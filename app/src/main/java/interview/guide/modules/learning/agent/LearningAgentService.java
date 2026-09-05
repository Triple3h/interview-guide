package interview.guide.modules.learning.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import interview.guide.modules.knowledgebase.service.RagChatSessionService;
import interview.guide.modules.learning.model.LearningRecordEntity;
import interview.guide.modules.learning.service.LearningRecordService;
import interview.guide.modules.user.model.UserEntity;
import interview.guide.modules.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * 学习帮手 Agent 编排
 * <p>
 * 手动 ReAct 循环：工具轮使用非流式调用（完整 JSON 响应里工具名一定是完整的），由
 * {@link ToolCallingManager} 执行工具并推进对话历史；部分模型（如 deepseek）流式输出工具
 * 调用时会把名字拆到多个分片，Spring AI 聚合层拼不上会抛 "toolName cannot be null or
 * empty"，因此不走 ToolCallingAdvisor。模型思维链（reasoning_content）按轮作为
 * reasoning 事件、最终答案按分片作为 delta 事件流式输出；工具执行过程通过
 * {@link LearningAgentToolCallback} 实时发步骤事件。
 */
@Slf4j
@Service
public class LearningAgentService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy年M月d日");

    /**
     * 最终答案流式输出的分片大小与节奏（分片数过大时自动放大分片，控制总时长）
     */
    private static final int DELTA_CHUNK_SIZE = 24;
    private static final int MAX_DELTA_CHUNKS = 150;
    private static final long DELTA_INTERVAL_MS = 15;

    private final LlmProviderRegistry llmProviderRegistry;
    private final ToolCallingManager toolCallingManager;
    private final RagChatSessionService sessionService;
    private final LearningRecordService recordService;
    private final UserService userService;
    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final LearningAgentProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 静态指令模板（无变量渲染；学员档案与台账由 Java 拼接追加，避免用户内容干扰模板引擎）
     */
    private final String staticSystemPrompt;

    public LearningAgentService(LlmProviderRegistry llmProviderRegistry,
                                ToolCallingManager toolCallingManager,
                                RagChatSessionService sessionService,
                                LearningRecordService recordService,
                                UserService userService,
                                KnowledgeBaseVectorService vectorService,
                                KnowledgeBaseRepository knowledgeBaseRepository,
                                LearningAgentProperties properties,
                                ObjectMapper objectMapper,
                                ResourceLoader resourceLoader) throws IOException {
        this.llmProviderRegistry = llmProviderRegistry;
        this.toolCallingManager = toolCallingManager;
        this.sessionService = sessionService;
        this.recordService = recordService;
        this.userService = userService;
        this.vectorService = vectorService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.staticSystemPrompt = resourceLoader
            .getResource(properties.getSystemPromptPath())
            .getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * 流式回答结果：events 为 delta/step 混合流；content/stepsJson 供完成后落库
     */
    public record AgentStreamResult(
        Flux<AgentEvent> events,
        Supplier<String> content,
        Supplier<String> stepsJson
    ) {}

    public AgentStreamResult chatStream(Long sessionId, Long userId, String question) {
        UserEntity learner = userService.getEntity(userId);
        List<Long> preferredKbIds = sessionService.getOwnedSessionKbIds(sessionId, userId);
        List<Message> history = sessionService.getHistoryMessages(sessionId);

        String systemPrompt = buildSystemPrompt(learner, userId);
        log.info("学习帮手开始回答: sessionId={}, userId={}, historySize={}, preferredKbIds={}",
            sessionId, userId, history.size(), preferredKbIds);

        // 实时事件通道：工具步骤与工具轮间的"思考"文本都从这里汇入主流
        List<AgentStep> steps = Collections.synchronizedList(new ArrayList<>());
        Sinks.Many<AgentEvent> liveSink = Sinks.many().unicast().onBackpressureBuffer();

        ToolCallback[] toolCallbacks = buildToolCallbacks(userId, sessionId, preferredKbIds, learner, steps, liveSink);

        StringBuilder content = new StringBuilder();

        // 工具轮是阻塞 HTTP 调用，放到 boundedElastic 执行；步骤/思考文本经 liveSink 汇入主流
        Flux<AgentEvent> agentWork = Flux.defer(() -> emitFinalAnswer(
                runAgentLoop(systemPrompt, history, question, toolCallbacks, liveSink)))
            .subscribeOn(Schedulers.boundedElastic())
            .doFinally(signal -> liveSink.tryEmitComplete());

        Flux<AgentEvent> events = Flux.merge(liveSink.asFlux(), agentWork)
            .doOnNext(event -> {
                if (AgentEvent.TYPE_DELTA.equals(event.type())) {
                    content.append(event.text());
                }
            });

        return new AgentStreamResult(
            events,
            content::toString,
            () -> serializeSteps(steps)
        );
    }

    /**
     * 手动 ReAct 循环：每轮非流式调用模型，模型请求工具则执行后带历史进下一轮，
     * 直到模型直接给出最终答案；工具轮耗尽或执行失败时降级为无工具收尾。
     */
    private String runAgentLoop(String systemPrompt, List<Message> history, String question,
                                ToolCallback[] toolCallbacks, Sinks.Many<AgentEvent> liveSink) {
        ChatClient loopClient = llmProviderRegistry.getAgentLoopChatClient(null);
        // 工具定义经由 runtime options 注入请求；镜像 Prompt 复用同一份 options 供 ToolCallingManager 解析回调
        ToolCallingChatOptions.Builder<?> toolOptionsBuilder = ToolCallingChatOptions.builder()
            .toolCallbacks(toolCallbacks);
        ToolCallingChatOptions toolOptions = toolOptionsBuilder.build();

        List<Message> conversation = new ArrayList<>();
        conversation.add(new SystemMessage(systemPrompt));
        conversation.addAll(history);
        conversation.add(new UserMessage(question));

        for (int round = 1; round <= properties.getMaxRounds(); round++) {
            ChatResponse response = callModel(loopClient, conversation, toolOptionsBuilder);
            if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
                log.warn("[LearningAgent] 第 {} 轮模型返回为空，转入无工具收尾", round);
                break;
            }
            AssistantMessage output = response.getResult().getOutput();
            String reasoning = extractReasoning(output);
            if (!output.hasToolCalls()) {
                emitReasoning(reasoning, liveSink);
                if (output.getText() != null && !output.getText().isBlank()) {
                    return output.getText();
                }
                log.warn("[LearningAgent] 第 {} 轮模型输出为空文本，转入无工具收尾", round);
                break;
            }
            log.info("[LearningAgent] 第 {} 轮调用 {} 个工具, 过渡正文 {} 字, 思维链 {} 字", round,
                output.getToolCalls().size(),
                output.getText() == null ? 0 : output.getText().length(),
                reasoning.length());
            // 思维链与过渡正文先推给前端，再执行工具
            emitReasoning(reasoning, liveSink);
            emitInterimText(output.getText(), liveSink);
            if (!executeToolRound(conversation, output, response, toolOptions)) {
                break;
            }
        }

        return forcedFinalAnswer(loopClient, conversation);
    }

    /**
     * 调用模型一轮；toolOptionsBuilder 为 null 时表示无工具收尾轮。
     * 消息列表自带头部 SystemMessage，与传给 ToolCallingManager 的镜像 Prompt 保持一致。
     * 通过 advisor 参数禁用 ChatClient 自动注册的工具循环 Advisor（Spring AI 2.0 会在
     * advisor 链内自动注册并接管整个工具循环），轮次编排由本服务手动控制。
     */
    private ChatResponse callModel(ChatClient loopClient, List<Message> conversation,
                                   ToolCallingChatOptions.Builder<?> toolOptionsBuilder) {
        ChatClient.ChatClientRequestSpec spec = loopClient.prompt()
            .messages(conversation)
            .advisors(a -> a.param(ChatClientAttributes.TOOL_CALLING_ADVISOR_AUTO_REGISTER.getKey(), Boolean.FALSE));
        if (toolOptionsBuilder != null) {
            spec = spec.options(toolOptionsBuilder);
        }
        return spec.call().chatResponse();
    }

    /**
     * 执行一轮工具调用并推进对话历史。
     * 这里传给 ToolCallingManager 的 Prompt 必须与模型实际收到的消息序列一致
     * （它会以此为前缀拼装下一轮的 conversationHistory）。
     * 执行失败时补一条错误 ToolResponseMessage 保持 OpenAI 消息合法性（assistant 的
     * tool_calls 后必须跟 tool 消息），返回 false 触发无工具收尾。
     */
    private boolean executeToolRound(List<Message> conversation, AssistantMessage assistant,
                                     ChatResponse response, ToolCallingChatOptions toolOptions) {
        try {
            ToolExecutionResult result = toolCallingManager.executeToolCalls(
                new Prompt(List.copyOf(conversation), toolOptions), response);
            conversation.clear();
            conversation.addAll(result.conversationHistory());
            return true;
        } catch (Exception e) {
            log.warn("[LearningAgent] 工具执行失败，降级为无工具收尾: {}", e.getMessage());
            List<ToolResponseMessage.ToolResponse> errorResponses = assistant.getToolCalls().stream()
                .map(call -> new ToolResponseMessage.ToolResponse(
                    call.id(), call.name(),
                    "工具执行失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage())))
                .toList();
            conversation.add(assistant);
            conversation.add(ToolResponseMessage.builder().responses(errorResponses).build());
            return false;
        }
    }

    /**
     * 工具轮耗尽或失败后的兜底：摘掉工具，让模型基于已有上下文直接收尾
     */
    private String forcedFinalAnswer(ChatClient loopClient, List<Message> conversation) {
        List<Message> closing = new ArrayList<>(conversation);
        closing.add(new UserMessage(
            "工具调用遇到问题，无法继续。请基于以上对话中已有的信息直接给出最终回答，不要提及工具调用失败的技术细节。"));
        ChatResponse response = callModel(loopClient, closing, null);
        String text = response != null && response.getResult() != null && response.getResult().getOutput() != null
            ? response.getResult().getOutput().getText()
            : null;
        if (text == null || text.isBlank()) {
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "模型未返回有效回答");
        }
        return text;
    }

    /**
     * 取思维链原文：Spring AI 的 OpenAI 模块把响应 message 级的 reasoning_content
     * （deepseek 等思考模型的 CoT 扩展字段，兼容键名 reasoning）放进
     * AssistantMessage metadata 的 reasoningContent 键
     */
    private String extractReasoning(AssistantMessage output) {
        Object reasoning = output.getMetadata() == null ? null : output.getMetadata().get("reasoningContent");
        return reasoning instanceof String text ? text : "";
    }

    /**
     * 思维链按整轮推给前端（reasoning 事件），不做分片延迟；未开启思考时为空串直接跳过
     */
    private void emitReasoning(String reasoning, Sinks.Many<AgentEvent> liveSink) {
        if (reasoning != null && !reasoning.isBlank()) {
            liveSink.tryEmitNext(AgentEvent.reasoning(reasoning));
        }
    }

    /**
     * 工具轮之间模型的过渡正文（思维链单独走 reasoning 事件后通常为空，保留兼容）
     */
    private void emitInterimText(String text, Sinks.Many<AgentEvent> liveSink) {
        if (text != null && !text.isBlank()) {
            liveSink.tryEmitNext(AgentEvent.delta(text));
        }
    }

    /**
     * 最终答案分片流式输出：保留打字机效果；答案很长时自动放大分片，控制总时长
     */
    private Flux<AgentEvent> emitFinalAnswer(String finalText) {
        return Flux.fromIterable(splitChunks(finalText))
            .map(AgentEvent::delta)
            .delayElements(Duration.ofMillis(DELTA_INTERVAL_MS));
    }

    private List<String> splitChunks(String text) {
        int chunkSize = Math.max(DELTA_CHUNK_SIZE, text.length() / MAX_DELTA_CHUNKS);
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            // 不把增补字符对（emoji 等）劈进两个分片
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
                end++;
            }
            chunks.add(text.substring(start, end));
            start = end;
        }
        return chunks;
    }

    private ToolCallback[] buildToolCallbacks(Long userId, Long sessionId, List<Long> preferredKbIds,
                                              UserEntity learner, List<AgentStep> steps,
                                              Sinks.Many<AgentEvent> liveSink) {
        LearningAgentTools tools = new LearningAgentTools(
            userId, sessionId, preferredKbIds, learner,
            vectorService, knowledgeBaseRepository, recordService, properties);

        ToolCallback[] rawCallbacks = MethodToolCallbackProvider.builder()
            .toolObjects(tools)
            .build()
            .getToolCallbacks();

        List<ToolCallback> decorated = new ArrayList<>();
        for (ToolCallback callback : rawCallbacks) {
            decorated.add(new LearningAgentToolCallback(callback, step -> {
                steps.add(step);
                liveSink.tryEmitNext(AgentEvent.step(step.tool(), step.phase(), step.summary()));
            }));
        }
        return decorated.toArray(ToolCallback[]::new);
    }

    private String buildSystemPrompt(UserEntity learner, Long userId) {
        List<LearningRecordEntity> topics = recordService.recentTopics(userId, properties.getPromptTopicLimit());

        StringBuilder sb = new StringBuilder(staticSystemPrompt);
        sb.append("\n\n# 学员档案\n");
        sb.append("- 昵称: ").append(learner.getNickname()).append('\n');
        sb.append("- 职业: ").append(orDefault(learner.getOccupation())).append('\n');
        sb.append("- 学习方向: ").append(orDefault(learner.getLearningDirection())).append('\n');
        sb.append("- 当前水平: ").append(orDefault(learner.getCurrentLevel())).append('\n');
        sb.append("- 学习目标: ").append(orDefault(learner.getLearningGoal())).append('\n');
        sb.append("- 今天日期: ").append(LocalDate.now().format(DATE_FORMATTER)).append('\n');
        sb.append("\n# 学习台账（最近 ").append(topics.size()).append(" 条）\n");
        sb.append(renderTopics(topics));
        return sb.toString();
    }

    private String renderTopics(List<LearningRecordEntity> topics) {
        if (topics.isEmpty()) {
            return "（台账暂无记录，学员可能刚刚开始学习）";
        }
        StringBuilder sb = new StringBuilder();
        for (LearningRecordEntity topic : topics) {
            sb.append("- ").append(topic.getTopic())
                .append("（").append(topic.getMastery().getLabel()).append("）");
            if (topic.getSummary() != null && !topic.getSummary().isBlank()) {
                sb.append("：").append(abbreviate(topic.getSummary(), 60));
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private String serializeSteps(List<AgentStep> steps) {
        try {
            return objectMapper.writeValueAsString(steps);
        } catch (JsonProcessingException e) {
            log.warn("序列化 Agent 步骤失败: {}", e.getMessage());
            return null;
        }
    }

    private String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private String orDefault(String value) {
        return value == null || value.isBlank() ? "未填写" : value;
    }
}
