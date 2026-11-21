package interview.guide.modules.interviewschedule.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.ai.StructuredOutputProperties;
import interview.guide.common.config.LlmProviderProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.OngoingStubbing;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("面试邀约解析")
class InterviewParseServiceTest {
  private static final String RAW_INVITE =
      "星河科技邀请你于 2026 年 11 月 25 日下午两点参加 Java 开发工程师第二轮视频面试。";
  private static final LocalDateTime INTERVIEW_TIME = LocalDateTime.of(2026, 11, 25, 14, 0);
  private static final String STRUCTURED_INVITE = """
      {
        "companyName": "星河科技",
        "position": "Java 开发工程师",
        "interviewTime": "2026-11-25T14:00",
        "interviewType": "VIDEO",
        "meetingLink": "",
        "roundNumber": 2,
        "interviewer": "",
        "notes": "预计 30 分钟"
      }
      """;

  @Mock
  private LlmProviderRegistry registry;

  @Mock
  private ChatModel model;

  private InterviewParseService service;

  @BeforeEach
  void setUp() {
    var invoker = new StructuredOutputInvoker(new StructuredOutputProperties(), null);
    service = new InterviewParseService(registry, invoker,
        new PromptSanitizer(new LlmProviderProperties()));
  }

  @Test
  @DisplayName("飞书规则命中时正确提取中文轮次并跳过模型")
  void parsesFeishuInviteWithoutAi() {
    var response = service.parse("""
        公司：星河科技
        岗位：Java工程师
        时间：2026-11-25 14:00
        https://meeting.feishu.cn/example
        第二轮
        """, "feishu");

    assertThat(response.getSuccess()).isTrue();
    assertThat(response.getParseMethod()).isEqualTo("rule");
    assertThat(response.getData().getInterviewTime()).isEqualTo(INTERVIEW_TIME);
    assertThat(response.getData().getRoundNumber()).isEqualTo(2);
    verifyNoInteractions(registry, model);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = "feishu")
  @DisplayName("规则解析失败时按默认模型提取结构化结果")
  void fallsBackToDefaultModel(String source) {
    stubModel(STRUCTURED_INVITE);

    var response = service.parse(RAW_INVITE, source);

    assertThat(response.getSuccess()).isTrue();
    assertThat(response.getParseMethod()).isEqualTo("ai");
    assertThat(response.getData().getCompanyName()).isEqualTo("星河科技");
    assertThat(response.getData().getPosition()).isEqualTo("Java 开发工程师");
    assertThat(response.getData().getInterviewTime()).isEqualTo(INTERVIEW_TIME);
    assertThat(response.getData().getRoundNumber()).isEqualTo(2);
    assertThat(response.getData().getMeetingLink()).isNull();
    verify(registry).getChatClientOrDefault(null);

    var prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(model).call(prompt.capture());
    assertThat(prompt.getValue().getSystemMessage().getText())
        .contains(LocalDate.now().toString(), "JSON Schema")
        .doesNotContain(RAW_INVITE);
    assertThat(prompt.getValue().getUserMessage().getText()).contains(RAW_INVITE, "<data-boundary-");
  }

  @Test
  @DisplayName("模型首次输出错误格式时重试并使用结构化结果")
  void retriesMalformedOutput() {
    stubModel("这是一条面试邀约。").thenReturn(modelResponse(STRUCTURED_INVITE));

    var response = service.parse(RAW_INVITE, "feishu");

    assertThat(response.getSuccess()).isTrue();
    assertThat(response.getData().getInterviewTime()).isEqualTo(INTERVIEW_TIME);
    verify(model, times(2)).call(any(Prompt.class));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      """
      {"companyName":" ", "position":"Java 开发工程师", "interviewTime":"2026-11-25T14:00:00"}
      """,
      """
      {"companyName":"星河科技", "interviewTime":"2026-11-25T14:00:00"}
      """,
      """
      {"companyName":"星河科技", "position":"Java 开发工程师", "interviewTime":"2026-11-31T14:00:00"}
      """
  })
  @DisplayName("模型结果缺少有效必填信息时提示解析失败")
  void rejectsIncompleteModelResult(String output) {
    stubModel(output);

    var response = service.parse(RAW_INVITE, "feishu");

    assertThat(response.getSuccess()).isFalse();
    assertThat(response.getParseMethod()).isEqualTo("none");
    assertThat(response.getData()).isNull();
    verify(model, atLeastOnce()).call(any(Prompt.class));
  }

  private OngoingStubbing<ChatResponse> stubModel(String output) {
    when(model.getOptions()).thenCallRealMethod();
    when(registry.getChatClientOrDefault(null)).thenReturn(ChatClient.builder(model).build());
    return when(model.call(any(Prompt.class))).thenReturn(modelResponse(output));
  }

  private static ChatResponse modelResponse(String output) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(output))));
  }
}
