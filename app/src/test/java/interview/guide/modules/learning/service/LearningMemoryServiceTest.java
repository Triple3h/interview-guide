package interview.guide.modules.learning.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.mapper.LearningMemoryMapper;
import interview.guide.infrastructure.mapper.LearningMemoryMapperImpl;
import interview.guide.modules.learning.model.LearningMemoryDTO.CreateMemoryRequest;
import interview.guide.modules.learning.model.LearningMemoryDTO.LearningMemoryResponse;
import interview.guide.modules.learning.model.LearningMemoryDTO.MemoryOp;
import interview.guide.modules.learning.model.LearningMemoryDTO.UpdateMemoryRequest;
import interview.guide.modules.learning.model.LearningMemoryEntity;
import interview.guide.modules.learning.model.LearningMemoryEntity.Kind;
import interview.guide.modules.learning.repository.LearningMemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("个人记忆服务测试")
class LearningMemoryServiceTest {

    @Mock
    private LearningMemoryRepository memoryRepository;

    private LearningMemoryMapper memoryMapper;

    private LearningMemoryService memoryService;

    private final AtomicLong idSeq = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        memoryMapper = new LearningMemoryMapperImpl();
        memoryService = new LearningMemoryService(memoryRepository, memoryMapper);
        when(memoryRepository.save(any(LearningMemoryEntity.class))).thenAnswer(invocation -> {
            LearningMemoryEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(idSeq.getAndIncrement());
            }
            return entity;
        });
    }

    private LearningMemoryEntity memory(long id, long userId, Kind kind, String content) {
        LearningMemoryEntity entity = new LearningMemoryEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setKind(kind);
        entity.setContent(content);
        return entity;
    }

    @Nested
    @DisplayName("手动维护")
    class ManualMaintenance {

        @Test
        @DisplayName("列表按内容与类型过滤，且只返回本人记忆")
        void shouldFilterByKeywordAndKind() {
            when(memoryRepository.findByUserIdOrderByUpdatedAtDesc(1L)).thenReturn(List.of(
                memory(1L, 1L, Kind.PREFERENCE, "讲解时给代码示例"),
                memory(2L, 1L, Kind.QUESTION, "Redis AOF 和 RDB 有何区别")));

            List<LearningMemoryResponse> filtered = memoryService.list(1L, "代码", "PREFERENCE");

            assertThat(filtered).hasSize(1);
            assertThat(filtered.getFirst().kind()).isEqualTo("PREFERENCE");
            assertThat(filtered.getFirst().kindLabel()).isEqualTo("偏好");
        }

        @Test
        @DisplayName("非法类型拒绝")
        void shouldRejectInvalidKind() {
            assertThatThrownBy(() -> memoryService.create(1L, new CreateMemoryRequest("UNKNOWN", "内容")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.LEARNING_MEMORY_KIND_INVALID.getCode());
        }

        @Test
        @DisplayName("删除他人记忆按不存在处理")
        void shouldRejectDeletingOthersMemory() {
            when(memoryRepository.findById(7L)).thenReturn(Optional.of(
                memory(7L, 2L, Kind.NOTE, "别人的记忆")));

            assertThatThrownBy(() -> memoryService.delete(1L, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.LEARNING_MEMORY_NOT_FOUND.getCode());
            verify(memoryRepository, never()).deleteById(7L);
        }

        @Test
        @DisplayName("更新本人记忆")
        void shouldUpdateOwnedMemory() {
            LearningMemoryEntity entity = memory(5L, 1L, Kind.NOTE, "旧内容");
            when(memoryRepository.findById(5L)).thenReturn(Optional.of(entity));

            LearningMemoryResponse response = memoryService.update(1L, 5L,
                new UpdateMemoryRequest("HABIT", "晚上学习"));

            assertThat(response.kind()).isEqualTo("HABIT");
            assertThat(response.content()).isEqualTo("晚上学习");
        }
    }

    @Nested
    @DisplayName("抽取落库")
    class ApplyExtract {

        @Test
        @DisplayName("ADD 使用任务中的 userId，忽略模型编造的归属")
        void shouldAddWithPayloadUserId() {
            LearningMemoryService.ApplyResult result = memoryService.applyExtractedOperations(
                1L, 10L, 20L,
                List.of(new MemoryOp("ADD", 999L, "PREFERENCE", "讲解时给代码示例")));

            assertThat(result.added()).isEqualTo(1);
            ArgumentCaptor<LearningMemoryEntity> captor = ArgumentCaptor.forClass(LearningMemoryEntity.class);
            verify(memoryRepository).save(captor.capture());
            assertThat(captor.getValue().getUserId()).isEqualTo(1L);
            assertThat(captor.getValue().getKind()).isEqualTo(Kind.PREFERENCE);
            assertThat(captor.getValue().getSourceMessageId()).isEqualTo(20L);
        }

        @Test
        @DisplayName("抽取 ADD 命中已有重复内容时不再新增")
        void shouldSkipDuplicateAdd() {
            when(memoryRepository.findByUserIdAndKind(1L, Kind.PREFERENCE)).thenReturn(List.of(
                memory(4L, 1L, Kind.PREFERENCE, "讲解时给代码示例，并对比错误写法")));

            LearningMemoryService.ApplyResult result = memoryService.applyExtractedOperations(
                1L, 10L, 20L,
                List.of(new MemoryOp("ADD", null, "PREFERENCE", "讲解时给代码示例")));

            assertThat(result.added()).isZero();
            verify(memoryRepository, never()).save(any(LearningMemoryEntity.class));
        }

        @Test
        @DisplayName("抽取 ADD 命中重复但内容更完整时就地补全")
        void shouldMergeDuplicateContent() {
            LearningMemoryEntity existing = memory(4L, 1L, Kind.PREFERENCE, "讲解时给代码示例");
            when(memoryRepository.findByUserIdAndKind(1L, Kind.PREFERENCE)).thenReturn(List.of(existing));

            LearningMemoryService.ApplyResult result = memoryService.applyExtractedOperations(
                1L, 10L, 20L,
                List.of(new MemoryOp("ADD", null, "PREFERENCE", "讲解时给代码示例，并对比错误写法")));

            assertThat(result.added()).isZero();
            assertThat(existing.getContent()).isEqualTo("讲解时给代码示例，并对比错误写法");
            verify(memoryRepository).save(existing);
        }

        @Test
        @DisplayName("UPDATE/DELETE 他人记忆时跳过")
        void shouldIgnoreOthersMemoryOnUpdateAndDelete() {
            when(memoryRepository.findById(8L)).thenReturn(Optional.of(
                memory(8L, 2L, Kind.NOTE, "别人的记忆")));

            LearningMemoryService.ApplyResult result = memoryService.applyExtractedOperations(
                1L, 10L, 20L,
                List.of(
                    new MemoryOp("UPDATE", 8L, "NOTE", "改掉别人的"),
                    new MemoryOp("DELETE", 8L, null, null)));

            assertThat(result.updated()).isZero();
            assertThat(result.deleted()).isZero();
            verify(memoryRepository, never()).deleteById(8L);
        }

        @Test
        @DisplayName("同一条消息再次抽取先清掉上次写下的记忆")
        void shouldReplaceMemoriesFromSameMessage() {
            memoryService.applyExtractedOperations(1L, 10L, 20L, List.of());

            verify(memoryRepository).deleteByUserIdAndSourceMessageId(1L, 20L);
        }

        @Test
        @DisplayName("UPDATE 不改写来源消息，避免重试时误删被更新过的记忆")
        void shouldKeepOriginalSourceOnUpdate() {
            LearningMemoryEntity entity = memory(9L, 1L, Kind.NOTE, "旧内容");
            entity.setSourceMessageId(11L);
            when(memoryRepository.findById(9L)).thenReturn(Optional.of(entity));

            LearningMemoryService.ApplyResult result = memoryService.applyExtractedOperations(
                1L, 10L, 20L, List.of(new MemoryOp("UPDATE", 9L, "PREFERENCE", "新内容")));

            assertThat(result.updated()).isEqualTo(1);
            assertThat(entity.getContent()).isEqualTo("新内容");
            assertThat(entity.getKind()).isEqualTo(Kind.PREFERENCE);
            assertThat(entity.getSourceMessageId()).isEqualTo(11L);
        }

        @Test
        @DisplayName("空 UPDATE 不落库")
        void shouldSkipEmptyUpdate() {
            LearningMemoryEntity entity = memory(9L, 1L, Kind.NOTE, "旧内容");
            when(memoryRepository.findById(9L)).thenReturn(Optional.of(entity));

            LearningMemoryService.ApplyResult result = memoryService.applyExtractedOperations(
                1L, 10L, 20L, List.of(new MemoryOp("UPDATE", 9L, null, "  ")));

            assertThat(result.updated()).isZero();
            verify(memoryRepository, never()).save(any(LearningMemoryEntity.class));
        }

        @Test
        @DisplayName("删除本人记忆")
        void shouldDeleteOwnedMemoryFromExtract() {
            when(memoryRepository.findById(3L)).thenReturn(Optional.of(
                memory(3L, 1L, Kind.HABIT, "一次学太多")));

            LearningMemoryService.ApplyResult result = memoryService.applyExtractedOperations(
                1L, 10L, 21L, List.of(new MemoryOp("DELETE", 3L, null, null)));

            assertThat(result.deleted()).isEqualTo(1);
            verify(memoryRepository).deleteById(3L);
        }
    }
}
