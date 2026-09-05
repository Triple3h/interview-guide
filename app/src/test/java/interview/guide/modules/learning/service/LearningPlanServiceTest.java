package interview.guide.modules.learning.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.mapper.LearningPlanItemMapper;
import interview.guide.infrastructure.mapper.LearningPlanItemMapperImpl;
import interview.guide.modules.learning.model.LearningPlanDTO.CreatePlanItemRequest;
import interview.guide.modules.learning.model.LearningPlanDTO.PlanItemResponse;
import interview.guide.modules.learning.model.LearningPlanItemEntity;
import interview.guide.modules.learning.repository.LearningPlanItemRepository;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("学习计划服务测试")
class LearningPlanServiceTest {

    @Mock
    private LearningPlanItemRepository planRepository;

    private LearningPlanItemMapper planMapper;

    private LearningPlanService planService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        planMapper = new LearningPlanItemMapperImpl();
        planService = new LearningPlanService(planRepository, planMapper);
    }

    private LearningPlanItemEntity item(long id, String topic, LearningPlanItemEntity.Status status, int sortOrder) {
        LearningPlanItemEntity entity = new LearningPlanItemEntity();
        entity.setId(id);
        entity.setUserId(1L);
        entity.setTopic(topic);
        entity.setGoal("目标：" + topic);
        entity.setStatus(status);
        entity.setSortOrder(sortOrder);
        return entity;
    }

    @Nested
    @DisplayName("Agent 固化计划")
    class UpsertFromAgent {

        @Test
        @DisplayName("同名主题更新，新主题按列表顺序新建")
        void shouldUpsertByTopic() {
            LearningPlanItemEntity existing = item(10L, "Redis 持久化",
                LearningPlanItemEntity.Status.PENDING, 0);
            when(planRepository.findByUserIdAndTopicIgnoreCase(1L, "Redis 持久化"))
                .thenReturn(Optional.of(existing));
            when(planRepository.findByUserIdAndTopicIgnoreCase(1L, "MySQL 索引"))
                .thenReturn(Optional.empty());
            when(planRepository.save(any(LearningPlanItemEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

            LearningPlanService.UpsertResult result = planService.upsertFromAgent(1L, List.of(
                new LearningPlanService.AgentPlanItem("MySQL 索引", "系统学索引", "PENDING"),
                new LearningPlanService.AgentPlanItem("Redis 持久化", "改为进行中", "IN_PROGRESS")),
                100L);

            assertThat(result.total()).isEqualTo(2);
            assertThat(result.created()).isEqualTo(1);
            assertThat(result.updated()).isEqualTo(1);

            ArgumentCaptor<LearningPlanItemEntity> captor =
                ArgumentCaptor.forClass(LearningPlanItemEntity.class);
            verify(planRepository, times(2)).save(captor.capture());
            List<LearningPlanItemEntity> savedAll = captor.getAllValues();
            assertThat(savedAll).hasSize(2);
            // 更新条目：状态与顺序随本次提交变化
            LearningPlanItemEntity updated = savedAll.stream()
                .filter(e -> e.getId() != null && e.getId() == 10L).findFirst().orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(LearningPlanItemEntity.Status.IN_PROGRESS);
            assertThat(updated.getSortOrder()).isEqualTo(1);
            assertThat(updated.getSourceSessionId()).isEqualTo(100L);
        }

        @Test
        @DisplayName("空条目或超过上限时拒绝")
        void shouldRejectInvalidItems() {
            assertThatThrownBy(() -> planService.upsertFromAgent(1L, List.of(), 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能为空");

            List<LearningPlanService.AgentPlanItem> tooMany = java.util.stream.IntStream
                .range(0, 21)
                .mapToObj(i -> new LearningPlanService.AgentPlanItem("主题" + i, null, null))
                .toList();
            assertThatThrownBy(() -> planService.upsertFromAgent(1L, tooMany, 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最多 20 条");
        }

        @Test
        @DisplayName("非法状态返回可修正异常")
        void shouldRejectInvalidStatus() {
            assertThatThrownBy(() -> planService.upsertFromAgent(1L,
                List.of(new LearningPlanService.AgentPlanItem("主题", null, "不明状态")), 100L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.LEARNING_PLAN_STATUS_INVALID.getCode());
        }
    }

    @Nested
    @DisplayName("手动维护")
    class ManualMaintenance {

        @Test
        @DisplayName("手动新增排在现有条目之后")
        void shouldAppendAfterExistingItems() {
            when(planRepository.findByUserIdAndTopicIgnoreCase(1L, "新主题"))
                .thenReturn(Optional.empty());
            when(planRepository.findFirstByUserIdOrderBySortOrderDesc(1L))
                .thenReturn(Optional.of(item(9L, "旧主题", LearningPlanItemEntity.Status.PENDING, 3)));
            when(planRepository.save(any(LearningPlanItemEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

            PlanItemResponse response = planService.create(1L,
                new CreatePlanItemRequest("新主题", "新目标"));

            assertThat(response.status()).isEqualTo("PENDING");
            assertThat(response.statusLabel()).isEqualTo("待开始");
            ArgumentCaptor<LearningPlanItemEntity> captor =
                ArgumentCaptor.forClass(LearningPlanItemEntity.class);
            verify(planRepository).save(captor.capture());
            assertThat(captor.getValue().getSortOrder()).isEqualTo(4);
        }

        @Test
        @DisplayName("重复主题拒绝新增")
        void shouldRejectDuplicateTopic() {
            when(planRepository.findByUserIdAndTopicIgnoreCase(1L, "已有主题"))
                .thenReturn(Optional.of(item(1L, "已有主题", LearningPlanItemEntity.Status.PENDING, 0)));

            assertThatThrownBy(() -> planService.create(1L, new CreatePlanItemRequest("已有主题", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已存在");
        }

        @Test
        @DisplayName("仅更新状态：非法取值拒绝，本人条目正常流转")
        void shouldUpdateStatusOnly() {
            LearningPlanItemEntity entity = item(5L, "Redis 持久化",
                LearningPlanItemEntity.Status.PENDING, 0);
            when(planRepository.findById(5L)).thenReturn(Optional.of(entity));
            when(planRepository.save(any(LearningPlanItemEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

            assertThatThrownBy(() -> planService.updateStatus(1L, 5L, "不明状态"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.LEARNING_PLAN_STATUS_INVALID.getCode());

            PlanItemResponse response = planService.updateStatus(1L, 5L, "DONE");
            assertThat(response.status()).isEqualTo("DONE");
            assertThat(entity.getStatus()).isEqualTo(LearningPlanItemEntity.Status.DONE);
        }

        @Test
        @DisplayName("删除他人条目按不存在处理")
        void shouldRejectDeletingOthersItem() {
            LearningPlanItemEntity others = item(7L, "他人条目", LearningPlanItemEntity.Status.PENDING, 0);
            others.setUserId(2L);
            when(planRepository.findById(7L)).thenReturn(Optional.of(others));

            assertThatThrownBy(() -> planService.delete(1L, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.LEARNING_PLAN_ITEM_NOT_FOUND.getCode());
        }
    }
}
