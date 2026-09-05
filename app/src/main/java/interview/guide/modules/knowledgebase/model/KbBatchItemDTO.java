package interview.guide.modules.knowledgebase.model;

import java.time.LocalDateTime;

/**
 * 批量上传批次明细 DTO
 */
public record KbBatchItemDTO(
    Long itemId,
    Long kbId,
    String fileName,
    String relativePath,
    String category,
    Long fileSize,
    String status,
    String error,
    LocalDateTime createdAt) {
}
