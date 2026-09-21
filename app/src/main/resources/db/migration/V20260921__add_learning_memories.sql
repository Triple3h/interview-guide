-- 学习帮手个人记忆：跨会话、按学员隔离的偏好 / 提问 / 易错点等
-- 与学习台账（知识点）分开，不写入知识库向量表

CREATE TABLE IF NOT EXISTS learning_memories (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  kind VARCHAR(20) NOT NULL,
  content TEXT NOT NULL,
  source_session_id BIGINT,
  source_message_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP(6)
);

CREATE INDEX IF NOT EXISTS idx_learning_memory_user
  ON learning_memories(user_id);

CREATE INDEX IF NOT EXISTS idx_learning_memory_kind
  ON learning_memories(user_id, kind);

CREATE INDEX IF NOT EXISTS idx_learning_memory_source_message
  ON learning_memories(user_id, source_message_id);
