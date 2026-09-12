package interview.guide.modules.learning.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.function.Consumer;

/**
 * 工具调用步骤装饰器：在真实工具执行前后向 SSE 通道发出步骤事件，
 * 让前端可以看到 Agent "正在做什么"（ReAct 过程可视化）
 */
@Slf4j
@RequiredArgsConstructor
public class LearningAgentToolCallback implements ToolCallback {

    private static final ObjectMapper RESULT_MAPPER = new ObjectMapper();

    private final ToolCallback delegate;
    private final Consumer<AgentStep> stepConsumer;

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        String name = delegate.getToolDefinition().name();
        stepConsumer.accept(new AgentStep(name, "start", LearningAgentTools.describeArgs(name, toolInput), null));
        try {
            String result = delegate.call(toolInput);
            String readable = unwrapJsonString(result);
            stepConsumer.accept(new AgentStep(name, "end", endSummary(name, readable), endDetail(readable)));
            return result;
        } catch (RuntimeException e) {
            log.warn("[LearningAgent] 工具执行失败: tool={}, error={}", name, e.getMessage());
            stepConsumer.accept(new AgentStep(name, "error", "执行失败: " + abbreviate(e.getMessage(), 60), null));
            throw e;
        }
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return call(toolInput);
    }

    /**
     * 工具返回的 String 会被序列化成 JSON 字符串字面量（外层引号 + \n 转义），
     * 展示前还原成纯文本，免得前端看到 {@code \"} 与 {@code \n}
     */
    private String unwrapJsonString(String result) {
        if (result == null) {
            return "";
        }
        String trimmed = result.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            try {
                return RESULT_MAPPER.readValue(trimmed, String.class);
            } catch (JsonProcessingException e) {
                log.debug("[LearningAgent] 工具结果非 JSON 字符串，按原文展示: {}", e.getMessage());
            }
        }
        return result;
    }

    private String endSummary(String name, String result) {
        return switch (name) {
            case "searchKnowledgeBase" -> "检索完成";
            case "getLearnerProfile" -> "档案已加载";
            case "listLearnedTopics" -> "台账已加载";
            case "loadSkillBaseline" -> "基线已加载";
            case "upsertLearningRecord", "upsertLearningPlan", "askLearner", "updateLearnerProfile" ->
                abbreviate(result, 40);
            default -> "执行完成";
        };
    }

    /** 工具返回原文：保留换行（前端展开后用 pre 展示），超长截断 */
    private String endDetail(String result) {
        if (result == null) {
            return "";
        }
        String trimmed = result.trim();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500) + "…";
    }

    /** 单行摘要：折叠空白，避免摘要里出现换行 */
    private String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }
}
