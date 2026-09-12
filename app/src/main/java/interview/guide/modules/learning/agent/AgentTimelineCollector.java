package interview.guide.modules.learning.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 按事件到达顺序把 SSE 事件流还原成回答时间线（思考 / 工具调用 / 正文）。
 * <p>
 * 与前端流式渲染同构：相邻的同类分片（思维链、正文）合并进同一块；工具调用 start 建块、
 * end/error 回填最近一个同名且仍在执行的块。结果序列化后存进
 * {@code rag_chat_messages.timeline_json}，供历史会话按原始顺序回放。
 */
class AgentTimelineCollector {

    private final List<AgentTimelineBlock> blocks = Collections.synchronizedList(new ArrayList<>());

    void accept(AgentEvent event) {
        switch (event.type()) {
            case AgentEvent.TYPE_REASONING ->
                mergeTextBlock(AgentTimelineBlock.KIND_REASONING, event.text());
            case AgentEvent.TYPE_DELTA -> mergeTextBlock(AgentTimelineBlock.KIND_TEXT, event.text());
            case AgentEvent.TYPE_STEP -> applyToolStep(event);
            default -> {
                // ask / title / error 不属于回答时间线
            }
        }
    }

    List<AgentTimelineBlock> blocks() {
        synchronized (blocks) {
            return List.copyOf(blocks);
        }
    }

    private void mergeTextBlock(String kind, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        synchronized (blocks) {
            int last = blocks.size() - 1;
            if (last >= 0 && kind.equals(blocks.get(last).kind())) {
                blocks.set(last, new AgentTimelineBlock(kind, blocks.get(last).text() + text, null));
            } else {
                blocks.add(new AgentTimelineBlock(kind, text, null));
            }
        }
    }

    private void applyToolStep(AgentEvent event) {
        synchronized (blocks) {
            if ("start".equals(event.phase())) {
                blocks.add(AgentTimelineBlock.tool(new AgentTimelineBlock.ToolInvocation(
                    event.tool(), event.summary(), "running", "", null)));
                return;
            }
            for (int i = blocks.size() - 1; i >= 0; i--) {
                AgentTimelineBlock block = blocks.get(i);
                if (block.invocation() == null || !event.tool().equals(block.invocation().tool())
                    || !"running".equals(block.invocation().status())) {
                    continue;
                }
                blocks.set(i, AgentTimelineBlock.tool(new AgentTimelineBlock.ToolInvocation(
                    block.invocation().tool(),
                    block.invocation().argsSummary(),
                    "error".equals(event.phase()) ? "error" : "ok",
                    event.summary(),
                    event.detail() != null ? event.detail() : block.invocation().detail())));
                return;
            }
        }
    }
}
