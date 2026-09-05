package interview.guide.modules.learning.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 学习记录实体（知识台账）
 * 由 Agent 从对话中自动提炼，也可手动维护，回答"我学过什么"
 */
@Entity
@Table(name = "learning_records", indexes = {
    @Index(name = "idx_learning_record_user", columnList = "user_id"),
    @Index(name = "idx_learning_record_topic", columnList = "user_id, topic")
})
@Getter
@Setter
@NoArgsConstructor
public class LearningRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 归属学习成员
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 知识点主题（同一成员内按主题去重更新）
     */
    @Column(nullable = false, length = 200)
    private String topic;

    /**
     * 学到了什么（1-3 句总结）
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String summary;

    /**
     * 掌握度
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Mastery mastery = Mastery.BEGINNER;

    /**
     * 来源会话（可追溯当时的对话）
     */
    @Column(name = "source_session_id")
    private Long sourceSessionId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /**
     * 最近一次被 Agent 复述/复习的时间
     */
    @Column(name = "last_reviewed_at")
    private LocalDateTime lastReviewedAt;

    public enum Mastery {
        /** 刚接触，有个印象 */
        BEGINNER("初学"),
        /** 理解了核心概念 */
        INTERMEDIATE("理解"),
        /** 能运用/能讲清楚 */
        ADVANCED("熟练");

        private final String label;

        Mastery(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        /**
         * 宽松解析：接受枚举名或中文标签，供 Agent 工具入参容错
         */
        public static Mastery parse(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("掌握度不能为空");
            }
            String value = raw.trim();
            for (Mastery m : values()) {
                if (m.name().equalsIgnoreCase(value) || m.label.equals(value)) {
                    return m;
                }
            }
            throw new IllegalArgumentException("掌握度必须是 BEGINNER(初学)/INTERMEDIATE(理解)/ADVANCED(熟练) 之一: " + value);
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
