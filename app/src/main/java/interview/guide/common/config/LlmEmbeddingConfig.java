package interview.guide.common.config;

import interview.guide.common.ai.LlmProviderRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PgVectorStoreProperties.class)
@RequiredArgsConstructor
public class LlmEmbeddingConfig {
  private final LlmProviderRegistry registry;
  private final JdbcTemplate jdbcTemplate;
  private final PgVectorStoreProperties properties;

  public VectorStore vectorStoreForUser(Long userId) {
    return PgVectorStore.builder(jdbcTemplate, registry.getDefaultEmbeddingModel(userId))
        .schemaName(properties.getSchemaName()).idType(properties.getIdType())
        .vectorTableName(properties.getTableName()).dimensions(properties.getDimensions())
        .distanceType(properties.getDistanceType()).indexType(properties.getIndexType())
        .initializeSchema(false).maxDocumentBatchSize(properties.getMaxDocumentBatchSize()).build();
  }
}
