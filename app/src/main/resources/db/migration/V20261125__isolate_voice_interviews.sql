ALTER TABLE voice_interview_sessions ADD COLUMN account_user_id BIGINT;

UPDATE voice_interview_sessions s
SET account_user_id = r.user_id
FROM resumes r
WHERE s.resume_id = r.id;
UPDATE voice_interview_sessions SET account_user_id = 1 WHERE account_user_id IS NULL;

ALTER TABLE voice_interview_sessions DROP COLUMN user_id;
ALTER TABLE voice_interview_sessions RENAME COLUMN account_user_id TO user_id;
ALTER TABLE voice_interview_sessions ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE voice_interview_sessions ADD CONSTRAINT fk_voice_interview_sessions_user
  FOREIGN KEY (user_id) REFERENCES app_users (id);
CREATE INDEX idx_voice_interview_sessions_user_updated ON voice_interview_sessions (user_id, updated_at);
