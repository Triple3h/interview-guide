package interview.guide.modules.knowledgebase.service.chunking;

import interview.guide.infrastructure.file.ParsedDocument;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseChunkingProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("通用文本分块测试（TokenTextSplitter + 尾部重叠）")
class DefaultTokenChunkerTest {

    private String longContent() {
        StringBuilder contentBuilder = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            contentBuilder.append("这是第 ").append(i).append(" 段内容。")
                .append("Spring Boot 是一个优秀的 Java 框架，它简化了 Spring 应用的开发。")
                .append("通过自动配置和起步依赖，开发者可以快速构建生产级别的应用。")
                .append("Spring AI 提供了与各种 AI 模型交互的能力，包括 embedding 和 chat 功能。")
                .append("PostgreSQL 是一个强大的开源关系数据库，支持向量存储和相似度搜索。")
                .append("通过 pgvector 扩展，可以实现高效的向量索引和检索功能。")
                .append("\n\n");
        }
        return contentBuilder.toString();
    }

    private ParsedDocument richText(String content) {
        return new ParsedDocument(content, ParsedDocument.DocumentFormat.RICH_TEXT);
    }

    /**
     * cur 是否以 prev 的某个后缀（长度在 [minK, maxK] 区间）开头
     */
    private boolean startsWithSuffixOf(String prev, String cur, int maxK, int minK) {
        int upper = Math.min(maxK, prev.length());
        for (int k = upper; k >= minK; k--) {
            if (cur.startsWith(prev.substring(prev.length() - k))) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("通用分块兜底支持所有解析结果")
    void shouldSupportEverything() {
        DefaultTokenChunker chunker = new DefaultTokenChunker(props());

        assertTrue(chunker.supports(richText("任意内容")));
        assertTrue(chunker.supports(markdown("# 标题")));
    }

    @Test
    @DisplayName("短内容产生单个 chunk 并标记为 general")
    void shouldChunkShortContent() {
        DefaultTokenChunker chunker = new DefaultTokenChunker(props());

        List<Document> chunks = chunker.chunk(richText("这是一段很短的内容。"));

        assertEquals(1, chunks.size());
        assertEquals(ChunkMetadataKeys.TYPE_GENERAL, chunks.get(0).getMetadata().get(ChunkMetadataKeys.CHUNK_TYPE));
    }

    @Test
    @DisplayName("多块时相邻块之间存在尾部重叠")
    void shouldOverlapAdjacentChunks() {
        DefaultTokenChunker chunker = new DefaultTokenChunker(props()); // overlapChars = 200

        List<Document> chunks = chunker.chunk(richText(longContent()));

        assertTrue(chunks.size() >= 2, "长文本应产生多个 chunk，实际: " + chunks.size());
        for (int i = 1; i < chunks.size(); i++) {
            String prev = chunks.get(i - 1).getText();
            String cur = chunks.get(i).getText();
            assertTrue(startsWithSuffixOf(prev, cur, 200, 1),
                "第 " + i + " 块应以前一块的尾部片段开头");
        }
    }

    @Test
    @DisplayName("关闭重叠后相邻块之间无重叠")
    void shouldNotOverlapWhenDisabled() {
        KnowledgeBaseChunkingProperties properties = props();
        properties.setOverlapChars(0);
        DefaultTokenChunker chunker = new DefaultTokenChunker(properties);

        List<Document> chunks = chunker.chunk(richText(longContent()));

        assertTrue(chunks.size() >= 2);
        for (int i = 1; i < chunks.size(); i++) {
            String prev = chunks.get(i - 1).getText();
            String cur = chunks.get(i).getText();
            assertFalse(startsWithSuffixOf(prev, cur, 200, 2),
                "关闭重叠后第 " + i + " 块不应以前块的尾部开头");
        }
    }

    private KnowledgeBaseChunkingProperties props() {
        return new KnowledgeBaseChunkingProperties();
    }

    private ParsedDocument markdown(String content) {
        return new ParsedDocument(content, ParsedDocument.DocumentFormat.MARKDOWN);
    }
}
