package interview.guide.modules.knowledgebase.service;

import interview.guide.common.config.LlmEmbeddingConfig;
import interview.guide.common.exception.BusinessException;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeBaseEmbeddingOwnerTest {
  @Test
  @DisplayName("向量检索按知识库的数据库所属账号选用Embedding配置")
  void usesPersistedOwnerForEachKnowledgeBase() {
    var config = mock(LlmEmbeddingConfig.class);
    var repository = mock(KnowledgeBaseRepository.class);
    var service = new KnowledgeBaseVectorService(mock(VectorRepository.class),
        null, new KnowledgeBaseVectorProperties(), null, config, repository);
    for (long owner : List.of(2L, 3L)) {
      long kbId = owner + 100;
      var kb = new KnowledgeBaseEntity();
      kb.setId(kbId);
      kb.setUserId(owner);
      var ownedStore = mock(VectorStore.class);
      when(repository.findAllById(List.of(kbId))).thenReturn(List.of(kb));
      when(config.vectorStoreForUser(owner)).thenReturn(ownedStore);
      when(ownedStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

      service.similaritySearch("问题", List.of(kbId), 3, 0.5);

      verify(config).vectorStoreForUser(owner);
      verify(ownedStore).similaritySearch(any(SearchRequest.class));
    }
  }

  @Test
  @DisplayName("拒绝混用不同账号的知识库，且不调用任何向量模型")
  void rejectsMixedOwnersBeforeEmbedding() {
    var config = mock(LlmEmbeddingConfig.class);
    var repository = mock(KnowledgeBaseRepository.class);
    var alice = new KnowledgeBaseEntity();
    alice.setId(12L);
    alice.setUserId(2L);
    var bob = new KnowledgeBaseEntity();
    bob.setId(13L);
    bob.setUserId(3L);
    when(repository.findAllById(List.of(12L, 13L))).thenReturn(List.of(alice, bob));
    var service = new KnowledgeBaseVectorService(mock(VectorRepository.class),
        null, new KnowledgeBaseVectorProperties(), null, config, repository);

    assertThatThrownBy(() -> service.similaritySearch("问题", List.of(12L, 13L), 3, 0.5))
        .isInstanceOf(BusinessException.class).hasMessageContaining("不属于同一账号");
    verifyNoInteractions(config);
  }
}
