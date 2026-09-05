package interview.guide.modules.knowledgebase.model;

/**
 * 批次内单文件上传结果
 */
public record KbBatchFileUploadResult(
    boolean duplicate,
    Long kbId,
    Long itemId,
    String status) {
}
