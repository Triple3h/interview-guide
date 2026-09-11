package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.modules.knowledgebase.model.BatchDeleteKnowledgeBaseResult;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("知识库删除服务测试（批量删除）")
class KnowledgeBaseDeleteServiceTest {

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock
    private KnowledgeBaseQuestionRepository questionRepository;
    @Mock
    private RagChatSessionRepository sessionRepository;
    @Mock
    private KnowledgeBaseVectorService vectorService;
    @Mock
    private FileStorageService storageService;
    @Mock
    private TransactionalExecutor transactionalExecutor;

    private KnowledgeBaseDeleteService deleteService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        deleteService = new KnowledgeBaseDeleteService(
            knowledgeBaseRepository, questionRepository, sessionRepository,
            vectorService, storageService, transactionalExecutor);
        // 事务执行器直接执行传入动作，使删除链路在测试中可见
        when(transactionalExecutor.call(any())).thenAnswer(invocation -> {
            Supplier<?> action = invocation.getArgument(0);
            return action.get();
        });
    }

    @Nested
    @DisplayName("批量删除")
    class DeleteBatch {

        @Test
        @DisplayName("ids 为空时抛出 BAD_REQUEST")
        void shouldThrowWhenIdsEmpty() {
            assertThatThrownBy(() -> deleteService.deleteKnowledgeBasesBatch(List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请选择要删除的知识库");
        }

        @Test
        @DisplayName("全部删除成功时统计成功数量")
        void shouldCountAllSuccess() {
            when(knowledgeBaseRepository.findById(1L))
                .thenReturn(Optional.of(knowledgeBase(1L, "kb-1", "key-1")));
            when(knowledgeBaseRepository.findById(2L))
                .thenReturn(Optional.of(knowledgeBase(2L, "kb-2", "key-2")));
            when(sessionRepository.findByKnowledgeBaseIds(anyList())).thenReturn(List.of());

            BatchDeleteKnowledgeBaseResult result =
                deleteService.deleteKnowledgeBasesBatch(List.of(1L, 2L));

            assertThat(result.successCount()).isEqualTo(2);
            assertThat(result.failedCount()).isZero();
            verify(knowledgeBaseRepository, times(2)).deleteById(anyLong());
            verify(questionRepository).deleteByKnowledgeBaseId(1L);
            verify(questionRepository).deleteByKnowledgeBaseId(2L);
            verify(vectorService).deleteByKnowledgeBaseId(1L);
            verify(vectorService).deleteByKnowledgeBaseId(2L);
            verify(storageService).deleteKnowledgeBase("key-1");
            verify(storageService).deleteKnowledgeBase("key-2");
        }

        @Test
        @DisplayName("单条失败不影响其余条目")
        void shouldIsolateFailure() {
            when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.empty());
            when(knowledgeBaseRepository.findById(2L))
                .thenReturn(Optional.of(knowledgeBase(2L, "kb-2", "key-2")));
            when(sessionRepository.findByKnowledgeBaseIds(anyList())).thenReturn(List.of());

            BatchDeleteKnowledgeBaseResult result =
                deleteService.deleteKnowledgeBasesBatch(List.of(1L, 2L));

            assertThat(result.successCount()).isEqualTo(1);
            assertThat(result.failedCount()).isEqualTo(1);
            verify(vectorService, never()).deleteByKnowledgeBaseId(1L);
            verify(vectorService).deleteByKnowledgeBaseId(2L);
        }

        @Test
        @DisplayName("重复 id 只删除一次")
        void shouldDeleteDistinctIdsOnce() {
            when(knowledgeBaseRepository.findById(1L))
                .thenReturn(Optional.of(knowledgeBase(1L, "kb-1", "key-1")));
            when(sessionRepository.findByKnowledgeBaseIds(anyList())).thenReturn(List.of());

            BatchDeleteKnowledgeBaseResult result =
                deleteService.deleteKnowledgeBasesBatch(List.of(1L, 1L));

            assertThat(result.successCount()).isEqualTo(1);
            verify(knowledgeBaseRepository, times(1)).deleteById(1L);
        }
    }

    @Nested
    @DisplayName("单条删除")
    class DeleteSingle {

        @Test
        @DisplayName("删除知识库时一并清理其关联题目")
        void shouldRemoveQuestionsBeforeDelete() {
            when(knowledgeBaseRepository.findById(1L))
                .thenReturn(Optional.of(knowledgeBase(1L, "kb-1", "key-1")));
            when(sessionRepository.findByKnowledgeBaseIds(anyList())).thenReturn(List.of());
            when(questionRepository.deleteByKnowledgeBaseId(1L)).thenReturn(5);

            deleteService.deleteKnowledgeBase(1L);

            verify(questionRepository).deleteByKnowledgeBaseId(1L);
            verify(knowledgeBaseRepository).deleteById(1L);
        }

        @Test
        @DisplayName("知识库不存在时抛出异常且不清理向量与文件")
        void shouldThrowWhenNotFound() {
            when(knowledgeBaseRepository.findById(9L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> deleteService.deleteKnowledgeBase(9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("知识库不存在");

            verify(vectorService, never()).deleteByKnowledgeBaseId(anyLong());
            verify(storageService, never()).deleteKnowledgeBase(any());
        }
    }

    private KnowledgeBaseEntity knowledgeBase(Long id, String name, String storageKey) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setStorageKey(storageKey);
        return entity;
    }
}
