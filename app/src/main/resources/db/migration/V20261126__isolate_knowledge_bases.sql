ALTER TABLE knowledge_bases ADD COLUMN user_id BIGINT;
UPDATE knowledge_bases SET user_id = 1;
ALTER TABLE knowledge_bases ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE knowledge_bases ADD CONSTRAINT fk_knowledge_bases_user
  FOREIGN KEY (user_id) REFERENCES app_users (id);
ALTER TABLE knowledge_bases DROP CONSTRAINT idx_kb_hash;
ALTER TABLE knowledge_bases ADD CONSTRAINT uk_knowledge_bases_user_file_hash
  UNIQUE (user_id, file_hash);
CREATE INDEX idx_knowledge_bases_user_uploaded ON knowledge_bases (user_id, uploaded_at);
