package interview.guide.modules.learning.agent;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 落库的 Agent 回答时间线块（与前端 MessageBlock 同构）：
 * kind=reasoning / text 时用 {@code text}，kind=tool 时用 {@code invocation}。
 * <p>
 * 序列化成 JSON 存进 {@code rag_chat_messages.timeline_json}，让历史会话也能按
 * 「思考 → 工具调用 → 再思考 → 正文」的原始顺序回放。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentTimelineBlock(String kind, String text, ToolInvocation invocation) {

    public static final String KIND_REASONING = "reasoning";
    public static final String KIND_TEXT = "text";
    public static final String KIND_TOOL = "tool";

    /** 工具调用块：入参摘要 + 状态 + 结果摘要与原文（与前端 ToolInvocation 同构） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolInvocation(String tool, String argsSummary, String status, String resultSummary, String detail) {
    }

    public static AgentTimelineBlock reasoning(String text) {
        return new AgentTimelineBlock(KIND_REASONING, text, null);
    }

    public static AgentTimelineBlock text(String text) {
        return new AgentTimelineBlock(KIND_TEXT, text, null);
    }

    public static AgentTimelineBlock tool(ToolInvocation invocation) {
        return new AgentTimelineBlock(KIND_TOOL, null, invocation);
    }
}
