package interview.guide.modules.knowledgebase.model;

import java.util.List;

/**
 * 知识库分页结果
 *
 * @param items 当前页数据
 * @param total 过滤后的总条数（用于前端计算总页数）
 * @param page  当前页码（从 0 开始，越界时返回空 items）
 * @param size  实际生效的每页条数
 */
public record KnowledgeBasePageDTO(
    List<KnowledgeBaseListItemDTO> items,
    long total,
    int page,
    int size
) {
}
