package interview.guide.modules.learning.controller;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.common.web.CurrentUser;
import interview.guide.common.web.LoginUser;
import interview.guide.common.web.SseEventWriter;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionDTO;
import interview.guide.modules.knowledgebase.service.RagChatSessionService;
import interview.guide.modules.learning.agent.AgentEvent;
import interview.guide.modules.learning.agent.LearningAgentService;
import interview.guide.modules.learning.model.LearningAgentDTO.CreateLearningSessionRequest;
import interview.guide.modules.learning.model.LearningAgentDTO.LearningAgentChatRequest;
import interview.guide.modules.learning.service.SessionTitleService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

/**
 * 学习帮手 Agent 控制器
 * 会话创建走本控制器，会话列表/详情/删除复用 /api/rag-chat/sessions（按成员隔离）
 */
@Tag(name = "学习帮手", description = "ReAct 学习 Agent：自主检索知识库、维护学习台账")
@Slf4j
@RestController
@RequiredArgsConstructor
public class LearningAgentController {

    /**
     * 首轮结束后生成标题的兜底超时：LLM 卡住时不允许一直占着 SSE 连接
     */
    private static final Duration TITLE_EVENT_TIMEOUT = Duration.ofSeconds(10);

    private final RagChatSessionService sessionService;
    private final LearningAgentService agentService;
    private final SessionTitleService sessionTitleService;
    private final SseEventWriter sseEventWriter;

    /**
     * 创建学习会话
     */
    @PostMapping("/api/learning/sessions")
    public Result<SessionDTO> createSession(@Valid @RequestBody CreateLearningSessionRequest request,
                                            @LoginUser CurrentUser currentUser) {
        return Result.success(sessionService.createLearningSession(currentUser.id(), request.title()));
    }

    /**
     * 学习会话流式问答（SSE 类型化事件：delta/step/error）
     */
    @PostMapping(value = "/api/learning/sessions/{sessionId}/stream",
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Flux<ServerSentEvent<String>> chatStream(@PathVariable Long sessionId,
                                                    @Valid @RequestBody LearningAgentChatRequest request,
                                                    @LoginUser CurrentUser currentUser) {
        log.info("收到学习帮手流式请求: sessionId={}, userId={}, 线程: {} (虚拟线程: {})",
            sessionId, currentUser.id(), Thread.currentThread(), Thread.currentThread().isVirtual());

        // 1. 保存用户消息并创建 AI 消息占位（含归属校验）
        Long messageId = sessionService.prepareStreamMessage(sessionId, request.question(), currentUser.id());

        // 2. 构建 Agent 流（工具步骤 + 回答分片）
        LearningAgentService.AgentStreamResult stream = agentService.chatStream(
            sessionId, currentUser.id(), request.question());

        return stream.events()
            .onErrorResume(e -> {
                log.error("学习帮手流式回答失败: sessionId={}", sessionId, e);
                return Flux.just(AgentEvent.error(friendlyAgentError(e)));
            })
            .map(event -> sseEventWriter.typed(event.type(), event))
            .doOnComplete(() -> {
                // 3. 完成后落库（含工具步骤，供前端回放）
                String content = stream.content().get();
                if (content.isBlank()) {
                    content = "【错误】回答生成失败，请重试";
                }
                sessionService.completeStreamMessage(messageId, content, stream.stepsJson().get());
                log.info("学习帮手流式完成: sessionId={}, messageId={}, steps={}",
                    sessionId, messageId, stream.stepsJson().get());
            })
            .concatWith(Mono
                // 4. 首轮回答结束后自动生成会话标题（标题仍是默认占位时才生成），经 title 事件推给前端
                .defer(() -> Mono.justOrEmpty(sessionTitleService.autoRenameIfDefault(
                        sessionId, currentUser.id(), request.question(), stream.content().get())
                    .map(title -> sseEventWriter.typed(AgentEvent.TYPE_TITLE, AgentEvent.title(title)))))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(TITLE_EVENT_TIMEOUT)
                .onErrorResume(e -> {
                    log.warn("会话标题事件生成异常: sessionId={}", sessionId, e);
                    return Mono.empty();
                }));
    }

    /**
     * 把底层 AI 调用异常翻译成用户能采取行动的提示（拼接整条 cause 链再匹配）
     */
    private String friendlyAgentError(Throwable e) {
        StringBuilder chain = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            chain.append(t.getMessage() == null ? "" : t.getMessage()).append(' ');
        }
        String all = chain.toString().toLowerCase();

        if (all.contains("free quota") || all.contains("quota") || all.contains("permissiondenied")
            || all.contains("403") || all.contains("insufficient") || all.contains("arrearage")) {
            return "AI 额度已用完或无权访问该模型，请在设置中更换模型或为账号充值";
        }
        if (all.contains("timeout") || all.contains("timed out")) {
            return "AI 服务响应超时，请稍后重试";
        }
        if (all.contains("401") || all.contains("unauthorized") || all.contains("invalid api key")) {
            return "AI 服务密钥无效，请检查模型配置";
        }
        return "AI 服务暂时不可用，请稍后重试";
    }
}
