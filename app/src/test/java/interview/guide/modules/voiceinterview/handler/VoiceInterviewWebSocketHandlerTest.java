package interview.guide.modules.voiceinterview.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import interview.guide.modules.llmprovider.service.LlmProviderConfigService;
import interview.guide.modules.voiceinterview.config.VoiceInterviewHandshakeInterceptor;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.context.VoiceContextCompressor;
import interview.guide.modules.voiceinterview.dto.WebSocketControlMessage;
import interview.guide.modules.voiceinterview.service.DashscopeLlmService;
import interview.guide.modules.voiceinterview.service.QwenAsrService;
import interview.guide.modules.voiceinterview.service.QwenTtsService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoiceInterviewWebSocketHandlerTest {

  @Mock
  private ObjectMapper objectMapper;
  @Mock
  private QwenAsrService sttService;
  @Mock
  private QwenTtsService ttsService;
  @Mock
  private LlmProviderConfigService providerConfigService;
  @Mock
  private DashscopeLlmService llmService;
  @Mock
  private VoiceInterviewService interviewService;
  @Mock
  private VoiceContextCompressor voiceContextCompressor;
  @Mock
  private interview.guide.modules.voiceinterview.context.VoiceHistoryLoader voiceHistoryLoader;
  @Mock
  private ObjectProvider<MeterRegistry> meterRegistryProvider;

  private VoiceInterviewWebSocketHandler handler;

  @AfterEach
  void tearDown() {
    if (handler != null) {
      handler.destroy();
    }
  }

  @Test
  @DisplayName("WebSocket 结束语音面试使用握手绑定的账号和会话 ID")
  void endsInterviewWithTheAuthenticatedSocketAccount() throws Exception {
    handler = newHandler(new VoiceInterviewProperties());
    var socket = mock(WebSocketSession.class);
    when(socket.getAttributes()).thenReturn(Map.of(
        VoiceInterviewHandshakeInterceptor.USER_ID_ATTRIBUTE, 7L,
        VoiceInterviewHandshakeInterceptor.SESSION_ID_ATTRIBUTE, 42L));

    String payload = """
        {"type":"control","action":"end_interview","data":{"userId":99,"sessionId":99}}
        """;
    var json = new ObjectMapper().readTree(payload);
    when(objectMapper.readTree(payload)).thenReturn(json);
    when(objectMapper.treeToValue(json, WebSocketControlMessage.class)).thenReturn(
        WebSocketControlMessage.builder().action("end_interview").data(Map.of("userId", 99, "sessionId", 99)).build());

    handler.handleTextMessage(socket, new TextMessage(payload));

    verify(interviewService).endSession(7L, 42L);
  }

  @Test
  @DisplayName("同一开场白只在同一账号且配置未更新时复用音频")
  void cachesOpeningAudioByAccountAndSettingsVersion() {
    handler = newHandler(new VoiceInterviewProperties());
    var version = LocalDateTime.of(2026, 1, 1, 9, 0);
    String text = "你好，开始面试。";
    when(providerConfigService.getVoiceSettingsUpdatedAt(7L))
        .thenReturn(version, version, version.plusSeconds(1));
    when(providerConfigService.getVoiceSettingsUpdatedAt(8L)).thenReturn(version);
    when(ttsService.synthesize(7L, text)).thenReturn(new byte[]{1, 0}, new byte[]{3, 0});
    when(ttsService.synthesize(8L, text)).thenReturn(new byte[]{2, 0});

    byte[] first = ReflectionTestUtils.invokeMethod(handler, "getOpeningWavAudio", 7L, text);
    byte[] cached = ReflectionTestUtils.invokeMethod(handler, "getOpeningWavAudio", 7L, text);
    byte[] other = ReflectionTestUtils.invokeMethod(handler, "getOpeningWavAudio", 8L, text);
    byte[] updated = ReflectionTestUtils.invokeMethod(handler, "getOpeningWavAudio", 7L, text);

    assertThat(first).endsWith((byte) 1, (byte) 0);
    assertThat(cached).containsExactly(first);
    assertThat(other).endsWith((byte) 2, (byte) 0);
    assertThat(updated).endsWith((byte) 3, (byte) 0);
    verify(ttsService, times(2)).synthesize(7L, text);
    verify(ttsService).synthesize(8L, text);
  }

  @Test
  @DisplayName("上一轮提交后到达的临时字幕不会推送到当前轮")
  void shouldIgnoreLatePartialAfterPreviousTurnWasSubmitted() throws Exception {
    WebSocketSession session = mock(WebSocketSession.class);
    handler = newHandler(new VoiceInterviewProperties(), new ObjectMapper());
    prepareSttState(49L, session, true);

    invokeHandleSttResult(49L, "这是上一轮回答", false);

    verify(session, never()).sendMessage(any(TextMessage.class));
  }

  @Test
  @DisplayName("上一轮处理完成后当前轮临时字幕可以正常推送")
  void shouldSendPartialForCurrentTurnWhenNotProcessing() throws Exception {
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.isOpen()).thenReturn(true);
    handler = newHandler(new VoiceInterviewProperties(), new ObjectMapper());
    prepareSttState(49L, session, false);

    invokeHandleSttResult(49L, "这是当前轮回答", false);

    ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
    verify(session).sendMessage(messageCaptor.capture());
    assertThat(messageCaptor.getValue().getPayload())
        .contains("\"type\":\"subtitle\"")
        .contains("这是当前轮回答");
  }

  private VoiceInterviewWebSocketHandler newHandler(VoiceInterviewProperties properties) {
    return newHandler(properties, objectMapper);
  }

  private VoiceInterviewWebSocketHandler newHandler(
      VoiceInterviewProperties properties,
      ObjectMapper handlerObjectMapper) {
    return new VoiceInterviewWebSocketHandler(
        handlerObjectMapper,
        sttService,
        ttsService,
        providerConfigService,
        llmService,
        interviewService,
        voiceContextCompressor,
        voiceHistoryLoader,
        properties,
        meterRegistryProvider
    );
  }

  private void prepareSttState(
      Long sessionId,
      WebSocketSession session,
      boolean processing) throws Exception {
    fieldMap("sessions").put(sessionId, session);

    Class<?> stateType = Class.forName(
        VoiceInterviewWebSocketHandler.class.getName() + "$SessionState");
    Constructor<?> constructor = stateType.getDeclaredConstructor();
    constructor.setAccessible(true);
    Object state = constructor.newInstance();
    Method isProcessing = stateType.getDeclaredMethod("isProcessing");
    isProcessing.setAccessible(true);
    ((AtomicBoolean) isProcessing.invoke(state)).set(processing);
    fieldMap("sessionStates").put(sessionId, state);
  }

  private void invokeHandleSttResult(
      Long sessionId,
      String text,
      boolean finalSegment) throws Exception {
    Method method = VoiceInterviewWebSocketHandler.class.getDeclaredMethod(
        "handleSttResult", Long.class, String.class, boolean.class);
    method.setAccessible(true);
    method.invoke(handler, sessionId, text, finalSegment);
  }

  @SuppressWarnings("unchecked")
  private Map<Long, Object> fieldMap(String fieldName) throws Exception {
    Field field = VoiceInterviewWebSocketHandler.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    return (Map<Long, Object>) field.get(handler);
  }
  @Nested
  @DisplayName("STT 日志隐私")
  class SttLogPrivacy {

    private final Logger handlerLogger =
        (Logger) LoggerFactory.getLogger(VoiceInterviewWebSocketHandler.class);
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
    private ch.qos.logback.classic.Level originalLevel;

    @BeforeEach
    void setUpAppender() {
      originalLevel = handlerLogger.getLevel();
      handlerLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
      logAppender.start();
      handlerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDownAppender() {
      handlerLogger.setLevel(originalLevel);
      handlerLogger.detachAppender(logAppender);
    }

    @Test
    @DisplayName("迟到与最终转写片段日志不包含转写原文，只记录长度")
    void shouldNotLogSttText() throws Exception {
      String lateMarker = "迟到转写敏感标记MARKER-STT-LATE-2e9b";
      String finalMarker = "最终转写敏感标记MARKER-STT-FINAL-c47a";
      WebSocketSession session = mock(WebSocketSession.class);
      when(session.isOpen()).thenReturn(true);
      handler = newHandler(new VoiceInterviewProperties(), new ObjectMapper());
      prepareSttState(51L, session, true);

      invokeHandleSttResult(51L, lateMarker, false);

      prepareSttState(52L, session, false);
      invokeHandleSttResult(52L, finalMarker, true);

      StringBuilder logs = new StringBuilder();
      for (ILoggingEvent event : logAppender.list) {
        logs.append(event.getFormattedMessage()).append('\n');
      }
      assertThat(logs.toString()).doesNotContain(lateMarker);
      assertThat(logs.toString()).doesNotContain(finalMarker);
      assertThat(logs.toString()).contains("textLength=" + lateMarker.length());
      assertThat(logs.toString()).contains("textLength=" + finalMarker.length());
    }
  }
}
