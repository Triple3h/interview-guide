package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.mapper.KnowledgeBaseMapper;
import interview.guide.modules.knowledgebase.model.CategoryTreeNode;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBasePageDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseStatsDTO;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity.MessageType;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 知识库查询服务
 * 负责知识库列表和详情的查询
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseListService {

    /** 分类树的根键：真实分类不会是空字符串，取空串不会与任何分类冲突 */
    private static final String ROOT_CATEGORY_KEY = "";

    /** 分页每页条数上限：防止前端传入超大值把整表拉回去，分页失去意义 */
    private static final int MAX_PAGE_SIZE = 200;

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final RagChatMessageRepository ragChatMessageRepository;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final FileStorageService fileStorageService;

    /**
     * 获取知识库列表（支持状态过滤和排序）
     * 
     * @param vectorStatus 向量化状态，null 表示不过滤
     * @param sortBy 排序字段：null 或 "time" 按上传时间倒序，另有 "size" / "access" / "question" / "status"
     *               （status 为按向量化状态排序，失败 → 处理中 → 待处理 → 已完成）
     * @return 知识库列表
     */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBases(VectorStatus vectorStatus, String sortBy) {
        List<KnowledgeBaseEntity> entities;
        
        // 如果指定了状态，按状态过滤
        if (vectorStatus != null) {
            entities = knowledgeBaseRepository.findByVectorStatusOrderByUploadedAtDesc(vectorStatus);
        } else {
            // 否则获取所有知识库
            entities = knowledgeBaseRepository.findAllByOrderByUploadedAtDesc();
        }
        
        // 如果指定了排序字段，在内存中排序
        if (sortBy != null && !sortBy.isBlank() && !sortBy.equalsIgnoreCase("time")) {
            entities = sortEntities(entities, sortBy);
        }
        
        return knowledgeBaseMapper.toListItemDTOList(entities);
    }

    /**
     * 获取所有知识库列表（保持向后兼容）
     */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBases() {
        return listKnowledgeBases(null, null);
    }

    /**
     * 分页查询知识库列表（管理页专用）
     *
     * <p>keyword / category / vectorStatus 三个筛选维度可自由组合，语义与各自单独的查询口径一致：
     * keyword 匹配名称或原始文件名（忽略大小写）；category 为前缀匹配（选中某一层会命中该层及其下全部层级）。
     * 排序沿用 {@link #sortEntities}（access / question / size / status 无法直接下推到数据库），
     * 排序后再切片分页；页码越界返回空 items，total 仍为过滤后的真实总数。
     *
     * @param keyword      关键词，空白表示不过滤
     * @param category     分类前缀，空白表示不过滤
     * @param vectorStatus 向量化状态，null 表示不过滤
     * @param sortBy       排序字段，见 {@link #sortEntities}
     * @param page         页码（从 0 开始，负数按 0 处理）
     * @param size         每页条数（限制在 1 ~ {@value #MAX_PAGE_SIZE}）
     */
    public KnowledgeBasePageDTO pageKnowledgeBases(String keyword, String category,
                                                   VectorStatus vectorStatus, String sortBy,
                                                   int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);

        List<KnowledgeBaseEntity> filtered = knowledgeBaseRepository.findAllByOrderByUploadedAtDesc().stream()
            .filter(entity -> matchesStatus(entity, vectorStatus))
            .filter(entity -> matchesKeyword(entity, keyword))
            .filter(entity -> matchesCategory(entity, category))
            .toList();

        List<KnowledgeBaseEntity> sorted = (sortBy != null && !sortBy.isBlank() && !sortBy.equalsIgnoreCase("time"))
            ? sortEntities(filtered, sortBy)
            : filtered;

        int total = sorted.size();
        int from = Math.min(safePage * safeSize, total);
        int to = Math.min(from + safeSize, total);
        List<KnowledgeBaseListItemDTO> items = knowledgeBaseMapper.toListItemDTOList(sorted.subList(from, to));
        return new KnowledgeBasePageDTO(items, total, safePage, safeSize);
    }

    private static boolean matchesStatus(KnowledgeBaseEntity entity, VectorStatus status) {
        return status == null || entity.getVectorStatus() == status;
    }

    private static boolean matchesKeyword(KnowledgeBaseEntity entity, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String needle = keyword.trim().toLowerCase();
        return containsIgnoreCase(entity.getName(), needle)
            || containsIgnoreCase(entity.getOriginalFilename(), needle);
    }

    private static boolean containsIgnoreCase(String value, String lowerCaseNeedle) {
        return value != null && value.toLowerCase().contains(lowerCaseNeedle);
    }

    private static boolean matchesCategory(KnowledgeBaseEntity entity, String category) {
        if (category == null || category.isBlank()) {
            return true;
        }
        String value = entity.getCategory();
        return value != null && (value.equals(category) || value.startsWith(category + "/"));
    }

    /**
     * 按向量化状态获取知识库列表（保持向后兼容）
     */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBasesByStatus(VectorStatus vectorStatus) {
        return listKnowledgeBases(vectorStatus, null);
    }

    /**
     * 根据ID获取知识库详情
     */
    public Optional<KnowledgeBaseListItemDTO> getKnowledgeBase(Long id) {
        return knowledgeBaseRepository.findById(id)
            .map(knowledgeBaseMapper::toListItemDTO);
    }

    /**
     * 根据ID获取知识库实体（用于删除等操作）
     */
    public Optional<KnowledgeBaseEntity> getKnowledgeBaseEntity(Long id) {
        return knowledgeBaseRepository.findById(id);
    }

    /**
     * 根据ID列表获取知识库名称列表
     */
    public List<String> getKnowledgeBaseNames(List<Long> ids) {
        return ids.stream()
            .map(id -> knowledgeBaseRepository.findById(id)
                .map(KnowledgeBaseEntity::getName)
                .orElse("未知知识库"))
            .toList();
    }

    // ========== 分类管理 ==========

    /**
     * 获取所有分类
     */
    public List<String> getAllCategories() {
        return knowledgeBaseRepository.findAllCategories();
    }

    /**
     * 获取分类树（可递归，支持任意层级）
     *
     * <p>category 约定为 "一级/二级[/三级]"（斜杠分隔），逐级登记完整路径：
     * "ai/agent/rag" 会生成 ai → ai/agent → ai/agent/rag 三层节点，
     * 中间层级即使没有知识库直接归属也会作为可选节点出现。
     */
    public List<CategoryTreeNode> getCategoryTree() {
        Map<String, Set<String>> childrenByParent = new LinkedHashMap<>();
        for (String category : knowledgeBaseRepository.findAllCategories()) {
            String[] segments = category.split("/");
            StringBuilder path = new StringBuilder();
            String parent = ROOT_CATEGORY_KEY;
            for (String segment : segments) {
                if (path.length() > 0) {
                    path.append("/");
                }
                path.append(segment);
                String node = path.toString();
                childrenByParent.computeIfAbsent(parent, k -> new LinkedHashSet<>()).add(node);
                // 叶子节点也要登记，保证后续能作为父节点被查询（无子节点时返回空）
                childrenByParent.computeIfAbsent(node, k -> new LinkedHashSet<>());
                parent = node;
            }
        }
        return buildCategoryTree(ROOT_CATEGORY_KEY, childrenByParent);
    }

    private List<CategoryTreeNode> buildCategoryTree(String parent, Map<String, Set<String>> childrenByParent) {
        return childrenByParent.getOrDefault(parent, Set.of()).stream()
            .map(node -> new CategoryTreeNode(node, buildCategoryTree(node, childrenByParent)))
            .toList();
    }

    /**
     * 根据分类获取知识库列表
     * 一级分类（"ai"）会连同其下全部二级分类（"ai/agent"、"ai/rag"）一起命中；二级分类精确匹配
     */
    public List<KnowledgeBaseListItemDTO> listByCategory(String category) {
        List<KnowledgeBaseEntity> entities;
        if (category == null || category.isBlank()) {
            entities = knowledgeBaseRepository.findByCategoryIsNullOrderByUploadedAtDesc();
        } else {
            List<String> matchedCategories = knowledgeBaseRepository.findAllCategories().stream()
                .filter(c -> c.equals(category) || c.startsWith(category + "/"))
                .toList();
            entities = matchedCategories.isEmpty()
                ? List.of()
                : knowledgeBaseRepository.findByCategoryInOrderByUploadedAtDesc(matchedCategories);
        }
        return knowledgeBaseMapper.toListItemDTOList(entities);
    }

    /**
     * 更新知识库分类
     */
    @Transactional
    public void updateCategory(Long id, String category) {
        KnowledgeBaseEntity entity = knowledgeBaseRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));
        entity.setCategory(category != null && !category.isBlank() ? category : null);
        knowledgeBaseRepository.save(entity);
        log.info("更新知识库分类: id={}, category={}", id, category);
    }

    /**
     * 批量更新知识库分类
     *
     * @param ids 知识库ID列表（非空）
     * @param category 目标分类，空白表示设为未分类
     * @return 更新的行数
     */
    @Transactional
    public int updateCategoryBatch(List<Long> ids, String category) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择要更新的知识库");
        }
        String normalized = category != null && !category.isBlank() ? category.trim() : null;
        int updated = knowledgeBaseRepository.updateCategoryBatch(ids, normalized);
        log.info("批量更新知识库分类: count={}, category={}", updated, normalized);
        return updated;
    }

    // ========== 搜索功能 ==========

    /**
     * 按关键词搜索知识库
     */
    public List<KnowledgeBaseListItemDTO> search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return listKnowledgeBases();
        }
        return knowledgeBaseMapper.toListItemDTOList(
            knowledgeBaseRepository.searchByKeyword(keyword.trim())
        );
    }

    // ========== 排序功能 ==========

    /**
     * 按指定字段排序获取知识库列表（保持向后兼容）
     */
    public List<KnowledgeBaseListItemDTO> listSorted(String sortBy) {
        return listKnowledgeBases(null, sortBy);
    }

    /**
     * 在内存中对实体列表排序
     *
     * <p>排序稳定，同值条目保持传入顺序（即默认的上传时间倒序）。
     */
    private List<KnowledgeBaseEntity> sortEntities(List<KnowledgeBaseEntity> entities, String sortBy) {
        return switch (sortBy.toLowerCase()) {
            case "size" -> entities.stream()
                .sorted((a, b) -> Long.compare(b.getFileSize(), a.getFileSize()))
                .toList();
            case "access" -> entities.stream()
                .sorted((a, b) -> Integer.compare(b.getAccessCount(), a.getAccessCount()))
                .toList();
            case "question" -> entities.stream()
                .sorted((a, b) -> Integer.compare(b.getQuestionCount(), a.getQuestionCount()))
                .toList();
            // 按状态排序：需要关注的排前面（失败 → 处理中 → 待处理 → 已完成）
            case "status" -> entities.stream()
                .sorted(Comparator.comparingInt(e -> statusOrder(e.getVectorStatus())))
                .toList();
            default -> entities; // time 已经在数据库层面排序了
        };
    }

    /**
     * 状态排序权重：越需要关注的值越小
     */
    private static int statusOrder(VectorStatus status) {
        if (status == null) {
            return 4;
        }
        return switch (status) {
            case FAILED -> 0;
            case PROCESSING -> 1;
            case PENDING -> 2;
            case COMPLETED -> 3;
        };
    }

    // ========== 统计功能 ==========

    /**
     * 获取知识库统计信息
     * 总提问次数从用户消息数统计，确保多知识库提问只算一次
     */
    public KnowledgeBaseStatsDTO getStatistics() {
        return new KnowledgeBaseStatsDTO(
            knowledgeBaseRepository.count(),
            ragChatMessageRepository.countByType(MessageType.USER),  // 真正的提问次数
            knowledgeBaseRepository.sumAccessCount(),
            knowledgeBaseRepository.countByVectorStatus(VectorStatus.COMPLETED),
            knowledgeBaseRepository.countByVectorStatus(VectorStatus.PROCESSING)
        );
    }

    // ========== 下载功能 ==========

    /**
     * 下载知识库文件
     */
    public byte[] downloadFile(Long id) {
        KnowledgeBaseEntity entity = knowledgeBaseRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));

        String storageKey = entity.getStorageKey();
        if (storageKey == null || storageKey.isBlank()) {
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件存储信息不存在");
        }

        log.info("下载知识库文件: id={}, filename={}", id, entity.getOriginalFilename());
        return fileStorageService.downloadFile(storageKey);
    }

    /**
     * 获取知识库文件信息（用于下载）
     */
    public KnowledgeBaseEntity getEntityForDownload(Long id) {
        return knowledgeBaseRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));
    }
}
