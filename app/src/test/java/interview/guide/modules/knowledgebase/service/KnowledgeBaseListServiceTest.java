package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.mapper.KnowledgeBaseMapper;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("知识库列表服务测试（批量分类）")
class KnowledgeBaseListServiceTest {

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock
    private RagChatMessageRepository ragChatMessageRepository;
    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Mock
    private FileStorageService fileStorageService;

    private KnowledgeBaseListService listService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        listService = new KnowledgeBaseListService(
            knowledgeBaseRepository, ragChatMessageRepository, knowledgeBaseMapper, fileStorageService);
    }

    @Nested
    @DisplayName("批量更新分类")
    class UpdateCategoryBatch {

        @Test
        @DisplayName("ids 为空时抛出 BAD_REQUEST")
        void shouldThrowWhenIdsEmpty() {
            assertThatThrownBy(() -> listService.updateCategoryBatch(List.of(), "MySQL"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请选择要更新的知识库");
        }

        @Test
        @DisplayName("分类为空白时批量置为未分类")
        void shouldSetNullWhenCategoryBlank() {
            when(knowledgeBaseRepository.updateCategoryBatch(List.of(1L, 2L), null)).thenReturn(2);

            int updated = listService.updateCategoryBatch(List.of(1L, 2L), "   ");

            assertThat(updated).isEqualTo(2);
            verify(knowledgeBaseRepository).updateCategoryBatch(anyList(), isNull());
        }

        @Test
        @DisplayName("分类去空格后批量更新")
        void shouldTrimCategoryBeforeUpdate() {
            when(knowledgeBaseRepository.updateCategoryBatch(List.of(1L), "MySQL 实战")).thenReturn(1);

            int updated = listService.updateCategoryBatch(List.of(1L), "  MySQL 实战  ");

            assertThat(updated).isEqualTo(1);
            verify(knowledgeBaseRepository).updateCategoryBatch(eq(List.of(1L)), eq("MySQL 实战"));
        }
    }
}
