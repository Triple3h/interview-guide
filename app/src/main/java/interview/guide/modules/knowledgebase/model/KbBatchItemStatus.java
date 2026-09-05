package interview.guide.modules.knowledgebase.model;

/**
 * 批量上传批次明细状态
 * PENDING/PROCESSING/COMPLETED/FAILED 与向量化链路联动，
 * DUPLICATE_SKIPPED/REJECTED 为上传接入阶段的终态
 */
public enum KbBatchItemStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    DUPLICATE_SKIPPED,
    REJECTED
}
