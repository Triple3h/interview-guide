package interview.guide.modules.learning.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.exception.BusinessException;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import interview.guide.modules.knowledgebase.service.chunking.ChunkMetadataKeys;
import interview.guide.modules.learning.model.LearningRecordEntity;
import interview.guide.modules.learning.service.LearningPlanService;
import interview.guide.modules.learning.service.LearningRecordService;
import interview.guide.modules.user.model.UserEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 学习帮手的工具集（每次请求实例化，绑定当前学员与会话）
 * 工具即 Agent 的"手脚"：检索知识库、读档案、查台账、记台账、固化计划、向学员提问
 */
@Slf4j
public class LearningAgentTools {

    private static final ObjectMapper ARGS_MAPPER = new ObjectMapper();

    private final Long userId;
    private final Long sourceSessionId;
    private final List<Long> preferredKbIds;
    private final UserEntity learner;
    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final LearningRecordService recordService;
    private final LearningAgentProperties properties;
    private final InterviewSkillService skillService;
    private final LearningPlanService planService;
    private final LearningAskRegistry askRegistry;
    private final Consumer<AgentEvent> askEmitter;

    public LearningAgentTools(Long userId, Long sourceSessionId, List<Long> preferredKbIds,
                              UserEntity learner, KnowledgeBaseVectorService vectorService,
                              KnowledgeBaseRepository knowledgeBaseRepository,
                              LearningRecordService recordService, LearningAgentProperties properties,
                              InterviewSkillService skillService, LearningPlanService planService,
                              LearningAskRegistry askRegistry, Consumer<AgentEvent> askEmitter) {
        this.userId = userId;
        this.sourceSessionId = sourceSessionId;
        this.preferredKbIds = preferredKbIds;
        this.learner = learner;
        this.vectorService = vectorService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.recordService = recordService;
        this.properties = properties;
        this.skillService = skillService;
        this.planService = planService;
        this.askRegistry = askRegistry;
        this.askEmitter = askEmitter;
    }

    @Tool(name = "searchKnowledgeBase",
        description = "在家庭共享知识库中检索学习资料片段。涉及知识性、事实性的问题先调用本工具，基于检索到的资料回答并注明来源")
    public String searchKnowledgeBase(
        @ToolParam(description = "检索关键词或问题") String query) {
        List<Long> kbIds = preferredKbIds.isEmpty() ? null : preferredKbIds;
        List<Document> docs = vectorService.similaritySearch(query, kbIds,
            properties.getSearchTopK(), properties.getSearchMinScore());

        if (docs.isEmpty()) {
            return "知识库中没有检索到与「" + query + "」相关的内容。请基于你自己的知识回答，并说明这不是来自家庭知识库。";
        }

        Map<Long, String> kbNames = loadKbNames(docs);
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (Document doc : docs) {
            sb.append("【片段").append(index++).append("】");
            Long kbId = parseKbId(doc);
            if (kbId != null && kbNames.containsKey(kbId)) {
                sb.append("来源: ").append(kbNames.get(kbId));
            }
            Object section = doc.getMetadata().get(ChunkMetadataKeys.SECTION_PATH);
            if (section != null && !section.toString().isBlank()) {
                sb.append(" | 章节: ").append(section);
            }
            sb.append('\n').append(truncate(doc.getText())).append("\n\n");
        }
        sb.append("请基于以上片段回答，并注明来源知识库名称。片段之间冲突时如实说明。");

        log.info("[LearningAgent] 检索知识库: userId={}, query={}, hits={}", userId, query, docs.size());
        return sb.toString();
    }

    @Tool(name = "getLearnerProfile",
        description = "查看当前学员的画像（职业、学习方向、当前水平、学习目标），用于个性化举例和难度把控")
    public String getLearnerProfile() {
        return "学员档案：\n"
            + "- 昵称: " + orDefault(learner.getNickname()) + "\n"
            + "- 职业: " + orDefault(learner.getOccupation()) + "\n"
            + "- 学习方向: " + orDefault(learner.getLearningDirection()) + "\n"
            + "- 当前水平: " + orDefault(learner.getCurrentLevel()) + "\n"
            + "- 学习目标: " + orDefault(learner.getLearningGoal());
    }

