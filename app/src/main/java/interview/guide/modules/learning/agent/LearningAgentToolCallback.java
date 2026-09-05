package interview.guide.modules.learning.agent;

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

    private final ToolCallback delegate;
    private final Consumer<AgentStep> stepConsumer;

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        String name = delegate.getToolDefinition().name();
        stepConsumer.accept(new AgentStep(name, "start", LearningAgentTools.describeArgs(name, toolInput)));
        try {
            String result = delegate.call(toolInput);
            stepConsumer.accept(new AgentStep(name, "end", endSummary(name, result)));
            return result;
        } catch (RuntimeException e) {
            log.warn("[LearningAgent] 工具执行失败: tool={}, error={}", name, e.getMessage());
            stepConsumer.accept(new AgentStep(name, "error", "执行失败: " + abbreviate(e.getMessage(), 60)));
            throw e;
        }
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return call(toolInput);
    }

    private String endSummary(String name, String result) {
        return switch (name) {
            case "searchKnowledgeBase" -> "检索完成";
            case "getLearnerProfile" -> "档案已加载";
            case "listLearnedTopics" -> "台账已加载";
            case "upsertLearningRecord" -> abbreviate(result, 40);
            default -> "执行完成";
        };
    }

    private String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }
}
