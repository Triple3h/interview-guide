package interview.guide.infrastructure.file;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 纯文本文档解析策略（.txt/.text）
 */
@Component
@Order(0)
public class PlainTextDocumentParseStrategy extends AbstractTextDocumentParseStrategy {

    @Override
    protected Set<String> extensions() {
        return Set.of("txt", "text");
    }

    @Override
    protected ParsedDocument.DocumentFormat format() {
        return ParsedDocument.DocumentFormat.PLAIN_TEXT;
    }
}
