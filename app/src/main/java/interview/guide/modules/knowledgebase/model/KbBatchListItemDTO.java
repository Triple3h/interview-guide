package interview.guide.modules.knowledgebase.model;

import java.time.LocalDateTime;

/**
 * 批量上传批次列表项 DTO
 * status 为派生值：PROCESSING（进行中）/ COMPLETED（已结束）
 */
public record KbBatchListItemDTO(
    Long batchId,
    String name,
    long total,
    long pending,
    long processing,
    long completed,
    long failed,
    long duplicateSkipped,
    long rejected,
    String status,
    LocalDateTime createdAt) {
}
