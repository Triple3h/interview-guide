package interview.guide.modules.learning.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.learning.model.LearningRecordDTO.LearningRecordResponse;
import interview.guide.modules.learning.model.LearningRecordEntity;
import interview.guide.modules.learning.model.LearningRecordEntity.Mastery;
import interview.guide.modules.learning.repository.LearningRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.infrastructure.mapper.LearningRecordMapper;

@DisplayName("学习台账服务测试")
class LearningRecordServiceTest {

    @Mock
    private LearningRecordRepository recordRepository;

    @Mock
    private LearningRecordMapper recordMapper;

    private LearningRecordService recordService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        recordService = new LearningRecordService(recordRepository, recordMapper);
    }

    @Nested
    @DisplayName("Agent 上报知识点（upsert）")
    class UpsertFromAgent {

        @Test
        @DisplayName("主题不存在时新建记录")
        void shouldCreateWhenTopicNotExists() {
            when(recordRepository.findByUserIdAndTopicIgnoreCase(1L, "Redis 持久化"))
                .thenReturn(Optional.empty());
            when(recordRepository.save(any(LearningRecordEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

            LearningRecordService.UpsertResult result = recordService.upsertFromAgent(
                1L, "Redis 持久化", "RDB 快照 + AOF 日志", "INTERMEDIATE", 10L);

            assertThat(result.created()).isTrue();
            LearningRecordEntity saved = result.record();
            assertThat(saved.getUserId()).isEqualTo(1L);
            assertThat(saved.getTopic()).isEqualTo("Redis 持久化");
            assertThat(saved.getMastery()).isEqualTo(Mastery.INTERMEDIATE);
            assertThat(saved.getSourceSessionId()).isEqualTo(10L);
            assertThat(saved.getLastReviewedAt()).isNotNull();
        }

        @Test
        @DisplayName("主题已存在（忽略大小写）时更新原记录，不新建")
        void shouldUpdateExistingTopicIgnoreCase() {
            LearningRecordEntity existing = new LearningRecordEntity();
            existing.setId(5L);
            existing.setUserId(1L);
            existing.setTopic("redis 持久化");

            when(recordRepository.findByUserIdAndTopicIgnoreCase(1L, "Redis 持久化"))
                .thenReturn(Optional.of(existing));
            when(recordRepository.save(any(LearningRecordEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

            LearningRecordService.UpsertResult result = recordService.upsertFromAgent(
                1L, "Redis 持久化", "更新后的总结", "ADVANCED", 11L);

            ArgumentCaptor<LearningRecordEntity> captor = ArgumentCaptor.forClass(LearningRecordEntity.class);
            verify(recordRepository).save(captor.capture());
            assertThat(captor.getValue().getId()).isEqualTo(5L);
            assertThat(result.created()).isFalse();
            assertThat(result.record().getSummary()).isEqualTo("更新后的总结");
            assertThat(result.record().getMastery()).isEqualTo(Mastery.ADVANCED);
        }

        @Test
        @DisplayName("掌握度使用中文标签也能解析")
        void shouldParseChineseMasteryLabel() {
            when(recordRepository.findByUserIdAndTopicIgnoreCase(1L, "索引"))
                .thenReturn(Optional.empty());
            when(recordRepository.save(any(LearningRecordEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

            LearningRecordService.UpsertResult result = recordService.upsertFromAgent(1L, "索引", "B+ 树", "熟练", null);

            assertThat(result.record().getMastery()).isEqualTo(Mastery.ADVANCED);
        }

        @Test
        @DisplayName("掌握度非法时抛出 IllegalArgumentException")
        void shouldThrowWhenMasteryInvalid() {
            assertThatThrownBy(() -> recordService.upsertFromAgent(1L, "主题", "总结", "精通", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("掌握度");
        }

        @Test
        @DisplayName("主题为空白时抛出 BAD_REQUEST")
        void shouldThrowWhenTopicBlank() {
            assertThatThrownBy(() -> recordService.upsertFromAgent(1L, "   ", "总结", "BEGINNER", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("主题不能为空");
        }
    }

    @Nested
    @DisplayName("手动维护")
    class ManualCrud {

        @Test
        @DisplayName("新增时主题重复抛出 BAD_REQUEST")
        void shouldThrowWhenCreateDuplicateTopic() {
            when(recordRepository.findByUserIdAndTopicIgnoreCase(1L, "Redis 持久化"))
                .thenReturn(Optional.of(new LearningRecordEntity()));

            assertThatThrownBy(() -> recordService.create(1L, new interview.guide.modules.learning.model.LearningRecordDTO.CreateRecordRequest(
                "Redis 持久化", "总结", "BEGINNER")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已存在");
        }

        @Test
        @DisplayName("更新他人记录按不存在处理")
        void shouldThrowWhenUpdateOthersRecord() {
            LearningRecordEntity others = new LearningRecordEntity();
            others.setId(9L);
            others.setUserId(2L);
            when(recordRepository.findById(9L)).thenReturn(Optional.of(others));

            assertThatThrownBy(() -> recordService.update(1L, 9L,
                new interview.guide.modules.learning.model.LearningRecordDTO.UpdateRecordRequest(null, null, "ADVANCED")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("学习记录不存在");
        }

        @Test
        @DisplayName("删除他人记录按不存在处理")
        void shouldThrowWhenDeleteOthersRecord() {
            LearningRecordEntity others = new LearningRecordEntity();
            others.setId(9L);
            others.setUserId(2L);
            when(recordRepository.findById(9L)).thenReturn(Optional.of(others));

            assertThatThrownBy(() -> recordService.delete(1L, 9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("学习记录不存在");
        }
    }

    @Nested
    @DisplayName("台账查询")
    class ListRecords {

        @Test
        @DisplayName("keyword 同时匹配主题与摘要（忽略大小写）")
        void shouldFilterByTopicOrSummary() {
            LearningRecordEntity byTopic = record("Redis 持久化", "RDB 与 AOF");
            LearningRecordEntity bySummary = record("MySQL 索引", "提到 redis 缓存的场景");
            LearningRecordEntity noMatch = record("JVM 调优", "垃圾回收器");
            when(recordRepository.findByUserIdOrderByUpdatedAtDesc(1L))
                .thenReturn(List.of(byTopic, bySummary, noMatch));
            when(recordMapper.toResponseList(any())).thenReturn(List.of());

            recordService.list(1L, "REDIS");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<LearningRecordEntity>> captor =
                ArgumentCaptor.forClass((Class<List<LearningRecordEntity>>) (Class<?>) List.class);
            verify(recordMapper).toResponseList(captor.capture());
            assertThat(captor.getValue()).containsExactly(byTopic, bySummary);
        }

        private LearningRecordEntity record(String topic, String summary) {
            LearningRecordEntity entity = new LearningRecordEntity();
            entity.setUserId(1L);
            entity.setTopic(topic);
            entity.setSummary(summary);
            return entity;
        }
    }
}
