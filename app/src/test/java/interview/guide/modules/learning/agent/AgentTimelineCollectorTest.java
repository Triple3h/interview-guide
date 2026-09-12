package interview.guide.modules.learning.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Agent 回答时间线收集器测试")
class AgentTimelineCollectorTest {

    private final AgentTimelineCollector collector = new AgentTimelineCollector();

    @Test
    @DisplayName("思考 / 工具 / 再思考 / 正文按事件顺序成块，同类分片合并")
    void blocksFollowEventOrder() {
        collector.accept(AgentEvent.reasoning("我先看"));
        collector.accept(AgentEvent.reasoning("一下台账。"));
        collector.accept(AgentEvent.step("listLearnedTopics", "start", "查看学习台账", null));
        collector.accept(AgentEvent.step("listLearnedTopics", "end", "台账已加载", "学习台账暂无知识点。"));
        collector.accept(AgentEvent.reasoning("台账是空的，"));
        collector.accept(AgentEvent.delta("好，"));
        collector.accept(AgentEvent.delta("我们从基础开始。"));

        List<AgentTimelineBlock> blocks = collector.blocks();

        assertThat(blocks).extracting(AgentTimelineBlock::kind)
            .containsExactly("reasoning", "tool", "reasoning", "text");
        assertThat(blocks.get(0).text()).isEqualTo("我先看一下台账。");
        assertThat(blocks.get(2).text()).isEqualTo("台账是空的，");
        assertThat(blocks.get(3).text()).isEqualTo("好，我们从基础开始。");
    }

    @Test
    @DisplayName("工具调用 start/end 配对：状态与结果回填到同一个块")
    void toolInvocationPairing() {
        collector.accept(AgentEvent.step("searchKnowledgeBase", "start", "检索知识库：关键词=Agent Loop", null));
        collector.accept(AgentEvent.step("searchKnowledgeBase", "end", "检索完成", "命中 3 条"));

        assertThat(collector.blocks()).hasSize(1);
        AgentTimelineBlock.ToolInvocation invocation = collector.blocks().get(0).invocation();
        assertThat(invocation.status()).isEqualTo("ok");
        assertThat(invocation.argsSummary()).isEqualTo("检索知识库：关键词=Agent Loop");
        assertThat(invocation.resultSummary()).isEqualTo("检索完成");
        assertThat(invocation.detail()).isEqualTo("命中 3 条");
    }

    @Test
    @DisplayName("工具执行失败标记为 error，且不影响后续块")
    void toolErrorStatus() {
        collector.accept(AgentEvent.step("upsertLearningRecord", "start", "记录知识点：ReAct", null));
        collector.accept(AgentEvent.step("upsertLearningRecord", "error", "执行失败: 参数非法", null));
        collector.accept(AgentEvent.delta("记录失败，稍后再试。"));

        List<AgentTimelineBlock> blocks = collector.blocks();
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).invocation().status()).isEqualTo("error");
        assertThat(blocks.get(0).invocation().resultSummary()).isEqualTo("执行失败: 参数非法");
        assertThat(blocks.get(1).kind()).isEqualTo("text");
    }

    @Test
    @DisplayName("空分片与 ask/title/error 事件不进入时间线")
    void ignoresEmptyAndNonTimelineEvents() {
        collector.accept(AgentEvent.reasoning(""));
        collector.accept(AgentEvent.delta(""));
        collector.accept(AgentEvent.ask("想先学哪块？", List.of("A", "B")));
        collector.accept(AgentEvent.title("学习计划"));
        collector.accept(AgentEvent.error("boom"));
        // 没有对应 start 的 end 事件同样忽略，不抛异常
        collector.accept(AgentEvent.step("askLearner", "end", "已收到回答", null));

        assertThat(collector.blocks()).isEmpty();
    }
}
