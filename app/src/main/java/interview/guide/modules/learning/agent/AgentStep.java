package interview.guide.modules.learning.agent;

/**
 * Agent 工具调用步骤（持久化到消息的 toolStepsJson，供前端回放）
 */
public record AgentStep(
    String tool,
    String phase,  // start | end | error
    String summary
) {
}
