package interview.guide.modules.knowledgebase.service.chunking;

import interview.guide.infrastructure.file.ParsedDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档分块服务
 * 按 @Order 顺序挑选首个支持的分块策略（QA -> Markdown -> 通用兜底），
 * 统一为 chunk 写入 chunk_seq，并保证 metadata 可变以便后续追加 kb_id 等键
 */
@Slf4j
@Service
public class DocumentChunkingService {

    private final List<ContentChunker> chunkers;

    public DocumentChunkingService(List<ContentChunker> chunkers) {
        this.chunkers = chunkers;
    }

    public List<Document> chunk(ParsedDocument parsed) {
        if (parsed == null || parsed.isBlank()) {
            return List.of();
        }
        ContentChunker chunker = chunkers.stream()
            .filter(candidate -> candidate.supports(parsed))
            .findFirst()
            .orElse(null);
        if (chunker == null) {
            log.warn("没有匹配的分块策略: format={}", parsed.format());
            return List.of();
        }

        List<Document> chunks = chunker.chunk(parsed);
        List<Document> normalized = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
            metadata.put(ChunkMetadataKeys.CHUNK_SEQ, i);
            normalized.add(new Document(chunk.getText(), metadata));
        }
        log.info("分块完成: 策略={}, 格式={}, chunk数={}",
            chunker.getClass().getSimpleName(), parsed.format(), normalized.size());
        return normalized;
    }
}
