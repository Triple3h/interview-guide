package interview.guide.modules.learning.agent;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import interview.guide.modules.learning.model.LearningRecordEntity;
import interview.guide.modules.learning.service.LearningPlanService;
import interview.guide.modules.learning.service.LearningRecordService;
import interview.guide.modules.user.model.UserDTO.UserResponse;
import interview.guide.modules.user.model.UserEntity;
import interview.guide.modules.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("学习帮手工具测试")
class LearningAgentToolsTest {

    @Mock
    private KnowledgeBaseVectorService vectorService;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private LearningRecordService recordService;

    @Mock
    private InterviewSkillService skillService;

    @Mock
    private LearningPlanService planService;

    @Mock
    private LearningAskRegistry askRegistry;

    @Mock
    private UserService userService;

    private LearningAgentProperties properties;

    private List<AgentEvent> askEvents;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        properties = new LearningAgentProperties();
        askEvents = new ArrayList<>();
    }

    private LearningAgentTools createTools(List<Long> preferredKbIds) {
        return createTools(preferredKbIds, newLearner());
    }

    private LearningAgentTools createTools(List<Long> preferredKbIds, UserEntity learner) {
        return new LearningAgentTools(1L, 100L, preferredKbIds, learner, userService,
            vectorService, knowledgeBaseRepository, recordService, properties, skillService,
            planService, askRegistry, askEvents::add);
    }

    private UserEntity newLearner() {
        UserEntity learner = new UserEntity();
        learner.setId(1L);
        learner.setNickname("Alice");
        return learner;
    }

    private UserResponse profileResponse(String occupation, String learningDirection,
                                         String learningSkillId, String currentLevel, String learningGoal) {
        return new UserResponse(1L, "Alice", "🦊", occupation, learningDirection,
            learningSkillId, currentLevel, learningGoal, null);
    }

    @Nested
    @DisplayName("知识库检索工具")
    class SearchKnowledgeBase {

        @Test
        @DisplayName("命中片段时返回带来源的格式化结果")
        void shouldFormatResultsWithSource() {
            Document doc = new Document("Redis 的 RDB 是快照持久化", Map.of(
                "kb_id", "7",
                "section_path", "Redis 基础 > 持久化"));
            when(vectorService.similaritySearch(eq("RDB 是什么"), any(), anyInt(), anyDouble()))
                .thenReturn(List.of(doc));
            KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
            kb.setId(7L);
            kb.setName("Redis 学习笔记");
            when(knowledgeBaseRepository.findAllById(List.of(7L))).thenReturn(List.of(kb));

            String result = createTools(List.of()).searchKnowledgeBase("RDB 是什么");

            assertThat(result).contains("【片段1】")
                .contains("来源: Redis 学习笔记")
                .contains("章节: Redis 基础 > 持久化")
                .contains("RDB 是快照持久化");
        }

        @Test
        @DisplayName("无命中时返回提示语引导模型自行回答")
        void shouldReturnHintWhenNoHit() {
            when(vectorService.similaritySearch(eq("量子力学"), any(), anyInt(), anyDouble()))
                .thenReturn(List.of());

            String result = createTools(List.of()).searchKnowledgeBase("量子力学");

            assertThat(result).contains("没有检索到");
        }

        @Test
        @DisplayName("会话绑定了知识库时按限定范围检索")
        void shouldSearchWithinPreferredKbIds() {
            when(vectorService.similaritySearch(eq("查询"), eq(List.of(3L, 5L)), anyInt(), anyDouble()))
                .thenReturn(List.of());

            createTools(List.of(3L, 5L)).searchKnowledgeBase("查询");

            verify(vectorService).similaritySearch(eq("查询"), eq(List.of(3L, 5L)), anyInt(), anyDouble());
        }
    }

    @Nested
    @DisplayName("学习档案与台账工具")
    class ProfileAndTopics {

        @Test
        @DisplayName("档案字段为空时显示未填写")
        void shouldShowDefaultForBlankProfile() {
            String result = createTools(List.of()).getLearnerProfile();

            assertThat(result).contains("昵称: Alice").contains("职业: 未填写");
        }

        @Test
        @DisplayName("台账为空时返回提示")
        void shouldReturnEmptyHintWhenNoRecords() {
            when(recordService.listEntities(1L, null)).thenReturn(List.of());

            String result = createTools(List.of()).listLearnedTopics("");

            assertThat(result).contains("暂无");
        }

        @Test
        @DisplayName("记录知识点委托给台账服务并返回确认文案")
        void shouldUpsertViaRecordService() {
            LearningRecordEntity saved = new LearningRecordEntity();
            saved.setTopic("Redis 持久化");
            saved.setMastery(LearningRecordEntity.Mastery.INTERMEDIATE);
            when(recordService.upsertFromAgent(1L, "Redis 持久化", "RDB + AOF", "INTERMEDIATE", 100L))
                .thenReturn(new LearningRecordService.UpsertResult(saved, true));

            String result = createTools(List.of()).upsertLearningRecord("Redis 持久化", "RDB + AOF", "INTERMEDIATE");

            assertThat(result).contains("已记录").contains("Redis 持久化").contains("理解");
        }

        @Test
        @DisplayName("describeArgs 从入参 JSON 提取检索关键词")
        void shouldDescribeArgsForStepSummary() {
            String summary = LearningAgentTools.describeArgs(
                "searchKnowledgeBase", "{\"query\":\"什么是 B+ 树\"}");

            assertThat(summary).contains("检索知识库").contains("什么是 B+ 树");
        }
    }

    @Nested
    @DisplayName("学员档案补全工具")
    class UpdateLearnerProfile {

        @Test
        @DisplayName("命中预置学习方向时关联 skill id，并刷新内存快照")
        void shouldUpdateProfileAndSyncSnapshot() {
            UserEntity learner = newLearner();
            when(skillService.getAllSkills()).thenReturn(List.of(
                new InterviewSkillService.SkillDTO("java-backend", "Java 后端开发", "Java 方向",
                    List.of(), true, null, null, null, false)));
            when(userService.updateProfileFromAgent(1L, "后端工程师", "Java 后端开发", "java-backend", null, null))
                .thenReturn(new UserService.ProfileUpdateResult(
                    profileResponse("后端工程师", "Java 后端开发", "java-backend", null, null),
                    List.of("职业", "学习方向")));

            LearningAgentTools tools = createTools(List.of(), learner);
            String result = tools.updateLearnerProfile("后端工程师", "Java 后端开发", "", "");

            assertThat(result).contains("已更新学员档案").contains("职业").contains("学习方向");
            assertThat(tools.getLearnerProfile()).contains("后端工程师").contains("Java 后端开发");
        }

        @Test
        @DisplayName("自定义学习方向清除旧的预置方向关联")
        void shouldClearSkillIdForCustomDirection() {
            when(skillService.getAllSkills()).thenReturn(List.of());
            when(userService.updateProfileFromAgent(1L, null, "英语口语", "", null, null))
                .thenReturn(new UserService.ProfileUpdateResult(
                    profileResponse(null, "英语口语", null, null, null), List.of("学习方向")));

            String result = createTools(List.of()).updateLearnerProfile(" ", "英语口语", null, "");

            assertThat(result).contains("已更新学员档案");
            verify(userService).updateProfileFromAgent(1L, null, "英语口语", "", null, null);
        }

        @Test
        @DisplayName("所有字段都为空时不调用服务")
        void shouldSkipWhenNoFieldProvided() {
            String result = createTools(List.of()).updateLearnerProfile("  ", null, "", null);

            assertThat(result).contains("没有提供新的档案信息");
            verifyNoInteractions(userService);
        }

        @Test
        @DisplayName("服务拒绝时返回可修正提示而不是抛错")
        void shouldReturnHintWhenServiceRejects() {
            when(userService.updateProfileFromAgent(1L, "后端工程师", null, null, null, null))
                .thenThrow(new BusinessException(ErrorCode.BAD_REQUEST, "职业最长 100 字"));

            String result = createTools(List.of()).updateLearnerProfile("后端工程师", null, null, null);

            assertThat(result).contains("档案未更新").contains("职业最长 100 字");
        }

        @Test
        @DisplayName("无有效变更时提示未更新")
        void shouldReturnHintWhenNothingChanged() {
            when(userService.updateProfileFromAgent(1L, null, null, null, "会用但不系统", null))
                .thenReturn(new UserService.ProfileUpdateResult(
                    profileResponse(null, null, null, "会用但不系统", null), List.of()));

            String result = createTools(List.of()).updateLearnerProfile(null, null, "会用但不系统", null);

            assertThat(result).contains("没有提供新的档案信息");
        }

        @Test
        @DisplayName("describeArgs 从入参 JSON 提取本次提交的字段")
        void shouldDescribeArgsForProfileUpdate() {
            String summary = LearningAgentTools.describeArgs("updateLearnerProfile",
                "{\"occupation\":\"后端工程师\",\"learningGoal\":\"补齐分布式基础\"}");

            assertThat(summary).contains("更新学员档案")
                .contains("职业=后端工程师")
                .contains("目标=补齐分布式基础");
        }
    }

    @Nested
    @DisplayName("知识基线加载工具")
    class LoadSkillBaseline {

        @Test
        @DisplayName("命中分类时返回带使用提示的知识基线内容")
        void shouldReturnBaselineWithUsageHint() {
            when(skillService.loadCategoryBaseline("REDIS")).thenReturn("Redis 考察要点：持久化、缓存、分布式锁");

            String result = createTools(List.of()).loadSkillBaseline("REDIS");

            assertThat(result)
                .contains("REDIS")
                .contains("Redis 考察要点：持久化、缓存、分布式锁")
                .contains("不要照本宣科");
        }

        @Test
        @DisplayName("未命中分类时返回引导提示")
        void shouldReturnHintWhenCategoryNotFound() {
            when(skillService.loadCategoryBaseline("UNKNOWN")).thenReturn(null);

            String result = createTools(List.of()).loadSkillBaseline("UNKNOWN");

            assertThat(result).contains("没有找到").contains("学习方向分类");
        }

        @Test
        @DisplayName("describeArgs 从入参 JSON 提取分类 key")
        void shouldDescribeArgsForBaseline() {
            String summary = LearningAgentTools.describeArgs(
                "loadSkillBaseline", "{\"categoryKey\":\"JAVA\"}");

            assertThat(summary).contains("加载知识基线").contains("JAVA");
        }
    }

    @Nested
    @DisplayName("学习计划固化工具")
    class UpsertLearningPlan {

        @Test
        @DisplayName("条目委托给计划服务并返回固化结果")
        void shouldDelegateToPlanService() {
            when(planService.upsertFromAgent(eq(1L), anyList(), eq(100L)))
                .thenReturn(new LearningPlanService.UpsertResult(2, 1, 1));

            String result = createTools(List.of()).upsertLearningPlan(List.of(
                new LearningAgentTools.PlanItemInput("Redis 持久化", "补齐持久化短板", "PENDING"),
                new LearningAgentTools.PlanItemInput("MySQL 索引", "系统学索引", "IN_PROGRESS")));

            assertThat(result).contains("已固化").contains("2").contains("1");
            verify(planService).upsertFromAgent(eq(1L), anyList(), eq(100L));
        }

        @Test
        @DisplayName("过滤空白主题条目后再提交")
        void shouldFilterBlankTopics() {
            when(planService.upsertFromAgent(eq(1L), anyList(), eq(100L)))
                .thenReturn(new LearningPlanService.UpsertResult(1, 1, 0));

            createTools(List.of()).upsertLearningPlan(List.of(
                new LearningAgentTools.PlanItemInput("  ", "空白主题", null),
                new LearningAgentTools.PlanItemInput("Redis 持久化", null, null)));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<LearningPlanService.AgentPlanItem>> captor =
                ArgumentCaptor.forClass((Class) List.class);
            verify(planService).upsertFromAgent(eq(1L), captor.capture(), eq(100L));
            assertThat(captor.getValue()).hasSize(1);
            assertThat(captor.getValue().get(0).topic()).isEqualTo("Redis 持久化");
        }

        @Test
        @DisplayName("服务拒绝时返回可修正提示而不是抛错")
        void shouldReturnHintWhenServiceRejects() {
            when(planService.upsertFromAgent(eq(1L), anyList(), eq(100L)))
                .thenThrow(new BusinessException(ErrorCode.BAD_REQUEST, "计划条目最多 20 条"));

            String result = createTools(List.of()).upsertLearningPlan(List.of(
                new LearningAgentTools.PlanItemInput("主题", null, null)));

            assertThat(result).contains("计划未保存").contains("最多 20 条");
        }
    }

    @Nested
    @DisplayName("学员提问工具")
    class AskLearner {

        @Test
        @DisplayName("发出 ask 事件并返回学员的点选回答")
        void shouldEmitAskEventAndReturnAnswer() {
            CompletableFuture<String> future = new CompletableFuture<>();
            future.complete("先学 Redis 持久化");
            when(askRegistry.register(eq(100L), eq("先学哪个？"), anyList())).thenReturn(future);

            String result = createTools(List.of()).askLearner(
                "先学哪个？", List.of("Redis 持久化", "MySQL 索引"));

            assertThat(result).contains("学员的回答").contains("先学 Redis 持久化");
            assertThat(askEvents).hasSize(1);
            assertThat(askEvents.get(0).type()).isEqualTo("ask");
            assertThat(askEvents.get(0).question()).isEqualTo("先学哪个？");
            assertThat(askEvents.get(0).options()).containsExactly("Redis 持久化", "MySQL 索引");
        }

        @Test
        @DisplayName("等待超时返回降级提示并清理注册表")
        void shouldReturnHintOnTimeout() {
            properties.setAskTimeoutSeconds(0);
            when(askRegistry.register(eq(100L), any(), anyList()))
                .thenReturn(new CompletableFuture<>());

            String result = createTools(List.of()).askLearner("先学哪个？", List.of("A", "B"));

            assertThat(result).contains("超时").contains("最合理的假设");
            verify(askRegistry).evict(eq(100L), any());
        }

        @Test
        @DisplayName("describeArgs 从入参 JSON 提取问题")
        void shouldDescribeArgsForAsk() {
            String summary = LearningAgentTools.describeArgs(
                "askLearner", "{\"question\":\"先学哪个？\"}");

            assertThat(summary).contains("向学员提问").contains("先学哪个");
        }
    }
}
