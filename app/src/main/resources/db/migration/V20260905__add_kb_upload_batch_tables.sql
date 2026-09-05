-- 知识库批量上传：批次与批次明细，一次批量上传视为一个解析任务
CREATE TABLE IF NOT EXISTS knowledge_base_upload_batches (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(200) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP(6)
);

CREATE TABLE IF NOT EXISTS knowledge_base_upload_batch_items (
  id BIGSERIAL PRIMARY KEY,
  batch_id BIGINT NOT NULL,
  kb_id BIGINT,
  file_name VARCHAR(500) NOT NULL,
  relative_path VARCHAR(1000),
  category VARCHAR(100),
  file_size BIGINT,
  status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
  error VARCHAR(500),
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP(6)
);

ALTER TABLE knowledge_base_upload_batch_items
  DROP CONSTRAINT IF EXISTS knowledge_base_upload_batch_items_status_check;

ALTER TABLE knowledge_base_upload_batch_items
  ADD CONSTRAINT knowledge_base_upload_batch_items_status_check
    CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'DUPLICATE_SKIPPED', 'REJECTED'));

CREATE INDEX IF NOT EXISTS idx_kb_batch_items_batch
  ON knowledge_base_upload_batch_items(batch_id);

CREATE INDEX IF NOT EXISTS idx_kb_batch_items_kb
  ON knowledge_base_upload_batch_items(kb_id);

ALTER TABLE knowledge_bases
  ADD COLUMN IF NOT EXISTS batch_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_kb_batch
  ON knowledge_bases(batch_id);
