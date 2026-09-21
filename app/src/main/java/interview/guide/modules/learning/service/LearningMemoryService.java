package interview.guide.modules.learning.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.mapper.LearningMemoryMapper;
import interview.guide.modules.learning.model.LearningMemoryDTO.CreateMemoryRequest;
import interview.guide.modules.learning.model.LearningMemoryDTO.LearningMemoryResponse;
import interview.guide.modules.learning.model.LearningMemoryDTO.MemoryOp;
import interview.guide.modules.learning.model.LearningMemoryDTO.UpdateMemoryRequest;
import interview.guide.modules.learning.model.LearningMemoryEntity;
import interview.guide.modules.learning.model.LearningMemoryEntity.Kind;
import interview.guide.modules.learning.repository.LearningMemoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * 个人记忆服务
 * 手动维护与抽取落库共用同一套读写和归属校验
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningMemoryService {

    private static final int CONTENT_MAX_LENGTH = 500;

    private final LearningMemoryRepository memoryRepository;
    private final LearningMemoryMapper memoryMapper;

    public record ApplyResult(int added, int updated, int deleted) {}

    /**
     * 我的记忆列表（keyword 匹配内容，kind 按类型过滤）
     */
    @Transactional(readOnly = true)
    public List<LearningMemoryResponse> list(Long userId, String keyword, String kind) {
        return memoryMapper.toResponseList(listEntities(userId, keyword, kind));
    }

    /**
     * 记忆实体列表（Agent 工具与抽取对照用）
     */
    @Transactional(readOnly = true)
    public List<LearningMemoryEntity> listEntities(Long userId, String keyword, String kind) {
        List<LearningMemoryEntity> memories = memoryRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        Kind kindFilter = parseKindFilter(kind);
        String needle = keyword == null || keyword.isBlank() ? null : keyword.trim().toLowerCase(Locale.ROOT);
        return memories.stream()
            .filter(memory -> kindFilter == null || memory.getKind() == kindFilter)
            .filter(memory -> needle == null || contains(memory.getContent(), needle)
                || contains(memory.getKind().getLabel(), needle))
            .toList();
    }

    /**
     * 最近记忆（注入 system prompt 与抽取对照用）
     */
    @Transactional(readOnly = true)
    public List<LearningMemoryEntity> recentMemories(Long userId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return memoryRepository.findByUserIdOrderByUpdatedAtDesc(userId, Limit.of(limit));
    }

    @Transactional
    public LearningMemoryResponse create(Long userId, CreateMemoryRequest request) {
        Kind kind = parseKindRequired(request.kind());
        LearningMemoryEntity entity = new LearningMemoryEntity();
        entity.setUserId(userId);
        entity.setKind(kind);
        entity.setContent(normalizeContent(request.content()));
        entity = memoryRepository.save(entity);
        log.info("手动新增个人记忆: userId={}, kind={}", userId, kind);
        return memoryMapper.toResponse(entity);
    }

    @Transactional
    public LearningMemoryResponse update(Long userId, Long id, UpdateMemoryRequest request) {
        LearningMemoryEntity entity = getOwnedMemory(userId, id);
        if (request.kind() != null && !request.kind().isBlank()) {
            entity.setKind(parseKindRequired(request.kind()));
        }
        if (request.content() != null && !request.content().isBlank()) {
            entity.setContent(normalizeContent(request.content()));
        }
        return memoryMapper.toResponse(memoryRepository.save(entity));
    }

    @Transactional
    public void delete(Long userId, Long id) {
        getOwnedMemory(userId, id);
        memoryRepository.deleteById(id);
        log.info("删除个人记忆: userId={}, id={}", userId, id);
    }

    /**
     * 应用抽取结果。同一条助手消息再次抽取时，先清掉它上次写下的记忆再写入，避免重试双份。
     * LLM 调用必须在本方法之外完成。
     */
    @Transactional
    public ApplyResult applyExtractedOperations(Long userId, Long sessionId, Long messageId,
                                                List<MemoryOp> operations) {
        int removed = deleteBySourceMessage(userId, messageId);

        int added = 0;
        int updated = 0;
        int deleted = 0;
        if (operations == null) {
            return new ApplyResult(added, updated, deleted);
        }

        for (MemoryOp op : operations) {
            if (op == null || op.action() == null) {
                continue;
            }
            String action = op.action().trim().toUpperCase(Locale.ROOT);
            switch (action) {
                case "ADD" -> {
                    if (createFromExtract(userId, sessionId, messageId, op)) {
                        added++;
                    }
                }
                case "UPDATE" -> {
                    if (updateFromExtract(userId, op)) {
                        updated++;
                    }
                }
                case "DELETE" -> {
                    if (deleteFromExtract(userId, op)) {
                        deleted++;
                    }
                }
                default -> log.warn("忽略未知记忆操作: userId={}, action={}", userId, op.action());
            }
        }

        log.info("应用记忆抽取: userId={}, messageId={}, removed={}, added={}, updated={}, deleted={}",
            userId, messageId, removed, added, updated, deleted);
        return new ApplyResult(added, updated, deleted);
    }

    private boolean createFromExtract(Long userId, Long sessionId, Long messageId, MemoryOp op) {
        Kind kind;
        String content;
        try {
            kind = parseKindRequired(op.kind());
            content = normalizeContent(op.content());
        } catch (BusinessException e) {
            log.warn("抽取 ADD 入参无效，跳过: userId={}, reason={}", userId, e.getMessage());
            return false;
        }
        LearningMemoryEntity duplicate = findDuplicate(userId, kind, content);
        if (duplicate != null) {
            // 模型看不到全部旧记忆时容易重复 ADD，这里兜底：新内容更完整就地补全，否则整条跳过
            if (content.length() > duplicate.getContent().length()) {
                duplicate.setContent(content);
                memoryRepository.save(duplicate);
            }
            log.info("抽取 ADD 命中已有记忆，未新增: userId={}, id={}", userId, duplicate.getId());
            return false;
        }
        LearningMemoryEntity entity = new LearningMemoryEntity();
        entity.setUserId(userId);
        entity.setKind(kind);
        entity.setContent(content);
        entity.setSourceSessionId(sessionId);
        entity.setSourceMessageId(messageId);
        memoryRepository.save(entity);
        return true;
    }

    private boolean updateFromExtract(Long userId, MemoryOp op) {
        if (op.id() == null) {
            return false;
        }
        boolean kindProvided = op.kind() != null && !op.kind().isBlank();
        boolean contentProvided = op.content() != null && !op.content().isBlank();
        if (!kindProvided && !contentProvided) {
            return false;
        }
        LearningMemoryEntity entity = memoryRepository.findById(op.id()).orElse(null);
        if (entity == null || !entity.getUserId().equals(userId)) {
            return false;
        }
        if (kindProvided) {
            try {
                entity.setKind(parseKindRequired(op.kind()));
            } catch (BusinessException e) {
                log.warn("抽取 UPDATE 类型无效，跳过: userId={}, id={}", userId, op.id());
                return false;
            }
        }
        if (contentProvided) {
            entity.setContent(normalizeContent(op.content()));
        }
        // 来源字段保持最初写入的那条消息：重试时按 source_message_id 清理本消息写入的记忆，
        // 若在这里改写成当前消息，会把只是被更新过的旧记忆一并删掉
        memoryRepository.save(entity);
        return true;
    }

    private boolean deleteFromExtract(Long userId, MemoryOp op) {
        if (op.id() == null) {
            return false;
        }
        LearningMemoryEntity entity = memoryRepository.findById(op.id()).orElse(null);
        if (entity == null || !entity.getUserId().equals(userId)) {
            return false;
        }
        memoryRepository.deleteById(entity.getId());
        return true;
    }

    /**
     * 清掉某条回答写下的记忆（同一条回答重新抽取前调用），返回删除条数
     */
    private int deleteBySourceMessage(Long userId, Long messageId) {
        return (int) memoryRepository.deleteByUserIdAndSourceMessageId(userId, messageId);
    }

    /**
     * 抽取去重兜底：同类型下内容归一化后一致，或一方包含另一方且长度接近，视为同一件事。
     * 只作用于抽取链路，手动新增与编辑不受限制
     */
    private LearningMemoryEntity findDuplicate(Long userId, Kind kind, String content) {
        String needle = normalizeForCompare(content);
        for (LearningMemoryEntity memory : memoryRepository.findByUserIdAndKind(userId, kind)) {
            String existing = normalizeForCompare(memory.getContent());
            if (needle.equals(existing) || isContained(existing, needle)) {
                return memory;
            }
        }
        return null;
    }

    /**
     * 包含关系且长度差不超过 20%（至少 8 字）才算重复，避免把补充说明误判成重复
     */
    private boolean isContained(String existing, String candidate) {
        String longer = existing.length() >= candidate.length() ? existing : candidate;
        String shorter = existing.length() >= candidate.length() ? candidate : existing;
        if (shorter.isEmpty() || !longer.contains(shorter)) {
            return false;
        }
        return longer.length() - shorter.length() <= Math.max(8, shorter.length() / 5);
    }

    private String normalizeForCompare(String content) {
        return content == null ? "" : content.toLowerCase(Locale.ROOT)
            .replaceAll("[\\s\\p{Punct}，。、；：？！…（）「」【】　]+", "");
    }

    private LearningMemoryEntity getOwnedMemory(Long userId, Long id) {
        LearningMemoryEntity entity = memoryRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.LEARNING_MEMORY_NOT_FOUND));
        if (!entity.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.LEARNING_MEMORY_NOT_FOUND);
        }
        return entity;
    }

    private Kind parseKindRequired(String raw) {
        try {
            return Kind.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.LEARNING_MEMORY_KIND_INVALID, e.getMessage());
        }
    }

    private Kind parseKindFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Kind.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.LEARNING_MEMORY_KIND_INVALID, e.getMessage());
        }
    }

    private String normalizeContent(String content) {
        String trimmed = content != null ? content.trim() : "";
        if (trimmed.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "记忆内容不能为空");
        }
        return trimmed.length() > CONTENT_MAX_LENGTH ? trimmed.substring(0, CONTENT_MAX_LENGTH) : trimmed;
    }

    private boolean contains(String value, String lowerNeedle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }
}
