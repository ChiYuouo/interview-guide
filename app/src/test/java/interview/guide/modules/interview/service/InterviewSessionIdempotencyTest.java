package interview.guide.modules.interview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.InterviewSessionCache.CachedSession;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.listener.EvaluateStreamProducer;
import interview.guide.modules.interview.model.CreateInterviewRequest;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.resume.service.ResumePersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("文本面试创建幂等性")
class InterviewSessionIdempotencyTest {

  @Mock
  private InterviewQuestionService questionService;
  @Mock
  private AnswerEvaluationService evaluationService;
  @Mock
  private InterviewPersistenceService persistenceService;
  @Mock
  private InterviewSessionCache sessionCache;
  @Mock
  private EvaluateStreamProducer evaluateStreamProducer;
  @Mock
  private LlmProviderRegistry llmProviderRegistry;
  @Mock
  private RedisService redisService;
  @Mock
  private ResumePersistenceService resumes;

  private ObjectMapper objectMapper;
  private InterviewSessionService service;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    service = new InterviewSessionService(
        questionService,
        evaluationService,
        persistenceService,
        sessionCache,
        objectMapper,
        evaluateStreamProducer,
        llmProviderRegistry,
        redisService,
        resumes
    );
  }

  @Test
  @DisplayName("Redis 结果映射丢失后应从数据库恢复同一会话，不再次调用 LLM")
  void restoresExistingSessionFromDatabaseWhenRedisMappingIsMissing() {
    when(redisService.executeWithLock(anyString(), anyLong(), anyLong(), any(), any()))
        .thenAnswer(invocation -> {
          RedisService.LockedOperation<?> operation = invocation.getArgument(4);
          return operation.execute();
        });
    String requestId = "request-20260803";
    String existingSessionId = "existing-session";
    InterviewQuestionDTO question = InterviewQuestionDTO.create(0, "什么是 JVM？", "JVM", "JVM");
    InterviewSessionEntity entity = new InterviewSessionEntity();
    entity.setSessionId(existingSessionId);
    entity.setRequestId(requestId);
    when(redisService.get("interview:create:result:1:" + requestId)).thenReturn(null);
    when(persistenceService.findByRequestId(1L, requestId)).thenReturn(Optional.of(entity));
    CachedSession cached = new CachedSession(
        existingSessionId,
        "",
        null,
        null,
        null,
        List.of(question),
        0,
        SessionStatus.CREATED,
        objectMapper
    );
    when(sessionCache.getSession(1L, existingSessionId)).thenReturn(Optional.of(cached));
    CreateInterviewRequest request = new CreateInterviewRequest(
        "",
        8,
        null,
        true,
        "glm",
        "java-backend",
        "mid",
        null,
        null,
        requestId
    );

    InterviewSessionDTO result = service.createSession(1L, request);

    assertThat(result.sessionId()).isEqualTo(existingSessionId);
    verify(redisService).executeWithLock(
        eq("interview:create:1:" + requestId), anyLong(), anyLong(), any(), any());
    verify(questionService, never()).generateQuestionsBySkill(
        any(), anyString(), anyString(), any(), anyInt(), any(), any(), any());
  }

  @Test
  @DisplayName("会话和简历索引在当前账号缓存命中时直接返回")
  void returnsOwnedCacheHitsWithoutQueryingDatabase() {
    long userId = 2L;
    long resumeId = 42L;
    String sessionId = "cached-session";
    var cached = new CachedSession(sessionId, "", resumeId, null, null,
        List.of(), 0, SessionStatus.IN_PROGRESS, objectMapper);
    when(sessionCache.getSession(userId, sessionId)).thenReturn(Optional.of(cached));
    when(sessionCache.findUnfinishedSessionId(userId, resumeId)).thenReturn(Optional.of(sessionId));

    assertThat(service.getSession(userId, sessionId).sessionId()).isEqualTo(sessionId);
    assertThat(service.findUnfinishedSessionOrThrow(userId, resumeId).sessionId()).isEqualTo(sessionId);
    verifyNoInteractions(persistenceService, resumes);
  }

  @Test
  @DisplayName("没有幂等键的会话保存失败时仍按原流程写入当前账号缓存")
  void preservesCacheFallbackForNonIdempotentCreation() {
    long userId = 2L;
    var questions = List.of(InterviewQuestionDTO.create(0, "什么是 JVM？", "JVM", "JVM"));
    var request = new CreateInterviewRequest("简历文本", 8, null, true,
        "glm", "java-backend", "mid", null, null, null);
    when(questionService.generateQuestionsBySkill(any(), anyString(), anyString(), any(), anyInt(), any(), any(), any()))
        .thenReturn(questions);
    when(persistenceService.saveSession(eq(userId), anyString(), isNull(), eq(1), eq(questions),
        eq("glm"), eq("java-backend"), eq("mid"))).thenThrow(new IllegalStateException("数据库不可用"));

    InterviewSessionDTO result = service.createSession(userId, request);

    assertThat(result.questions()).isEqualTo(questions);
    verify(sessionCache).saveSession(userId, result.sessionId(), "简历文本", null, null, null,
        questions, 0, SessionStatus.CREATED);
  }
}
