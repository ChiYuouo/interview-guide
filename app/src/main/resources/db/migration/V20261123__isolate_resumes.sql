ALTER TABLE resumes ADD COLUMN user_id BIGINT;
UPDATE resumes SET user_id = 1;
ALTER TABLE resumes ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE resumes ADD CONSTRAINT fk_resumes_user FOREIGN KEY (user_id) REFERENCES app_users (id);
ALTER TABLE resumes DROP CONSTRAINT idx_resume_hash;
ALTER TABLE resumes ADD CONSTRAINT uk_resumes_user_file_hash UNIQUE (user_id, file_hash);
