package interview.guide.modules.learning.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.service.RagChatSessionService;
import interview.guide.modules.knowledgebase.service.RagChatSessionService.CompletedTurn;
import interview.guide.modules.learning.agent.LearningAgentProperties;
import interview.guide.modules.learning.model.LearningMemoryDTO.ExtractResult;
import interview.guide.modules.learning.model.LearningMemoryDTO.MemoryOp;
import interview.guide.modules.learning.model.LearningMemoryEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 从已完成的学习对话抽取个人记忆。
 * LLM 调用不在事务内；落库交给 {@link LearningMemoryService#applyExtractedOperations}。
 */
@Slf4j
@Service
public class LearningMemoryExtractService {

    private final RagChatSessionService sessionService;
    private final LearningMemoryService memoryService;
    private final LlmProviderRegistry llmProviderRegistry;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final LearningAgentProperties properties;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final BeanOutputConverter<ExtractResult> outputConverter;

    public LearningMemoryExtractService(RagChatSessionService sessionService,
                                        LearningMemoryService memoryService,
                                        LlmProviderRegistry llmProviderRegistry,
                                        StructuredOutputInvoker structuredOutputInvoker,
                                        LearningAgentProperties properties,
                                        ResourceLoader resourceLoader) throws IOException {
        this.sessionService = sessionService;
        this.memoryService = memoryService;
        this.llmProviderRegistry = llmProviderRegistry;
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.properties = properties;
        this.systemPromptTemplate = new PromptTemplate(resourceLoader
            .getResource(properties.getExtractSystemPromptPath())
            .getContentAsString(StandardCharsets.UTF_8));
        this.userPromptTemplate = new PromptTemplate(resourceLoader
            .getResource(properties.getExtractUserPromptPath())
            .getContentAsString(StandardCharsets.UTF_8));
        this.outputConverter = new BeanOutputConverter<>(ExtractResult.class);
    }

    /**
     * 抽取一轮对话中的个人记忆。会话/消息已删除或未完成时直接返回。
     */
    public void extractFromCompletedTurn(Long userId, Long sessionId, Long messageId) {
        CompletedTurn turn = sessionService.findCompletedAssistantTurn(messageId, userId).orElse(null);
        if (turn == null) {
            log.info("跳过记忆抽取：回合不可用 sessionId={}, messageId={}, userId={}",
                sessionId, messageId, userId);
            return;
        }

        List<LearningMemoryEntity> existing =
            memoryService.recentMemories(userId, properties.getExtractMemoryContextLimit());
        ExtractResult result = invokeExtract(turn.question(), turn.answer(), existing);
        List<MemoryOp> operations = capOperations(result == null ? null : result.operations());
        memoryService.applyExtractedOperations(userId, turn.sessionId(), messageId, operations);
    }

    ExtractResult invokeExtract(String question, String answer, List<LearningMemoryEntity> existing) {
        String systemPrompt = systemPromptTemplate.render() + "\n\n" + outputConverter.getFormat();
        String userPrompt = userPromptTemplate.render(Map.of(
            "existingMemories", renderExisting(existing),
            "question", truncate(question, properties.getExtractQuestionChars()),
            "answer", truncate(answer, properties.getExtractAnswerChars())
        ));
        return structuredOutputInvoker.invoke(
            llmProviderRegistry.getChatClientOrDefault(null),
            systemPrompt,
            userPrompt,
            outputConverter,
            ErrorCode.AI_SERVICE_ERROR,
            "记忆抽取失败：",
            "学习记忆抽取",
            log);
    }

    private List<MemoryOp> capOperations(List<MemoryOp> operations) {
        if (operations == null || operations.isEmpty()) {
            return List.of();
        }
        int max = Math.max(0, properties.getExtractMaxOperations());
        List<MemoryOp> capped = new ArrayList<>();
        for (MemoryOp op : operations) {
            if (op == null) {
                continue;
            }
            capped.add(op);
            if (capped.size() >= max) {
                break;
            }
        }
        if (capped.size() < operations.size()) {
            log.warn("记忆抽取操作数超过上限，已截断: total={}, max={}", operations.size(), max);
        }
        return capped;
    }

    private String renderExisting(List<LearningMemoryEntity> existing) {
        if (existing == null || existing.isEmpty()) {
            return "（暂无）";
        }
        StringBuilder sb = new StringBuilder();
        for (LearningMemoryEntity memory : existing) {
            sb.append("[id=").append(memory.getId()).append("][")
                .append(memory.getKind().getLabel()).append("] ")
                .append(memory.getContent()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (max <= 0 || trimmed.length() <= max) {
            return trimmed;
        }
        return trimmed.substring(0, max) + "…";
    }
}
