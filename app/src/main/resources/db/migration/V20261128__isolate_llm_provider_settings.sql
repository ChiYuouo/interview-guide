-- 在原模型表上按账号隔离，原有配置归属保留的管理员账号。
ALTER TABLE llm_provider_config ADD COLUMN user_id BIGINT NOT NULL DEFAULT 1
  REFERENCES app_users(id);
ALTER TABLE llm_provider_config DROP CONSTRAINT llm_provider_config_pkey;
ALTER TABLE llm_provider_config ADD PRIMARY KEY (user_id, id);
ALTER TABLE llm_provider_config ALTER COLUMN api_key_ciphertext TYPE TEXT;
ALTER TABLE llm_provider_config ALTER COLUMN api_key_ciphertext DROP NOT NULL;
ALTER TABLE llm_provider_config ALTER COLUMN api_key_nonce DROP NOT NULL;

ALTER TABLE llm_global_setting RENAME COLUMN id TO user_id;
ALTER TABLE llm_global_setting ADD CONSTRAINT fk_llm_setting_user
  FOREIGN KEY (user_id) REFERENCES app_users(id);
ALTER TABLE llm_global_setting ALTER COLUMN default_chat_provider_id DROP NOT NULL;
ALTER TABLE llm_global_setting ALTER COLUMN default_embedding_provider_id DROP NOT NULL;
ALTER TABLE llm_global_setting ADD COLUMN presets_initialized BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE llm_global_setting SET presets_initialized = TRUE WHERE user_id = 1;

ALTER TABLE llm_global_setting
  ADD COLUMN asr_config_json TEXT,
  ADD COLUMN asr_api_key_nonce VARCHAR(64),
  ADD COLUMN asr_api_key_ciphertext TEXT,
  ADD COLUMN tts_config_json TEXT,
  ADD COLUMN tts_api_key_nonce VARCHAR(64),
  ADD COLUMN tts_api_key_ciphertext TEXT;

ALTER TABLE llm_provider_config ALTER COLUMN user_id DROP DEFAULT;
ALTER TABLE interview_sessions ALTER COLUMN llm_provider TYPE VARCHAR(64);
ALTER TABLE voice_interview_sessions ALTER COLUMN llm_provider TYPE VARCHAR(64);
