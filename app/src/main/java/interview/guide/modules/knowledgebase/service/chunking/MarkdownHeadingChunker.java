package interview.guide.modules.knowledgebase.service.chunking;

import interview.guide.infrastructure.file.ParsedDocument;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseChunkingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Markdown 标题分块
 * 按 ATX 标题（#）切节并把标题路径写入 metadata；跟踪代码围栏，
 * 代码块内的 # 不视为标题；相邻小节合并、超长小节回退 TokenTextSplitter
 */
@Slf4j
@Component
@Order(100)
public class MarkdownHeadingChunker implements ContentChunker {

    private static final Pattern ATX_HEADING = Pattern.compile("^(#{1,6})\\s+(.*\\S)\\s*$");
    private static final String FENCE_MARKER = "```";
    private static final String PATH_SEPARATOR = " > ";

    private final KnowledgeBaseChunkingProperties properties;
    private final TokenTextSplitter fallbackSplitter;

    public MarkdownHeadingChunker(KnowledgeBaseChunkingProperties properties) {
        this.properties = properties;
        this.fallbackSplitter = TokenTextSplitter.builder()
            .withChunkSize(properties.getChunkSizeTokens())
            .build();
    }

    @Override
    public boolean supports(ParsedDocument parsed) {
        return parsed.format() == ParsedDocument.DocumentFormat.MARKDOWN;
    }

    @Override
    public List<Document> chunk(ParsedDocument parsed) {
        List<Section> sections = splitSections(parsed.content());
        List<Document> result = new ArrayList<>();
        mergeAndEmit(sections, result);
        return result;
    }

    private record HeadingEntry(int level, String text) {
    }

    private record Section(int level, String path, String headingLine, String body) {
        int length() {
            return headingLine.length() + body.length();
        }
    }

    private List<Section> splitSections(String content) {
        List<Section> sections = new ArrayList<>();
        List<HeadingEntry> headingStack = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        String currentPath = "";
        int currentLevel = 0;
        String currentHeadingLine = "";
        boolean seenHeading = false;
        boolean inFence = false;

        for (String line : content.split("\\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith(FENCE_MARKER)) {
                inFence = !inFence;
            }
            Matcher matcher = inFence ? null : ATX_HEADING.matcher(trimmed);
            if (matcher != null && matcher.matches()) {
                if (seenHeading) {
                    sections.add(new Section(currentLevel, currentPath, currentHeadingLine, body.toString()));
                } else if (!body.toString().isBlank()) {
                    // 首个标题前的内容作为无标题导语
                    sections.add(new Section(0, "", "", body.toString()));
                }
                seenHeading = true;

                int level = matcher.group(1).length();
                headingStack.removeIf(entry -> entry.level() >= level);
                headingStack.add(new HeadingEntry(level, matcher.group(2).strip()));
                currentLevel = level;
                currentHeadingLine = trimmed;
                currentPath = headingStack.stream()
                    .map(HeadingEntry::text)
                    .collect(Collectors.joining(PATH_SEPARATOR));
                body = new StringBuilder();
            } else {
                body.append(line).append('\n');
            }
        }

        if (seenHeading) {
            sections.add(new Section(currentLevel, currentPath, currentHeadingLine, body.toString()));
        } else if (!body.toString().isBlank()) {
            sections.add(new Section(0, "", "", body.toString()));
        }
        return sections;
    }

    private void mergeAndEmit(List<Section> sections, List<Document> result) {
        int minChunkChars = properties.getMinChunkChars();
        int i = 0;
        while (i < sections.size()) {
            Section first = sections.get(i);
            if (first.level() == 0) {
                // 无标题导语不参与合并
                emit(first.level(), first.path(), first.headingLine(), first.body(), result);
                i++;
                continue;
            }
            String parentPath = parentPathOf(first);
            int mergedLength = first.length();
            int j = i + 1;
            while (j < sections.size()
                && sections.get(j).level() > 0
                && parentPathOf(sections.get(j)).equals(parentPath)
                && mergedLength < minChunkChars) {
                mergedLength += sections.get(j).length();
                j++;
            }

            if (j == i + 1) {
                Section section = sections.get(i);
                emit(section.level(), section.path(), section.headingLine(), section.body(), result);
            } else {
                // 多个小节合并时以共同父路径作为 metadata
                StringBuilder body = new StringBuilder();
                for (int k = i; k < j; k++) {
                    body.append(sections.get(k).headingLine()).append('\n').append(sections.get(k).body());
                }
                int level = Math.max(1, first.level() - 1);
                emit(level, parentPath, "", body.toString(), result);
            }
            i = j;
        }
    }

    private void emit(int level, String path, String headingLine, String body, List<Document> result) {
        String content = headingLine.isEmpty() ? body : headingLine + "\n" + body;
        if (content.isBlank()) {
            return;
        }
        if (isOversized(content)) {
            List<Document> parts = fallbackSplitter.apply(List.of(new Document(content)));
            for (int p = 0; p < parts.size(); p++) {
                result.add(new Document(parts.get(p).getText(), sectionMetadata(level, path, p + 1)));
            }
        } else {
            result.add(new Document(content, sectionMetadata(level, path, null)));
        }
    }

    private Map<String, Object> sectionMetadata(int level, String path, Integer part) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ChunkMetadataKeys.CHUNK_TYPE, ChunkMetadataKeys.TYPE_SECTION);
        if (level > 0) {
            metadata.put(ChunkMetadataKeys.HEADING_LEVEL, level);
        }
        if (path != null && !path.isBlank()) {
            metadata.put(ChunkMetadataKeys.SECTION_PATH, path);
        }
        if (part != null) {
            metadata.put(ChunkMetadataKeys.PART, part);
        }
        return metadata;
    }

    private String parentPathOf(Section section) {
        int cut = section.path().lastIndexOf(PATH_SEPARATOR);
        return cut < 0 ? "" : section.path().substring(0, cut);
    }

    /**
     * 单节超过约两倍 token 预算对应的字符数时回退到通用切分（中文约 1 字符 ≈ 1 token）
     */
    private boolean isOversized(String content) {
        return content.length() > properties.getChunkSizeTokens() * 2;
    }
}
