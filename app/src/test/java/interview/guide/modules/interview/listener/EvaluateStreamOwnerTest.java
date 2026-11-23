package interview.guide.modules.interview.listener;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.service.AnswerEvaluationService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvaluateStreamOwnerTest {
  @Test
  @DisplayName("后台评估从会话读取账号和同名模型，不依赖登录线程或管理员配置")
  void resolvesModelUsingPersistedSessionOwner() {
    var repository = mock(InterviewSessionRepository.class);
    var evaluator = mock(AnswerEvaluationService.class);
    var persistence = mock(InterviewPersistenceService.class);
    var registry = mock(LlmProviderRegistry.class);
    var consumer = new EvaluateStreamConsumer(mock(RedisService.class), repository, evaluator,
        persistence, JsonMapper.builder().build(), registry);

    for (long owner : List.of(2L, 3L)) {
      String id = "session-" + owner;
      var session = new InterviewSessionEntity();
      session.setUserId(owner);
      session.setSessionId(id);
      session.setLlmProvider("same-model");
      session.setQuestionsJson("[]");
      var client = mock(ChatClient.class);
      when(repository.findBySessionIdWithResume(id)).thenReturn(Optional.of(session));
      when(persistence.findAnswersBySessionId(owner, id)).thenReturn(List.of());
      when(registry.getChatClientOrDefault(owner, "same-model")).thenReturn(client);

      consumer.processBusiness(new EvaluateStreamConsumer.EvaluatePayload(id));

      verify(registry).getChatClientOrDefault(owner, "same-model");
      verify(evaluator).evaluateInterview(client, id, "", List.of());
      verify(persistence).saveReport(owner, id, null);
    }
  }
}
