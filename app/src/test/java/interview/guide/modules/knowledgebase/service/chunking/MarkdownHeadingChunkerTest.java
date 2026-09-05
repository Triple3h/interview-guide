package interview.guide.modules.knowledgebase.service.chunking;

import interview.guide.infrastructure.file.ParsedDocument;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseChunkingProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Markdown 标题分块测试")
class MarkdownHeadingChunkerTest {

    private KnowledgeBaseChunkingProperties props() {
        return new KnowledgeBaseChunkingProperties();
    }

    private ParsedDocument markdown(String content) {
        return new ParsedDocument(content, ParsedDocument.DocumentFormat.MARKDOWN);
    }

    @Test
    @DisplayName("仅支持 MARKDOWN 格式")
    void shouldOnlySupportMarkdown() {
        MarkdownHeadingChunker chunker = new MarkdownHeadingChunker(props());

        assertTrue(chunker.supports(markdown("# 标题")));
        assertFalse(chunker.supports(new ParsedDocument("# 标题", ParsedDocument.DocumentFormat.PLAIN_TEXT)));
        assertFalse(chunker.supports(new ParsedDocument("内容", ParsedDocument.DocumentFormat.RICH_TEXT)));
    }

    @Nested
    @DisplayName("按标题切分")
    class SplitByHeading {

        @Test
        @DisplayName("按标题切节并写入标题路径与层级")
        void shouldSplitWithSectionPath() {
            String content = """
                # Java 基础
                Java 是一门面向对象语言。

                ## 集合
                List 和 Map 是常用容器。

                # 数据库
                MySQL 是关系型数据库。
                """;
            MarkdownHeadingChunker chunker = new MarkdownHeadingChunker(props());

            List<Document> chunks = chunker.chunk(markdown(content));

            assertEquals(3, chunks.size());
            assertEquals(ChunkMetadataKeys.TYPE_SECTION, chunks.get(0).getMetadata().get(ChunkMetadataKeys.CHUNK_TYPE));
            assertEquals("Java 基础", chunks.get(0).getMetadata().get(ChunkMetadataKeys.SECTION_PATH));
            assertEquals(1, chunks.get(0).getMetadata().get(ChunkMetadataKeys.HEADING_LEVEL));
            assertEquals("Java 基础 > 集合", chunks.get(1).getMetadata().get(ChunkMetadataKeys.SECTION_PATH));
            assertEquals(2, chunks.get(1).getMetadata().get(ChunkMetadataKeys.HEADING_LEVEL));
            assertEquals("数据库", chunks.get(2).getMetadata().get(ChunkMetadataKeys.SECTION_PATH));
            assertTrue(chunks.get(0).getText().contains("# Java 基础"), "标题文本应保留在 chunk 内容里");
        }

        @Test
        @DisplayName("首个标题前的导语不带标题路径")
        void shouldChunkPreambleWithoutPath() {
            String content = """
                这是文档开头的一段导语，说明本文档的用途。

                # 标题
                正文内容。
                """;
            MarkdownHeadingChunker chunker = new MarkdownHeadingChunker(props());

            List<Document> chunks = chunker.chunk(markdown(content));

            assertEquals(2, chunks.size());
            Document preamble = chunks.get(0);
            assertEquals(ChunkMetadataKeys.TYPE_SECTION, preamble.getMetadata().get(ChunkMetadataKeys.CHUNK_TYPE));
            assertFalse(preamble.getMetadata().containsKey(ChunkMetadataKeys.SECTION_PATH), "导语不应有标题路径");
        }

        @Test
        @DisplayName("代码围栏内的 # 不视为标题")
        void shouldNotSplitInsideCodeFence() {
            KnowledgeBaseChunkingProperties properties = props();
            properties.setMinChunkChars(0); // 关闭合并，聚焦围栏行为
            MarkdownHeadingChunker chunker = new MarkdownHeadingChunker(properties);

            String content = """
                # 配置说明
                以下为示例配置：

                ```
                # 这不是标题
                spring.profiles.active=dev
                ```

                # 真标题
                正文内容。
                """;

            List<Document> chunks = chunker.chunk(markdown(content));

            assertEquals(2, chunks.size(), "围栏内的 # 不应切分");
            Document first = chunks.get(0);
            assertTrue(first.getText().contains("这不是标题"), "围栏内容应留在配置说明一节");
            assertEquals("配置说明", first.getMetadata().get(ChunkMetadataKeys.SECTION_PATH));
        }
    }

    @Nested
    @DisplayName("合并与回退")
    class MergeAndFallback {

        @Test
        @DisplayName("同父路径的相邻小节合并后以父路径为元数据")
        void shouldMergeSmallSections() {
            String content = """
                # 指南
                ## 小节一
                短内容一
                ## 小节二
                短内容二
                """;
            MarkdownHeadingChunker chunker = new MarkdownHeadingChunker(props());

            List<Document> chunks = chunker.chunk(markdown(content));

            assertEquals(2, chunks.size(), "# 指南 独立成块，两个小节合并");
            Document merged = chunks.get(1);
            assertEquals("指南", merged.getMetadata().get(ChunkMetadataKeys.SECTION_PATH));
            assertEquals(1, merged.getMetadata().get(ChunkMetadataKeys.HEADING_LEVEL));
            assertTrue(merged.getText().contains("小节一"));
            assertTrue(merged.getText().contains("小节二"));
        }

        @Test
        @DisplayName("超长小节回退通用切分并保留路径与分段号")
        void shouldFallbackSplitOversizedSection() {
            KnowledgeBaseChunkingProperties properties = props();
            properties.setChunkSizeTokens(50); // maxChars = 100
            MarkdownHeadingChunker chunker = new MarkdownHeadingChunker(properties);

            StringBuilder body = new StringBuilder("# 长章节\n");
            for (int i = 0; i < 10; i++) {
                body.append("这是第 ").append(i)
                    .append(" 段很长的正文内容，用来把这一节撑到超过回退阈值，从而触发通用切分。\n\n");
            }

            List<Document> chunks = chunker.chunk(markdown(body.toString()));

            assertTrue(chunks.size() >= 2, "超长小节应被回退切分");
            for (int i = 0; i < chunks.size(); i++) {
                Document chunk = chunks.get(i);
                assertEquals(ChunkMetadataKeys.TYPE_SECTION, chunk.getMetadata().get(ChunkMetadataKeys.CHUNK_TYPE));
                assertEquals("长章节", chunk.getMetadata().get(ChunkMetadataKeys.SECTION_PATH));
                assertEquals(i + 1, chunk.getMetadata().get(ChunkMetadataKeys.PART));
            }
        }
    }
}
