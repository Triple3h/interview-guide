package interview.guide.modules.knowledgebase.service.chunking;

import interview.guide.infrastructure.file.ParsedDocument;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseChunkingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用文本分块（兜底策略）
 * TokenTextSplitter 按 token 预算切分后，为相邻 chunk 追加尾部重叠，
 * 避免答案/要点恰好落在分块边界被切断
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class DefaultTokenChunker implements ContentChunker {

    /** 重叠片段起点回吸时认定的句子/短语边界字符 */
    private static final String SENTENCE_BOUNDARIES = "。！？；!?;\n，、, ";

    private final KnowledgeBaseChunkingProperties properties;
    private final TokenTextSplitter splitter;

    public DefaultTokenChunker(KnowledgeBaseChunkingProperties properties) {
        this.properties = properties;
        this.splitter = TokenTextSplitter.builder()
            .withChunkSize(properties.getChunkSizeTokens())
            .build();
    }

    @Override
    public boolean supports(ParsedDocument parsed) {
        return true;
    }

    @Override
    public List<Document> chunk(ParsedDocument parsed) {
        List<Document> baseChunks = splitter.apply(List.of(new Document(parsed.content())));
        return applyOverlap(baseChunks);
    }

    /**
     * 把前一个 chunk 的尾部拼接为下一个 chunk 的开头
     */
    private List<Document> applyOverlap(List<Document> baseChunks) {
        int overlapChars = properties.getOverlapChars();
        if (baseChunks.size() <= 1) {
            return withMetadata(baseChunks);
        }

        List<Document> result = new ArrayList<>(baseChunks.size());
        for (int i = 0; i < baseChunks.size(); i++) {
            String text = baseChunks.get(i).getText();
            String combined = i == 0
                ? text
                : tailOf(baseChunks.get(i - 1).getText(), overlapChars) + text;
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(ChunkMetadataKeys.CHUNK_TYPE, ChunkMetadataKeys.TYPE_GENERAL);
            result.add(new Document(combined, metadata));
        }
        return result;
    }

    private String tailOf(String text, int overlapChars) {
        if (text == null || text.isEmpty() || overlapChars <= 0) {
            return "";
        }
        int start = Math.max(0, text.length() - overlapChars);
        // 回吸到句子边界，避免重叠片段从词中间开始
        while (start < text.length() - 1 && SENTENCE_BOUNDARIES.indexOf(text.charAt(start)) < 0) {
            start++;
        }
        if (start < text.length() - 1) {
            start++; // 跳过边界字符本身
        }
        return text.substring(start);
    }

    private List<Document> withMetadata(List<Document> baseChunks) {
        return baseChunks.stream()
            .map(doc -> {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put(ChunkMetadataKeys.CHUNK_TYPE, ChunkMetadataKeys.TYPE_GENERAL);
                return new Document(doc.getText(), metadata);
            })
            .toList();
    }
}
