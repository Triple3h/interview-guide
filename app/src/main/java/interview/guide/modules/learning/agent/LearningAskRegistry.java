package interview.guide.modules.learning.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 学员提问应答注册表（每个会话同一时刻最多一个待回答提问）
 * askLearner 工具在 Agent 工具线程上阻塞等待 future，学员在应答端点提交后 complete 放行
 */
@Slf4j
@Component
public class LearningAskRegistry {

    /**
     * 待回答提问：future 完成值即学员回答文本
     */
    public record PendingAsk(Long sessionId, String question, List<String> options,
                             CompletableFuture<String> future) {}

    private final ConcurrentHashMap<Long, PendingAsk> pending = new ConcurrentHashMap<>();

    /**
     * 注册待回答提问；同会话已有未回答提问时，旧提问以"学员未回答"收场（新消息/新提问顶掉旧等待）
     */
    public CompletableFuture<String> register(Long sessionId, String question, List<String> options) {
        CompletableFuture<String> future = new CompletableFuture<>();
        PendingAsk previous = pending.put(sessionId, new PendingAsk(sessionId, question, options, future));
        if (previous != null && !previous.future().isDone()) {
            log.info("学习帮手提问被顶替: sessionId={}, question={}", sessionId, previous.question());
            previous.future().complete("（学员没有回答这个问题）");
        }
        return future;
    }

    /**
     * 学员应答；返回 false 表示当前没有待回答的提问
     */
    public boolean complete(Long sessionId, String answer) {
        PendingAsk ask = pending.get(sessionId);
        if (ask == null) {
            return false;
        }
        if (ask.future().complete(answer)) {
            pending.remove(sessionId, ask);
            return true;
        }
        return false;
    }

    /**
     * 等待超时后清理，仅当仍是本次注册的提问时才移除
     */
    public void evict(Long sessionId, CompletableFuture<String> future) {
        PendingAsk ask = pending.get(sessionId);
        if (ask != null && ask.future() == future) {
            pending.remove(sessionId, ask);
        }
    }
}
