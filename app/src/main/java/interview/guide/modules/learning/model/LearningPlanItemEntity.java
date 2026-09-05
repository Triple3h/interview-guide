package interview.guide.modules.learning.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 学习计划条目实体
 * 由 Agent 与学员对话商定后固化（也可手动维护），回答"接下来学什么"
 */
@Entity
@Table(name = "learning_plan_items", indexes = {
    @Index(name = "idx_learning_plan_user", columnList = "user_id"),
    @Index(name = "idx_learning_plan_topic", columnList = "user_id, topic")
})
@Getter
@Setter
@NoArgsConstructor
public class LearningPlanItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 归属学习成员
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 计划主题（同一成员内按主题去重更新）
     */
    @Column(nullable = false, length = 200)
    private String topic;

    /**
     * 一句话说清为什么学 / 学到什么程度
     */
    @Column(columnDefinition = "TEXT")
    private String goal;

    /**
     * 状态
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    /**
     * 计划顺序（小的在前）
     */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /**
     * 来源会话（可追溯计划是在哪次对话商定的）
     */
    @Column(name = "source_session_id")
    private Long sourceSessionId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public enum Status {
        /** 还没开始 */
        PENDING("待开始"),
        /** 正在学 */
        IN_PROGRESS("进行中"),
        /** 已完成 */
        DONE("已完成");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        /**
         * 宽松解析：接受枚举名或中文标签，空值默认 PENDING，供 Agent 工具入参容错
         */
        public static Status parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return PENDING;
            }
            String value = raw.trim();
            for (Status s : values()) {
                if (s.name().equalsIgnoreCase(value) || s.label.equals(value)) {
                    return s;
                }
            }
            throw new IllegalArgumentException("状态必须是 PENDING(待开始)/IN_PROGRESS(进行中)/DONE(已完成) 之一: " + value);
        }
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
