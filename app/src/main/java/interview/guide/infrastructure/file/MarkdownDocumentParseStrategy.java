package interview.guide.infrastructure.file;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Markdown 文档解析策略（.md/.markdown）
 * 保留标题层级与列表结构，供下游按标题分块
 */
@Component
@Order(0)
public class MarkdownDocumentParseStrategy extends AbstractTextDocumentParseStrategy {

    @Override
    protected Set<String> extensions() {
        return Set.of("md", "markdown");
    }

    @Override
    protected ParsedDocument.DocumentFormat format() {
        return ParsedDocument.DocumentFormat.MARKDOWN;
    }
}
