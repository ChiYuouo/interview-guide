ALTER TABLE interview_sessions ADD COLUMN user_id BIGINT;

UPDATE interview_sessions s
SET user_id = r.user_id
FROM resumes r
WHERE s.resume_id = r.id;
UPDATE interview_sessions SET user_id = 1 WHERE user_id IS NULL;

ALTER TABLE interview_sessions ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE interview_sessions ADD CONSTRAINT fk_interview_sessions_user
  FOREIGN KEY (user_id) REFERENCES app_users (id);

DROP INDEX uk_interview_sessions_request_id;
ALTER TABLE interview_sessions ADD CONSTRAINT uk_interview_sessions_user_request
  UNIQUE (user_id, request_id);
CREATE INDEX idx_interview_sessions_user_created ON interview_sessions (user_id, created_at);
