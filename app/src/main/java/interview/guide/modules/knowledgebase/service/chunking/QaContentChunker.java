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

/**
 * 题库按题分块（纯结构化识别，不调用 LLM）
 * 按题号行把"题干 + 选项 + 答案 + 解析"保持在同一个 chunk，
 * 检测基于内容而不是文件类型，因此 PDF/Word 提取出的题库同样适用
 */
@Slf4j
@Component
@Order(0)
public class QaContentChunker implements ContentChunker {

    /** 题号行：1. / 1、 / 1． / 1) / 1），编号后不允许紧跟数字（排除 "1.5万" 这类小数） */
    private static final Pattern NUMBERED_QUESTION =
        Pattern.compile("^\\s*(\\d{1,3})\\s*[.、．)）]\\s*(?!\\d)\\S");
    /** 题号行：第N题 / 第N问 */
    private static final Pattern DI_QUESTION = Pattern.compile("^\\s*第\\s*(\\d{1,3})\\s*[题问]");
    /** 题号行：Q1、 / Q1. / Q1： / Q:（无编号时按 -1 处理） */
    private static final Pattern Q_QUESTION = Pattern.compile("^\\s*Q(\\d{0,3})\\s*[.、．:：]");
    /** 答案标记 */
    private static final Pattern ANSWER_MARKER =
        Pattern.compile("(答案|答[:：]|answer\\s*[:：])", Pattern.CASE_INSENSITIVE);

    private final KnowledgeBaseChunkingProperties properties;
    private final TokenTextSplitter fallbackSplitter;

    public QaContentChunker(KnowledgeBaseChunkingProperties properties) {
        this.properties = properties;
        this.fallbackSplitter = TokenTextSplitter.builder()
            .withChunkSize(properties.getChunkSizeTokens())
            .build();
    }

    @Override
    public boolean supports(ParsedDocument parsed) {
        if (!properties.isQaEnabled() || parsed.isBlank()) {
            return false;
        }
        QuestionScan scan = scanQuestions(parsed.content());
        if (scan.questionCount() < properties.getQaMinQuestionMarkers()) {
            return false;
        }
        // 有答案标记按题库处理；纯编号列表容易误判，需要更高的题号密度
        return scan.answerCount() > 0
            || scan.questionCount() >= properties.getQaMinMarkersWithoutAnswer();
    }

    @Override
    public List<Document> chunk(ParsedDocument parsed) {
        List<Block> blocks = splitBlocks(parsed.content());
        return mergeAndEmit(blocks);
    }

    private record QuestionScan(int questionCount, int answerCount) {
    }

    private record Block(String content, Integer questionNo, boolean isQa) {
        static Block preface(String content) {
            return new Block(content, null, false);
        }
    }

    private QuestionScan scanQuestions(String content) {
        int questionCount = 0;
        int answerCount = 0;
        int lastQaNo = -1;
        for (String line : content.split("\\n")) {
            Integer no = matchQuestionNo(line);
            if (no != null && isNewQuestion(no, lastQaNo)) {
                if (no > 0) {
                    lastQaNo = no;
                }
                questionCount++;
            }
            if (ANSWER_MARKER.matcher(line).find()) {
                answerCount++;
            }
        }
        return new QuestionScan(questionCount, answerCount);
    }

    private List<Block> splitBlocks(String content) {
        List<Block> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        Integer currentNo = null;
        int lastQaNo = -1;
        boolean hasQa = false;

        for (String line : content.split("\\n", -1)) {
            Integer no = matchQuestionNo(line);
            if (no != null && isNewQuestion(no, lastQaNo)) {
                if (no > 0) {
                    lastQaNo = no;
                }
                if (!hasQa) {
                    // 第一个题目之前的内容作为导语
                    if (!current.toString().isBlank()) {
                        blocks.add(Block.preface(current.toString()));
                    }
                    hasQa = true;
                } else {
                    blocks.add(new Block(current.toString(), currentNo, true));
                }
                current = new StringBuilder();
                currentNo = no > 0 ? no : null;
            }
            current.append(line).append('\n');
        }

        if (!current.toString().isBlank()) {
            blocks.add(hasQa ? new Block(current.toString(), currentNo, true) : Block.preface(current.toString()));
        }
        return blocks;
    }

    /**
     * 相邻过小的 QA 块合并，导语块不参与合并
     */
    private List<Document> mergeAndEmit(List<Block> blocks) {
        List<Document> result = new ArrayList<>();
        int minChars = properties.getMinChunkChars();
        int i = 0;
        while (i < blocks.size()) {
            Block first = blocks.get(i);
            if (!first.isQa()) {
                emitPreface(first.content(), result);
                i++;
                continue;
            }
            StringBuilder merged = new StringBuilder(first.content());
            int j = i + 1;
            while (j < blocks.size() && blocks.get(j).isQa() && merged.length() < minChars) {
                merged.append(blocks.get(j).content());
                j++;
            }
            emitQa(merged.toString(), first.questionNo(), result);
            i = j;
        }
        return result;
    }

    private void emitQa(String content, Integer questionNo, List<Document> result) {
        if (content.isBlank()) {
            return;
        }
        if (isOversized(content)) {
            List<Document> parts = fallbackSplitter.apply(List.of(new Document(content)));
            for (int p = 0; p < parts.size(); p++) {
                Map<String, Object> metadata = qaMetadata(questionNo);
                metadata.put(ChunkMetadataKeys.PART, p + 1);
                result.add(new Document(parts.get(p).getText(), metadata));
            }
        } else {
            result.add(new Document(content, qaMetadata(questionNo)));
        }
    }

    private void emitPreface(String content, List<Document> result) {
        if (content.isBlank()) {
            return;
        }
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ChunkMetadataKeys.CHUNK_TYPE, ChunkMetadataKeys.TYPE_PREFACE);
        result.add(new Document(content, metadata));
    }

    private Map<String, Object> qaMetadata(Integer questionNo) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ChunkMetadataKeys.CHUNK_TYPE, ChunkMetadataKeys.TYPE_QA);
        if (questionNo != null) {
            metadata.put(ChunkMetadataKeys.QUESTION_NO, questionNo);
        }
        return metadata;
    }

    /**
     * 单块超过约两倍 token 预算对应的字符数时回退到通用切分（中文约 1 字符 ≈ 1 token）
     */
    private boolean isOversized(String content) {
        return content.length() > properties.getChunkSizeTokens() * 2;
    }

    /**
     * 判断一行是否为题目起始行。
     * 返回题号；无法识别编号的 Q: 行返回 -1；非题目行返回 null。
     */
    private Integer matchQuestionNo(String line) {
        Matcher m = NUMBERED_QUESTION.matcher(line);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        m = DI_QUESTION.matcher(line);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        m = Q_QUESTION.matcher(line);
        if (m.find()) {
            String digits = m.group(1);
            return digits.isEmpty() ? -1 : Integer.parseInt(digits);
        }
        return null;
    }

    /**
     * 编号非递减校验：题号应递增，出现 "1" 视为新的一节重新编号。
     * 用于避免题干解析、小节列表里的子编号被误当成新题目
     */
    private boolean isNewQuestion(int no, int lastQaNo) {
        if (no == -1) {
            return true;
        }
        if (no > lastQaNo) {
            return true;
        }
        return no == 1 && lastQaNo >= 3;
    }
}
