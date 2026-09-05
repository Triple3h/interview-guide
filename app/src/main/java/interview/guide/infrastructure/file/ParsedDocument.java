package interview.guide.infrastructure.file;

/**
 * 文档解析结果
 *
 * @param content 解析出的文本内容
 * @param format  文档格式，供下游分块策略选择使用
 */
public record ParsedDocument(String content, DocumentFormat format) {

    /**
     * 文档格式
     */
    public enum DocumentFormat {
        /** Markdown 文档，可按标题层级切分 */
        MARKDOWN,
        /** 纯文本文档 */
        PLAIN_TEXT,
        /** 从 PDF/Office 等二进制文档提取出的文本 */
        RICH_TEXT
    }

    public static ParsedDocument empty() {
        return new ParsedDocument("", DocumentFormat.PLAIN_TEXT);
    }

    public boolean isBlank() {
        return content == null || content.isBlank();
    }
}
