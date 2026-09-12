package interview.guide.modules.learning.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.OpenAiCompatibleStreamClient;
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
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * 手动 ReAct 循环：每轮由 {@link OpenAiCompatibleStreamClient} 直连供应商的 /chat/completions 读
 * SSE 原始分片——思维链（reasoning_content）与正文分片实时转成 reasoning / delta 事件，工具调用
 * 分片按 index 聚合后交给 {@link ToolCallingManager} 执行并推进对话历史。之所以不用 Spring AI 的
 * ChatClient.stream()：其流式聚合会丢掉 reasoning_content（实测恒为空串），且聚合深seek 的分片形态
 * 曾报 "toolName cannot be null or empty"。工具执行过程通过
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

    private final LlmProviderRegistry llmProviderRegistry;
    private final OpenAiCompatibleStreamClient streamClient;
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
                                OpenAiCompatibleStreamClient streamClient,
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
        this.streamClient = streamClient;
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
     * 流式回答结果：events 为 delta/step 混合流；content/stepsJson/timelineJson 供完成后落库
     */
    public record AgentStreamResult(
        Flux<AgentEvent> events,
        Supplier<String> content,
        Supplier<String> stepsJson,
        Supplier<String> timelineJson
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

        // 整轮循环都是阻塞式流式读取，放到 boundedElastic 执行；思维链/正文/步骤都经 liveSink 汇入主流
        Flux<AgentEvent> agentWork = Flux.defer(() -> {
                runAgentLoop(() -> buildSystemPrompt(learner, userId), history, question, toolCallbacks,
                    liveSink, profileUpdated);
                return Flux.<AgentEvent>empty();
            })
            .subscribeOn(Schedulers.boundedElastic())
            .doFinally(signal -> liveSink.tryEmitComplete());

        // 按事件到达顺序还原时间线（思考/工具/正文），随消息一起落库供历史回放
        AgentTimelineCollector timeline = new AgentTimelineCollector();
        Flux<AgentEvent> events = Flux.merge(liveSink.asFlux(), agentWork)
            .doOnNext(event -> {
                if (AgentEvent.TYPE_DELTA.equals(event.type())) {
                    content.append(event.text());
                }
                timeline.accept(event);
            });

        return new AgentStreamResult(
            events,
            content::toString,
            () -> serializeSteps(steps),
            () -> serializeTimeline(timeline.blocks())
        );
    }

    /**
     * 手动 ReAct 循环：每轮流式调用模型（思维链/正文分片实时推给前端），模型请求工具则用
     * {@link ToolCallingManager} 执行并推进对话历史，直到模型直接给出最终答案；
     * 工具轮耗尽或执行失败时降级为无工具收尾。
     */
    private String runAgentLoop(Supplier<String> systemPromptSupplier, List<Message> history, String question,
                                ToolCallback[] toolCallbacks, Sinks.Many<AgentEvent> liveSink,
                                AtomicBoolean profileUpdated) {
        OpenAiCompatibleStreamClient.Connection connection = llmProviderRegistry.getChatConnection(null);
        // 原生流式绕过了 ChatClient 的 SafeGuardAdvisor，这里做等价检查，命中则不请求模型
        if (llmProviderRegistry.isSafeguardTriggered(question)) {
            String fallback = llmProviderRegistry.safeguardFailureResponse();
            liveSink.tryEmitNext(AgentEvent.delta(fallback));
            return fallback;
        }
        // 工具定义经 runtime options 注入请求，同一份 options 供 ToolCallingManager 解析回调
        ToolCallingChatOptions toolOptions = ToolCallingChatOptions.builder()
            .toolCallbacks(toolCallbacks)
            .build();
        List<ToolDefinition> toolDefinitions = toolCallingManager.resolveToolDefinitions(toolOptions);

        List<Message> conversation = new ArrayList<>();
        conversation.add(new SystemMessage(systemPromptSupplier.get()));
        conversation.addAll(history);
        conversation.add(new UserMessage(question));

        // streamState[0]=本轮思维链是否已开始推；streamState[1]=之前是否推过（多轮之间空一行分隔）
        boolean[] streamState = {false, false};
        for (int round = 1; round <= properties.getMaxRounds(); round++) {
            streamState[0] = false;
            OpenAiCompatibleStreamClient.StreamedAssistant turn = streamClient.streamTurn(
                connection, conversation, toolDefinitions,
                delta -> emitStreamDelta(delta, liveSink, streamState));
            if (!turn.hasToolCalls()) {
                if (turn.text().isBlank()) {
                    log.warn("[LearningAgent] 第 {} 轮模型输出为空文本，转入无工具收尾", round);
                    break;
                }
                log.info("[LearningAgent] 第 {} 轮结束：正文 {} 字，思维链 {} 字", round,
                    turn.text().length(), turn.reasoningContent().length());
                return turn.text();
            }
            log.info("[LearningAgent] 第 {} 轮调用 {} 个工具，过渡正文 {} 字，思维链 {} 字", round,
                turn.toolCalls().size(), turn.text().length(), turn.reasoningContent().length());
            if (!executeToolRound(conversation, turn.toAssistantMessage(), turn.toChatResponse(), toolOptions)) {
                break;
            }
            if (profileUpdated.getAndSet(false)) {
                // 档案刚被补充：重建 system prompt，让后续轮次按最新档案回答
                conversation.set(0, new SystemMessage(systemPromptSupplier.get()));
            }
        }

        return forcedFinalAnswer(connection, conversation, liveSink);
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
     * 工具轮耗尽或失败后的兜底：摘掉工具，让模型基于已有上下文直接收尾（同样流式输出）
     */
    private String forcedFinalAnswer(OpenAiCompatibleStreamClient.Connection connection,
                                     List<Message> conversation, Sinks.Many<AgentEvent> liveSink) {
        List<Message> closing = new ArrayList<>(conversation);
        closing.add(new UserMessage(
            "工具调用遇到问题，无法继续。请基于以上对话中已有的信息直接给出最终回答，不要提及工具调用失败的技术细节。"));
        // 收尾轮之前已有思维链，需要空行分隔
        boolean[] streamState = {false, true};
        OpenAiCompatibleStreamClient.StreamedAssistant turn = streamClient.streamTurn(
            connection, closing, List.of(), delta -> emitStreamDelta(delta, liveSink, streamState));
        if (turn.text().isBlank()) {
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "模型未返回有效回答");
        }
        return turn.text();
    }

    /**
     * 把模型流式分片实时转成前端事件：思维链走 reasoning、正文走 delta，全部即收即发（真流式）。
     * streamState[0] 标记本轮思维链是否已开始推送；streamState[1] 标记之前是否推过，
     * 多轮之间补一个空行（前端按纯追加渲染）。
     */
    private void emitStreamDelta(OpenAiCompatibleStreamClient.StreamDelta delta,
                                 Sinks.Many<AgentEvent> liveSink, boolean[] streamState) {
        String reasoning = delta.reasoningContent();
        if (reasoning != null && !reasoning.isEmpty()) {
            if (!streamState[0]) {
                // 多轮之间的空行直接并进首片，前端纯追加、落库的时间线也自带这个分隔
                reasoning = streamState[1] ? "\n\n" + reasoning : reasoning;
                streamState[0] = true;
                streamState[1] = true;
            }
            liveSink.tryEmitNext(AgentEvent.reasoning(reasoning));
        }
        String content = delta.content();
        if (content != null && !content.isEmpty()) {
            liveSink.tryEmitNext(AgentEvent.delta(content));
        }
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

    private String serializeTimeline(List<AgentTimelineBlock> timeline) {
        try {
            return objectMapper.writeValueAsString(List.copyOf(timeline));
        } catch (JsonProcessingException e) {
            log.warn("序列化 Agent 时间线失败: {}", e.getMessage());
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
