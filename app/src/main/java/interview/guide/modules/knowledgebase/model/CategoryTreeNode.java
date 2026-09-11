package interview.guide.modules.knowledgebase.model;

import java.util.List;

/**
 * 分类树节点：可递归，支持任意层级分类。
 *
 * <p>name 为该节点的<b>完整路径</b>（如 "ai"、"ai/agent"、"ai/agent/rag"），
 * 前端按最后一个斜杠之后的部分作为显示名，按 name 作为筛选值；children 为空表示叶子节点。
 */
public record CategoryTreeNode(
    String name,
    List<CategoryTreeNode> children
) {
}
