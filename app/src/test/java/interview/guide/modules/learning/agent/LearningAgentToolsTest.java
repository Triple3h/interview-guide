package interview.guide.modules.learning.agent;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import interview.guide.modules.learning.model.LearningRecordEntity;
import interview.guide.modules.learning.service.LearningRecordService;
import interview.guide.modules.user.model.UserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("学习帮手工具测试")
class LearningAgentToolsTest {

    @Mock
    private KnowledgeBaseVectorService vectorService;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private LearningRecordService recordService;

    private LearningAgentProperties properties;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        properties = new LearningAgentProperties();
    }

    private LearningAgentTools createTools(List<Long> preferredKbIds) {
        UserEntity learner = new UserEntity();
        learner.setId(1L);
        learner.setNickname("Alice");
        return new LearningAgentTools(1L, 100L, preferredKbIds, learner,
            vectorService, knowledgeBaseRepository, recordService, properties);
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
}
