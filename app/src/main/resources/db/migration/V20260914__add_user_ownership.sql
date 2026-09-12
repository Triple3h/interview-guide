-- 数据归属（P2）：简历 / 面试会话 / 面试日程 / 语音面试会话归属到用户
-- 存量归属策略：统一归 app_users 中最小 id 成员（线上当前仅一个成员），由管理员重置密码后激活
-- 幂等：列/索引均 IF NOT EXISTS，可重复执行

-- ---------- 1. resumes（简历） ----------
ALTER TABLE resumes ADD COLUMN IF NOT EXISTS user_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_resume_user ON resumes(user_id);

-- 去重语义从「全局唯一」改为「按用户唯一」：不同用户上传同一份文件应各自保留一份
ALTER TABLE resumes DROP CONSTRAINT IF EXISTS idx_resume_hash;
CREATE UNIQUE INDEX IF NOT EXISTS uk_resume_user_hash ON resumes(user_id, file_hash);

-- ---------- 2. interview_sessions（面试会话） ----------
ALTER TABLE interview_sessions ADD COLUMN IF NOT EXISTS user_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_interview_session_user_created
  ON interview_sessions(user_id, created_at);

-- ---------- 3. interview_schedule（面试日程） ----------
ALTER TABLE interview_schedule ADD COLUMN IF NOT EXISTS user_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_interview_schedule_user ON interview_schedule(user_id);

-- ---------- 4. voice_interview_sessions（语音面试会话）----------
-- 历史列 user_id 为 VARCHAR(255) 且固定写入 'default'，归一为 BIGINT 并与 app_users.id 对齐
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_name = 'voice_interview_sessions'
      AND column_name = 'user_id'
      AND data_type = 'character varying'
  ) THEN
    ALTER TABLE voice_interview_sessions
      ALTER COLUMN user_id TYPE BIGINT
      USING (CASE WHEN user_id ~ '^[0-9]+$' THEN user_id::BIGINT ELSE NULL END);
  END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_voice_session_user ON voice_interview_sessions(user_id);

-- ---------- 5. 存量回填 ----------
UPDATE resumes SET user_id = (SELECT MIN(id) FROM app_users) WHERE user_id IS NULL;
UPDATE interview_sessions SET user_id = (SELECT MIN(id) FROM app_users) WHERE user_id IS NULL;
UPDATE interview_schedule SET user_id = (SELECT MIN(id) FROM app_users) WHERE user_id IS NULL;
UPDATE voice_interview_sessions SET user_id = (SELECT MIN(id) FROM app_users) WHERE user_id IS NULL;
UPDATE rag_chat_sessions SET user_id = (SELECT MIN(id) FROM app_users) WHERE user_id IS NULL;
UPDATE learning_records SET user_id = (SELECT MIN(id) FROM app_users) WHERE user_id IS NULL;
UPDATE learning_plan_items SET user_id = (SELECT MIN(id) FROM app_users) WHERE user_id IS NULL;
