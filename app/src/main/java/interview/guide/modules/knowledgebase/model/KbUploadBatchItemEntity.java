package interview.guide.modules.knowledgebase.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 知识库批量上传批次明细实体
 * 记录批次内每个文件的处理结果，解析进度与向量化链路联动更新
 */
@Entity
@Table(name = "knowledge_base_upload_batch_items", indexes = {
    @Index(name = "idx_kb_batch_items_batch", columnList = "batch_id"),
    @Index(name = "idx_kb_batch_items_kb", columnList = "kb_id")
})
public class KbUploadBatchItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 所属批次ID
    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    // 关联的知识库ID（REJECTED/DUPLICATE_SKIPPED 时可为空或指向已有知识库）
    @Column(name = "kb_id")
    private Long kbId;

    // 原始文件名
    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;

    // 文件夹上传时的相对路径（展示用）
    @Column(name = "relative_path", length = 1000)
    private String relativePath;

    // 分类（文件夹上传时来自第一级子文件夹名）
    @Column(length = 100)
    private String category;

    // 文件大小（字节）
    @Column(name = "file_size")
    private Long fileSize;

    // 明细状态
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private KbBatchItemStatus status = KbBatchItemStatus.PENDING;

    // 失败或拒绝原因
    @Column(length = 500)
    private String error;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    public void touchUpdatedAt() {
        this.updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public Long getKbId() {
        return kbId;
    }

    public void setKbId(Long kbId) {
        this.kbId = kbId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public KbBatchItemStatus getStatus() {
        return status;
    }

    public void setStatus(KbBatchItemStatus status) {
        this.status = status;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
