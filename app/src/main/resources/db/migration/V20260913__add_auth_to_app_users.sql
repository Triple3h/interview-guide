-- 用户账号体系：登录字段（管理端不落用户表，用 .env 固定 Token）
-- 说明：存量成员 username / password_hash 为空，需管理员重置密码并补用户名后激活

ALTER TABLE app_users ADD COLUMN IF NOT EXISTS username VARCHAR(50);
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS password_hash VARCHAR(100);
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMP(6);

-- 补历史缺迁移：实体 @Column(learning_skill_id) 存在但此前未走 Flyway
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS learning_skill_id VARCHAR(50);

CREATE UNIQUE INDEX IF NOT EXISTS idx_user_username ON app_users(username);

-- 补历史缺迁移：学习计划条目表（实体 LearningPlanItemEntity）
CREATE TABLE IF NOT EXISTS learning_plan_items (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  topic VARCHAR(200) NOT NULL,
  goal TEXT,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  sort_order INTEGER NOT NULL DEFAULT 0,
  source_session_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP(6)
);

CREATE INDEX IF NOT EXISTS idx_learning_plan_user ON learning_plan_items(user_id);
CREATE INDEX IF NOT EXISTS idx_learning_plan_topic ON learning_plan_items(user_id, topic);
