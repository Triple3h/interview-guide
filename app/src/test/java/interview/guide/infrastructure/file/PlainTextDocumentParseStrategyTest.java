package interview.guide.infrastructure.file;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("纯文本文档解析策略测试")
class PlainTextDocumentParseStrategyTest {

    private final PlainTextDocumentParseStrategy strategy = new PlainTextDocumentParseStrategy();

    @Nested
    @DisplayName("文件类型支持")
    class Supports {

        @Test
        @DisplayName("支持 txt/text 扩展名，大小写不敏感")
        void shouldSupportTxtExtensions() {
            assertTrue(strategy.supports("题库.txt"));
            assertTrue(strategy.supports("notes.text"));
            assertTrue(strategy.supports("NOTES.TXT"));
        }

        @Test
        @DisplayName("不支持其他扩展名、无扩展名或空文件名")
        void shouldRejectOtherExtensions() {
            assertFalse(strategy.supports("doc.md"));
            assertFalse(strategy.supports("doc.docx"));
            assertFalse(strategy.supports("noextension"));
            assertFalse(strategy.supports(null));
        }
    }

    @Nested
    @DisplayName("字符集解码")
    class CharsetDecode {

        @Test
        @DisplayName("UTF-8 内容正常解码")
        void shouldDecodeUtf8() {
            byte[] bytes = "面试题：HashMap 的底层实现".getBytes(StandardCharsets.UTF_8);

            ParsedDocument parsed = strategy.parse(bytes, "bank.txt");

            assertTrue(parsed.content().contains("HashMap 的底层实现"));
            assertEquals(ParsedDocument.DocumentFormat.PLAIN_TEXT, parsed.format());
        }

        @Test
        @DisplayName("GBK 内容自动回退解码")
        void shouldDecodeGbk() {
            String text = "这是一份常见的 GBK 编码面试题库，包含大量中文内容，"
                + "例如 HashMap 的底层实现、JVM 内存模型、Spring 事务传播机制等知识点。";
            byte[] bytes = text.getBytes(Charset.forName("GBK"));

            ParsedDocument parsed = strategy.parse(bytes, "bank.txt");

            assertTrue(parsed.content().contains("HashMap 的底层实现"), "GBK 内容应被正确解码，实际: " + parsed.content());
        }

        @Test
        @DisplayName("UTF-8 BOM 自动剥离")
        void shouldStripUtf8Bom() {
            byte[] body = "第一行内容".getBytes(StandardCharsets.UTF_8);
            byte[] bytes = new byte[body.length + 3];
            bytes[0] = (byte) 0xEF;
            bytes[1] = (byte) 0xBB;
            bytes[2] = (byte) 0xBF;
            System.arraycopy(body, 0, bytes, 3, body.length);

            ParsedDocument parsed = strategy.parse(bytes, "bank.txt");

            assertEquals("第一行内容", parsed.content());
        }

        @Test
        @DisplayName("UTF-16LE BOM 自动识别")
        void shouldDecodeUtf16LeBom() {
            byte[] body = "第二行内容".getBytes(StandardCharsets.UTF_16LE);
            byte[] bytes = new byte[body.length + 2];
            bytes[0] = (byte) 0xFF;
            bytes[1] = (byte) 0xFE;
            System.arraycopy(body, 0, bytes, 2, body.length);

            ParsedDocument parsed = strategy.parse(bytes, "bank.txt");

            assertEquals("第二行内容", parsed.content());
        }

        @Test
        @DisplayName("空内容返回空字符串")
        void shouldReturnEmptyForEmptyBytes() {
            ParsedDocument parsed = strategy.parse(new byte[0], "bank.txt");

            assertEquals("", parsed.content());
        }
    }
}
