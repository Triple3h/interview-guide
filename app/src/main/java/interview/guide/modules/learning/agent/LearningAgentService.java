package interview.guide.modules.learning.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import interview.guide.modules.knowledgebase.service.RagChatSessionService;
import interview.guide.modules.learning.model.LearningPlanItemEntity;
import interview.guide.modules.learning.model.LearningRecordEntity;
import interview.guide.modules.learning.service.LearningPlanService;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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
     * 补充学员档案的工具名：调用成功后重建 system prompt，避免后续轮次仍按旧档案判断缺失
     */
    private static final String UPDATE_PROFILE_TOOL = "updateLearnerProfile";

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
    private final LearningPlanService planService;
    private final UserService userService;
    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final InterviewSkillService skillService;
    private final LearningAskRegistry askRegistry;
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
                                LearningPlanService planService,
                                UserService userService,
                                KnowledgeBaseVectorService vectorService,
                                KnowledgeBaseRepository knowledgeBaseRepository,
                                InterviewSkillService skillService,
                                LearningAskRegistry askRegistry,
                                LearningAgentProperties properties,
                                ObjectMapper objectMapper,
                                ResourceLoader resourceLoader) throws IOException {
        this.llmProviderRegistry = llmProviderRegistry;
        this.toolCallingManager = toolCallingManager;
        this.sessionService = sessionService;
        this.recordService = recordService;
        this.planService = planService;
        this.userService = userService;
        this.vectorService = vectorService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.skillService = skillService;
        this.askRegistry = askRegistry;
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

        log.info("学习帮手开始回答: sessionId={}, userId={}, historySize={}, preferredKbIds={}",
            sessionId, userId, history.size(), preferredKbIds);

        // 实时事件通道：工具步骤与工具轮间的"思考"文本都从这里汇入主流
        List<AgentStep> steps = Collections.synchronizedList(new ArrayList<>());
        Sinks.Many<AgentEvent> liveSink = Sinks.many().unicast().onBackpressureBuffer();
        // 档案在本轮被补充后置位，下一轮工具调用前重建 system prompt（learner 快照由工具同步刷新）
        AtomicBoolean profileUpdated = new AtomicBoolean(false);

        ToolCallback[] toolCallbacks = buildToolCallbacks(userId, sessionId, preferredKbIds, learner, steps,
            liveSink, profileUpdated);

        StringBuilder content = new StringBuilder();

        // 工具轮是阻塞 HTTP 调用，放到 boundedElastic 执行；步骤/思考文本经 liveSink 汇入主流
        Flux<AgentEvent> agentWork = Flux.defer(() -> emitFinalAnswer(
                runAgentLoop(() -> buildSystemPrompt(learner, userId), history, question, toolCallbacks,
                    liveSink, profileUpdated)))
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
    private String runAgentLoop(Supplier<String> systemPromptSupplier, List<Message> history, String question,
                                ToolCallback[] toolCallbacks, Sinks.Many<AgentEvent> liveSink,
                                AtomicBoolean profileUpdated) {
        ChatClient loopClient = llmProviderRegistry.getAgentLoopChatClient(null);
        // 工具定义经由 runtime options 注入请求；镜像 Prompt 复用同一份 options 供 ToolCallingManager 解析回调
        ToolCallingChatOptions.Builder<?> toolOptionsBuilder = ToolCallingChatOptions.builder()
            .toolCallbacks(toolCallbacks);
        ToolCallingChatOptions toolOptions = toolOptionsBuilder.build();

        List<Message> conversation = new ArrayList<>();
        conversation.add(new SystemMessage(systemPromptSupplier.get()));
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
            if (profileUpdated.getAndSet(false)) {
                // 档案刚被补充：重建 system prompt，让后续轮次按最新档案回答
                conversation.set(0, new SystemMessage(systemPromptSupplier.get()));
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
                                              Sinks.Many<AgentEvent> liveSink, AtomicBoolean profileUpdated) {
        LearningAgentTools tools = new LearningAgentTools(
            userId, sessionId, preferredKbIds, learner, userService,
            vectorService, knowledgeBaseRepository, recordService, properties, skillService,
            planService, askRegistry, liveSink::tryEmitNext);

        ToolCallback[] rawCallbacks = MethodToolCallbackProvider.builder()
            .toolObjects(tools)
            .build()
            .getToolCallbacks();

        List<ToolCallback> decorated = new ArrayList<>();
        for (ToolCallback callback : rawCallbacks) {
            decorated.add(new LearningAgentToolCallback(callback, step -> {
                steps.add(step);
                if (UPDATE_PROFILE_TOOL.equals(step.tool()) && "end".equals(step.phase())) {
                    profileUpdated.set(true);
                }
                liveSink.tryEmitNext(AgentEvent.step(step.tool(), step.phase(), step.summary(), step.detail()));
            }));
        }
        return decorated.toArray(ToolCallback[]::new);
    }

    private String buildSystemPrompt(UserEntity learner, Long userId) {
        List<LearningRecordEntity> topics = recordService.recentTopics(userId, properties.getPromptTopicLimit());
        List<LearningPlanItemEntity> planItems = planService.listEntities(userId);

        StringBuilder sb = new StringBuilder(staticSystemPrompt);
        sb.append("\n\n# 学员档案\n");
        sb.append("- 昵称: ").append(learner.getNickname()).append('\n');
        sb.append("- 职业: ").append(orDefault(learner.getOccupation())).append('\n');
        sb.append("- 学习方向: ").append(orDefault(learner.getLearningDirection())).append('\n');
        appendSkillCatalog(sb, learner.getLearningSkillId());
        sb.append("- 当前水平: ").append(orDefault(learner.getCurrentLevel())).append('\n');
        sb.append("- 学习目标: ").append(orDefault(learner.getLearningGoal())).append('\n');
        sb.append(renderMissingProfileFields(learner));
        sb.append("- 今天日期: ").append(LocalDate.now().format(DATE_FORMATTER)).append('\n');
        sb.append("\n# 学习计划（当前）\n");
        sb.append(renderPlanItems(planItems));
        sb.append("\n\n# 学习台账（最近 ").append(topics.size()).append(" 条）\n");
        sb.append(renderTopics(topics));
        return sb.toString();
    }

    /**
     * 档案缺失字段提示：缺失信息与当前话题相关时，Agent 可自然地向学员询问并用 updateLearnerProfile 记录
     */
    private String renderMissingProfileFields(UserEntity learner) {
        List<String> missing = new ArrayList<>();
        if (isBlank(learner.getOccupation())) {
            missing.add("职业");
        }
        if (isBlank(learner.getLearningDirection())) {
            missing.add("学习方向");
        }
        if (isBlank(learner.getCurrentLevel())) {
            missing.add("当前水平");
        }
        if (isBlank(learner.getLearningGoal())) {
            missing.add("学习目标");
        }
        if (missing.isEmpty()) {
            return "- 待补全字段: 无（资料已完整）\n";
        }
        return "- 待补全字段: " + String.join("、", missing)
            + "（与本次学习话题相关时才询问学员，学员告知后用 updateLearnerProfile 记录）\n";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 学习计划注入：Agent 每轮都知道计划进行到哪，讲解和规划要主动关联计划条目
     */
    private String renderPlanItems(List<LearningPlanItemEntity> planItems) {
        if (planItems.isEmpty()) {
            return "（暂无学习计划。学员想规划学习路径时，先结合台账和学习方向分类给出提案，"
                + "与学员商定后再用 upsertLearningPlan 固化）";
        }
        StringBuilder sb = new StringBuilder();
        for (LearningPlanItemEntity item : planItems) {
            sb.append("- [").append(item.getStatus().getLabel()).append("] ")
                .append(item.getTopic());
            if (item.getGoal() != null && !item.getGoal().isBlank()) {
                sb.append("：").append(abbreviate(item.getGoal(), 60));
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 学员选了预置学习方向时，列出该方向下配置了知识基线的分类，
     * 供 loadSkillBaseline 工具按 key 加载；skill 已失效（被移除/改名）时降级为不列出
     */
    private void appendSkillCatalog(StringBuilder sb, String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return;
        }
        try {
            InterviewSkillService.SkillDTO skill = skillService.getSkill(skillId);
            List<InterviewSkillService.SkillCategoryDTO> withBaseline = skill.categories().stream()
                .filter(c -> c.ref() != null && !c.ref().isBlank())
                .toList();
            if (withBaseline.isEmpty()) {
                return;
            }
            sb.append("- 学习方向分类: ").append(withBaseline.stream()
                    .map(c -> c.key() + "(" + c.label() + ")")
                    .collect(Collectors.joining("、")))
                .append("，可用 loadSkillBaseline 工具按 key 加载对应知识基线\n");
        } catch (BusinessException e) {
            log.warn("学员学习方向关联的 skill 不可用，跳过分类清单: skillId={}", skillId);
        }
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
