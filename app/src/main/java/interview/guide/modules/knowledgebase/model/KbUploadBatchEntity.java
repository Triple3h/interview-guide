package interview.guide.modules.knowledgebase.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 知识库批量上传批次实体
 * 一次批量上传（多选文件或选择文件夹）作为一个解析任务
 */
@Entity
@Table(name = "knowledge_base_upload_batches")
public class KbUploadBatchEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 批次名称（默认"批量上传 yyyy-MM-dd HH:mm"，选择文件夹时可用顶层文件夹名）
    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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
