package interview.guide.modules.knowledgebase.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批量上传批次详情 DTO（含全部文件明细）
 */
public record KbBatchDetailDTO(
    Long batchId,
    String name,
    String status,
    long total,
    long pending,
    long processing,
    long completed,
    long failed,
    long duplicateSkipped,
    long rejected,
    LocalDateTime createdAt,
    List<KbBatchItemDTO> items) {
}
