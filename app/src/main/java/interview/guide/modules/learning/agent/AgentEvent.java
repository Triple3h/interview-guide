package interview.guide.modules.learning.agent;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 学习帮手 SSE 事件
 * type=delta: 回答文本分片；type=reasoning: 模型思维链；type=step: 工具调用步骤；
 * type=title: 自动生成的会话标题（首轮回答结束后）；type=error: 错误（前端统一走 onError）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentEvent(
    String type,
    String text,
    String tool,
    String phase,
    String summary,
    String message
) {

    public static final String TYPE_DELTA = "delta";
    public static final String TYPE_REASONING = "reasoning";
    public static final String TYPE_STEP = "step";
    public static final String TYPE_TITLE = "title";
    public static final String TYPE_ERROR = "error";

    public static AgentEvent delta(String text) {
        return new AgentEvent(TYPE_DELTA, text, null, null, null, null);
    }

    public static AgentEvent reasoning(String text) {
        return new AgentEvent(TYPE_REASONING, text, null, null, null, null);
    }

    public static AgentEvent step(String tool, String phase, String summary) {
        return new AgentEvent(TYPE_STEP, null, tool, phase, summary, null);
    }

    public static AgentEvent title(String text) {
        return new AgentEvent(TYPE_TITLE, text, null, null, null, null);
    }

    public static AgentEvent error(String message) {
        return new AgentEvent(TYPE_ERROR, null, null, null, null, message);
    }
}
