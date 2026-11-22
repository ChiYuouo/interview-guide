ALTER TABLE rag_chat_sessions ADD COLUMN user_id BIGINT;
UPDATE rag_chat_sessions SET user_id = 1;
ALTER TABLE rag_chat_sessions ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE rag_chat_sessions ADD CONSTRAINT fk_rag_chat_sessions_user
  FOREIGN KEY (user_id) REFERENCES app_users (id);
CREATE INDEX idx_rag_chat_sessions_user_pinned_updated
  ON rag_chat_sessions (user_id, is_pinned DESC, updated_at DESC);