    @Tool(name = "listLearnedTopics",
        description = "查看学员的学习台账（已学知识点、掌握度与摘要），可按关键词过滤。出题复习、规划路径前先查看")
    public String listLearnedTopics(
        @ToolParam(description = "过滤关键词，不需要过滤时传空字符串", required = false) String keyword) {
        List<LearningRecordEntity> records = recordService.listEntities(userId, keyword);
        if (records.isEmpty()) {
            return "学习台账暂无" + (keyword != null && !keyword.isBlank() ? "匹配「" + keyword + "」的" : "") + "知识点。";
        }

        StringBuilder sb = new StringBuilder("学习台账共 ").append(records.size()).append(" 条：\n");
        for (LearningRecordEntity record : records) {
            sb.append("- ").append(record.getTopic())
                .append("（").append(record.getMastery().getLabel()).append("）");
            if (record.getSummary() != null && !record.getSummary().isBlank()) {
                sb.append("：").append(abbreviate(record.getSummary(), 80));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    @Tool(name = "upsertLearningRecord",
        description = "把本次对话中学到的知识点写入学习台账；主题已存在则更新掌握度与摘要。"
            + "每当学员理解了一个新概念、搞懂了一个此前的困惑，都应调用本工具记录")
    public String upsertLearningRecord(
        @ToolParam(description = "知识点主题，简短名词短语，如：Redis 持久化") String topic,
        @ToolParam(description = "学到了什么，1-3 句中文总结") String summary,
        @ToolParam(description = "掌握度：BEGINNER(初学) / INTERMEDIATE(理解) / ADVANCED(熟练)") String mastery) {
        LearningRecordService.UpsertResult result =
            recordService.upsertFromAgent(userId, topic, summary, mastery, sourceSessionId);
        LearningRecordEntity saved = result.record();
        return (result.created() ? "已记录知识点「" : "已更新知识点「")
            + saved.getTopic() + "」（" + saved.getMastery().getLabel() + "）";
    }

    @Tool(name = "loadSkillBaseline",
        description = "按分类 key 加载该方向的知识基线（面试考察要点骨架），系统讲解、出题、规划路径前先加载，"
            + "让内容对齐考察要点。可用分类见系统提示中「学习方向分类」")
    public String loadSkillBaseline(
        @ToolParam(description = "分类 key，如 JAVA / REDIS / REACT_VUE") String categoryKey) {
        String baseline = skillService.loadCategoryBaseline(categoryKey);
        if (baseline == null || baseline.isBlank()) {
            return "没有找到分类「" + categoryKey + "」对应的知识基线，"
                + "请改用系统提示「学习方向分类」中列出的分类 key。";
        }
        log.info("[LearningAgent] 加载知识基线: userId={}, categoryKey={}", userId, categoryKey);
        return "「" + categoryKey + "」知识基线（讲解骨架，用自己的话教学式展开，不要照本宣科）：\n" + baseline;
    }

    /**
     * Agent 工具入参：一条计划条目（Spring AI 按字段生成 JSON Schema）
     */
    public record PlanItemInput(String topic, String goal, String status) {}

    @Tool(name = "upsertLearningPlan",
        description = "把与学员商定好的学习计划固化到计划表（同名条目更新，否则新增，列表顺序即学习顺序）。"
            + "仅当学员明确要求制定/调整学习计划，或你的计划提案已获学员确认时才可调用；"
            + "制定前先 listLearnedTopics 看台账、结合学员的学习方向分类，条目要具体可执行")
    public String upsertLearningPlan(
        @ToolParam(description = "计划条目列表，按学习先后排序") List<PlanItemInput> items) {
        List<LearningPlanService.AgentPlanItem> planItems =
            (items == null ? List.<PlanItemInput>of() : items).stream()
                .filter(i -> i != null && i.topic() != null && !i.topic().isBlank())
                .map(i -> new LearningPlanService.AgentPlanItem(i.topic(), i.goal(), i.status()))
                .toList();
        try {
            LearningPlanService.UpsertResult result =
                planService.upsertFromAgent(userId, planItems, sourceSessionId);
            log.info("[LearningAgent] 固化学习计划: userId={}, total={}", userId, result.total());
            return "学习计划已固化：共 " + result.total() + " 条（新增 " + result.created()
                + "、更新 " + result.updated() + "）。后续对话会结合该计划安排学习内容";
        } catch (BusinessException e) {
            return "计划未保存：" + e.getMessage() + "。请修正后重试";
        }
    }

    @Tool(name = "askLearner",
        description = "向学员发起选择提问并等待其点选回答（前端会渲染选项卡片）。"
            + "需要在几个具体方向间做选择时使用，如制定/调整学习计划、确认学习方式、澄清学员意图；"
            + "给出 2-4 个具体互斥的选项。靠档案、台账和上下文能自行推理的问题不要问")
    public String askLearner(
        @ToolParam(description = "要问学员的问题，一句话说清楚") String question,
        @ToolParam(description = "候选项列表，2-4 个，每项一句话", required = false) List<String> options) {
        List<String> cleanOptions = options == null ? List.of()
            : options.stream()
                .filter(o -> o != null && !o.isBlank())
                .map(String::trim)
                .limit(6)
                .toList();
        log.info("[LearningAgent] 向学员提问: userId={}, sessionId={}, question={}, options={}",
            userId, sourceSessionId, question, cleanOptions);
        askEmitter.accept(AgentEvent.ask(question, cleanOptions));
        CompletableFuture<String> future = askRegistry.register(sourceSessionId, question, cleanOptions);
        try {
            String answer = future.get(properties.getAskTimeoutSeconds(), TimeUnit.SECONDS);
            return "学员的回答：" + answer;
        } catch (TimeoutException e) {
            askRegistry.evict(sourceSessionId, future);
            return "学员暂未回答（等待超时）。请基于学员档案和台账做最合理的假设继续，不要再次提问等待";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "等待学员回答被中断，请基于合理假设继续";
        } catch (ExecutionException e) {
            return "学员回答通道异常，请基于合理假设继续";
        }
    }

    // ========== 私有方法 ==========

    private Map<Long, String> loadKbNames(List<Document> docs) {
        List<Long> kbIds = docs.stream()
            .map(this::parseKbId)
            .filter(id -> id != null)
            .distinct()
            .toList();
        if (kbIds.isEmpty()) {
            return Map.of();
        }
        return knowledgeBaseRepository.findAllById(kbIds).stream()
            .collect(Collectors.toMap(KnowledgeBaseEntity::getId, KnowledgeBaseEntity::getName, (a, b) -> a));
    }

    private Long parseKbId(Document doc) {
        Object kbId = doc.getMetadata().get("kb_id");
        if (kbId == null) {
            return null;
        }
        try {
            return Long.parseLong(kbId.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        int max = properties.getMaxChunkChars();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private String orDefault(String value) {
        return value == null || value.isBlank() ? "未填写" : value;
    }

    /**
     * 供步骤装饰器复用：解析工具入参 JSON 提取摘要字段
     */
    static String describeArgs(String toolName, String toolInput) {
        return switch (toolName) {
            case "searchKnowledgeBase" -> "检索知识库：" + extractField(toolInput, "query");
            case "upsertLearningRecord" -> "记录知识点：" + extractField(toolInput, "topic");
            case "listLearnedTopics" -> "查看学习台账";
            case "getLearnerProfile" -> "读取学员档案";
            case "loadSkillBaseline" -> "加载知识基线：" + extractField(toolInput, "categoryKey");
            case "upsertLearningPlan" -> "固化学习计划";
            case "askLearner" -> "向学员提问：" + extractField(toolInput, "question");
            default -> "调用工具 " + toolName;
        };
    }

    private static String extractField(String toolInput, String field) {
        try {
            JsonNode node = ARGS_MAPPER.readTree(toolInput == null ? "{}" : toolInput);
            String value = node.path(field).asText(null);
            if (value == null || value.isBlank()) {
                return "";
            }
            return value.length() > 30 ? value.substring(0, 30) + "…" : value;
        } catch (Exception e) {
            return "";
        }
    }
}
