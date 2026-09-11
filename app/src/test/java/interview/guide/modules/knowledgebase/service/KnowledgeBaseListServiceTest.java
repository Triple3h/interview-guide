package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.mapper.KnowledgeBaseMapper;
import interview.guide.modules.knowledgebase.model.CategoryTreeNode;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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

    @Nested
    @DisplayName("按分类筛选")
    class ListByCategory {

        @Test
        @DisplayName("选中一级分类时连同其下二级分类一起命中")
        void shouldIncludeChildCategories() {
            when(knowledgeBaseRepository.findAllCategories())
                .thenReturn(List.of("ai", "ai/agent", "ai/rag", "system-design"));
            when(knowledgeBaseRepository.findByCategoryInOrderByUploadedAtDesc(anyList()))
                .thenReturn(List.of());
            when(knowledgeBaseMapper.toListItemDTOList(anyList())).thenReturn(List.of());

            listService.listByCategory("ai");

            verify(knowledgeBaseRepository)
                .findByCategoryInOrderByUploadedAtDesc(List.of("ai", "ai/agent", "ai/rag"));
        }

        @Test
        @DisplayName("选中二级分类时只精确匹配该分类")
        void shouldMatchChildCategoryExactly() {
            when(knowledgeBaseRepository.findAllCategories())
                .thenReturn(List.of("ai", "ai/agent", "ai/rag"));
            when(knowledgeBaseRepository.findByCategoryInOrderByUploadedAtDesc(anyList()))
                .thenReturn(List.of());
            when(knowledgeBaseMapper.toListItemDTOList(anyList())).thenReturn(List.of());

            listService.listByCategory("ai/agent");

            verify(knowledgeBaseRepository)
                .findByCategoryInOrderByUploadedAtDesc(List.of("ai/agent"));
        }

        @Test
        @DisplayName("分类不存在时返回空列表且不查库")
        void shouldReturnEmptyWhenNoCategoryMatched() {
            when(knowledgeBaseRepository.findAllCategories()).thenReturn(List.of("ai/agent"));

            assertThat(listService.listByCategory("rag")).isEmpty();

            verify(knowledgeBaseRepository, never()).findByCategoryInOrderByUploadedAtDesc(anyList());
        }

        @Test
        @DisplayName("空白分类返回未分类知识库")
        void shouldReturnUncategorizedWhenBlank() {
            when(knowledgeBaseRepository.findByCategoryIsNullOrderByUploadedAtDesc()).thenReturn(List.of());
            when(knowledgeBaseMapper.toListItemDTOList(anyList())).thenReturn(List.of());

            assertThat(listService.listByCategory("  ")).isEmpty();

            verify(knowledgeBaseRepository).findByCategoryIsNullOrderByUploadedAtDesc();
        }
    }

    @Nested
    @DisplayName("按状态排序")
    class SortByStatus {

        @Test
        @DisplayName("失败、处理中、待处理排在已完成之前，同状态保持原有时间倒序")
        void shouldSortByStatusWorthAttentionFirst() {
            // 传入顺序即默认的上传时间倒序
            when(knowledgeBaseRepository.findAllByOrderByUploadedAtDesc()).thenReturn(List.of(
                knowledgeBase(1L, VectorStatus.COMPLETED),
                knowledgeBase(2L, VectorStatus.FAILED),
                knowledgeBase(3L, VectorStatus.PROCESSING),
                knowledgeBase(4L, VectorStatus.PENDING),
                knowledgeBase(5L, VectorStatus.FAILED)
            ));
            when(knowledgeBaseMapper.toListItemDTOList(anyList())).thenReturn(List.of());

            listService.listKnowledgeBases(null, "status");

            ArgumentCaptor<List<KnowledgeBaseEntity>> captor = ArgumentCaptor.forClass(List.class);
            verify(knowledgeBaseMapper).toListItemDTOList(captor.capture());
            assertThat(captor.getValue())
                .extracting(KnowledgeBaseEntity::getId)
                .containsExactly(2L, 5L, 3L, 4L, 1L);
        }
    }

    @Nested
    @DisplayName("分类树")
    class CategoryTree {

        @Test
        @DisplayName("逐级登记多级分类：中间层级也作为可选节点，name 为完整路径")
        void shouldBuildNestedTree() {
            when(knowledgeBaseRepository.findAllCategories())
                .thenReturn(List.of("ai/agent/rag", "ai/agent", "database"));

            List<CategoryTreeNode> tree = listService.getCategoryTree();

            assertThat(tree).extracting(CategoryTreeNode::name).containsExactly("ai", "database");
            CategoryTreeNode agent = tree.get(0).children().get(0);
            assertThat(agent.name()).isEqualTo("ai/agent");
            assertThat(agent.children()).extracting(CategoryTreeNode::name).containsExactly("ai/agent/rag");
            // 叶子节点 children 为空而不是 null
            assertThat(tree.get(1).children()).isEmpty();
        }
    }

    private KnowledgeBaseEntity knowledgeBase(Long id, VectorStatus status) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setId(id);
        entity.setVectorStatus(status);
        return entity;
    }
}
