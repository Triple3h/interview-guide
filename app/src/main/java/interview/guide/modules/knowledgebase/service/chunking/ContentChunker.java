package interview.guide.modules.knowledgebase.service.chunking;

import interview.guide.infrastructure.file.ParsedDocument;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 内容分块策略
 * 实现方需保证返回的 Document 携带可变的 metadata map
 */
public interface ContentChunker {

    /**
     * 是否支持该解析结果
     */
    boolean supports(ParsedDocument parsed);

    /**
     * 分块
     */
    List<Document> chunk(ParsedDocument parsed);
}
