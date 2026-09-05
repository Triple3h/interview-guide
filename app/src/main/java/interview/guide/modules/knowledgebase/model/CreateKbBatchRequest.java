package interview.guide.modules.knowledgebase.model;

import jakarta.validation.constraints.Size;

/**
 * 创建批量上传批次请求
 */
public record CreateKbBatchRequest(
    @Size(max = 200, message = "批次名称最长 200 字符") String name) {
}
