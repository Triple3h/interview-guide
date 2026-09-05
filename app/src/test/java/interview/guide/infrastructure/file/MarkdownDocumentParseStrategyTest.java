package interview.guide.infrastructure.file;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Markdown 文档解析策略测试")
class MarkdownDocumentParseStrategyTest {

    private final MarkdownDocumentParseStrategy strategy = new MarkdownDocumentParseStrategy();

    @Test
    @DisplayName("支持 md/markdown 扩展名，不支持其他")
    void shouldSupportOnlyMarkdownExtensions() {
        assertTrue(strategy.supports("notes.md"));
        assertTrue(strategy.supports("notes.markdown"));
        assertTrue(strategy.supports("README.MD"));
        assertFalse(strategy.supports("notes.txt"));
        assertFalse(strategy.supports("notes.docx"));
        assertFalse(strategy.supports(null));
    }

    @Test
    @DisplayName("解析结果标记为 MARKDOWN 且保留标题与列表结构")
    void shouldParseAndPreserveStructure() {
        String content = """
            # 题库说明

            ## 第一章
            - 要点一
            - 要点二
            """;
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        ParsedDocument parsed = strategy.parse(bytes, "notes.md");

        assertEquals(ParsedDocument.DocumentFormat.MARKDOWN, parsed.format());
        assertTrue(parsed.content().contains("# 题库说明"), "标题结构应保留");
        assertTrue(parsed.content().contains("- 要点一"), "列表结构应保留");
    }

    @Test
    @DisplayName("空内容返回空字符串")
    void shouldReturnEmptyForEmptyBytes() {
        ParsedDocument parsed = strategy.parse(new byte[0], "notes.md");

        assertEquals("", parsed.content());
    }
}
