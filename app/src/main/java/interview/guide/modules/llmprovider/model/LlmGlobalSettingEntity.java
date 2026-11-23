package interview.guide.modules.llmprovider.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "llm_global_setting")
public class LlmGlobalSettingEntity {

  public static final Long ADMIN_USER_ID = 1L;

  @Id
  @Column(name = "user_id")
  private Long id;

  @Column(name = "default_chat_provider_id", length = 64)
  private String defaultChatProviderId;

  @Column(name = "default_embedding_provider_id", length = 64)
  private String defaultEmbeddingProviderId;

  @Column(name = "presets_initialized", nullable = false)
  private boolean presetsInitialized;

  @Column(name = "asr_config_json", columnDefinition = "TEXT")
  private String asrConfigJson;

  @Column(name = "asr_api_key_nonce", columnDefinition = "VARCHAR(64)")
  private String asrApiKeyNonce;

  @Column(name = "asr_api_key_ciphertext", columnDefinition = "TEXT")
  private String asrApiKeyCiphertext;

  @Column(name = "tts_config_json", columnDefinition = "TEXT")
  private String ttsConfigJson;

  @Column(name = "tts_api_key_nonce", columnDefinition = "VARCHAR(64)")
  private String ttsApiKeyNonce;

  @Column(name = "tts_api_key_ciphertext", columnDefinition = "TEXT")
  private String ttsApiKeyCiphertext;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @PrePersist
  void prePersist() {
    LocalDateTime now = LocalDateTime.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = LocalDateTime.now();
  }
}
