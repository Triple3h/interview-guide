-- 学习帮手：学习成员（极简选人模式）+ 学习台账（知识台账）+ 会话/消息多用户扩展

-- 学习成员表（无登录，前端选人后以 X-User-Id 标识）
CREATE TABLE IF NOT EXISTS app_users (
  id BIGSERIAL PRIMARY KEY,
  nickname VARCHAR(50) NOT NULL,
  avatar_emoji VARCHAR(8),
  occupation VARCHAR(100),
  learning_direction VARCHAR(100),
  current_level VARCHAR(200),
  learning_goal VARCHAR(500),
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP(6)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_user_nickname
  ON app_users(nickname);

-- 学习台账：Agent 自动提炼/手动维护的知识点
CREATE TABLE IF NOT EXISTS learning_records (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  topic VARCHAR(200) NOT NULL,
  summary TEXT NOT NULL,
  mastery VARCHAR(20) NOT NULL,
  source_session_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP(6),
  last_reviewed_at TIMESTAMP(6)
);

CREATE INDEX IF NOT EXISTS idx_learning_record_user
  ON learning_records(user_id);

CREATE INDEX IF NOT EXISTS idx_learning_record_topic
  ON learning_records(user_id, topic);

-- 会话归属学习成员（存量数据为 NULL，首位成员创建时由应用回填）
ALTER TABLE rag_chat_sessions
  ADD COLUMN IF NOT EXISTS user_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_rag_session_user
  ON rag_chat_sessions(user_id);

-- Agent 工具调用步骤（JSON 数组，仅 ASSISTANT 消息，供前端回放）
ALTER TABLE rag_chat_messages
  ADD COLUMN IF NOT EXISTS tool_steps_json TEXT;
