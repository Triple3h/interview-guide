package interview.guide.modules.knowledgebase.model;

import java.util.List;

/**
 * 分类树节点：一级分类下挂二级分类（category 约定为 "一级/二级"，斜杠分隔，最多两级）
 */
public record CategoryTreeNode(
    String name,
    List<String> children
) {
}
