package interview.guide.modules.knowledgebase.service.chunking;

import interview.guide.infrastructure.file.ParsedDocument;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseChunkingProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("文档分块分发服务测试")
class DocumentChunkingServiceTest {

    private DocumentChunkingService service() {
        KnowledgeBaseChunkingProperties properties = new KnowledgeBaseChunkingProperties();
        properties.setMinChunkChars(0); // 关闭合并，块数与断言一一对应
        return new DocumentChunkingService(List.of(
            new QaContentChunker(properties),
            new MarkdownHeadingChunker(properties),
            new DefaultTokenChunker(properties)
        ));
    }

    @Test
    @DisplayName("空解析结果返回空列表")
    void shouldReturnEmptyForNullOrBlank() {
        DocumentChunkingService chunkingService = service();

        assertEquals(0, chunkingService.chunk(null).size());
        assertEquals(0, chunkingService.chunk(new ParsedDocument("   ", ParsedDocument.DocumentFormat.PLAIN_TEXT)).size());
    }

    @Test
    @DisplayName("题库内容优先于 Markdown 标题策略（QA 按题切分）")
    void shouldPreferQaOverMarkdown() {
        String content = """
            # 题库

            1. 第一题题干。答案：A
            2. 第二题题干。答案：B
            3. 第三题题干。答案：C
            """;
        DocumentChunkingService chunkingService = service();

        List<Document> chunks = chunkingService.chunk(new ParsedDocument(content, ParsedDocument.DocumentFormat.MARKDOWN));

        assertEquals(ChunkMetadataKeys.TYPE_QA, chunks.get(1).getMetadata().get(ChunkMetadataKeys.CHUNK_TYPE),
            "题库内容应命中 QA 策略而不是标题策略（第 0 块为导语）");
    }

    @Test
    @DisplayName("Markdown 文档走标题分块")
    void shouldUseMarkdownChunkerForMarkdown() {
        String content = """
            # 章节
            正文内容。
            """;
        DocumentChunkingService chunkingService = service();

        List<Document> chunks = chunkingService.chunk(new ParsedDocument(content, ParsedDocument.DocumentFormat.MARKDOWN));

        assertEquals(ChunkMetadataKeys.TYPE_SECTION, chunks.get(0).getMetadata().get(ChunkMetadataKeys.CHUNK_TYPE));
    }

    @Test
    @DisplayName("二进制文档提取的文本走通用分块")
    void shouldUseDefaultChunkerForRichText() {
        DocumentChunkingService chunkingService = service();

        List<Document> chunks = chunkingService.chunk(
            new ParsedDocument("这是一段从 PDF 提取的普通正文内容。", ParsedDocument.DocumentFormat.RICH_TEXT));

        assertEquals(ChunkMetadataKeys.TYPE_GENERAL, chunks.get(0).getMetadata().get(ChunkMetadataKeys.CHUNK_TYPE));
    }

    @Test
    @DisplayName("所有 chunk 顺序写入 chunk_seq")
    void shouldAssignSequentialChunkSeq() {
        String content = """
            Java 面试题库。

            1. 第一题题干。答案：A
            2. 第二题题干。答案：B
            3. 第三题题干。答案：C
            """;
        DocumentChunkingService chunkingService = service();

        List<Document> chunks = chunkingService.chunk(new ParsedDocument(content, ParsedDocument.DocumentFormat.RICH_TEXT));

        assertTrue(chunks.size() >= 2);
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).getMetadata().get(ChunkMetadataKeys.CHUNK_SEQ));
        }
    }
}
