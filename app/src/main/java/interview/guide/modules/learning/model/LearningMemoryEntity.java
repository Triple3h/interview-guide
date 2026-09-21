package interview.guide.modules.learning.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 学员个人记忆：偏好、提过的问题、易错点、习惯等。
 * 由对话结束后异步抽取，也可手动维护；按 userId 隔离，跨会话可用。
 */
@Entity
@Table(name = "learning_memories", indexes = {
    @Index(name = "idx_learning_memory_user", columnList = "user_id"),
    @Index(name = "idx_learning_memory_kind", columnList = "user_id, kind"),
    @Index(name = "idx_learning_memory_source_message", columnList = "user_id, source_message_id")
})
@Getter
@Setter
@NoArgsConstructor
public class LearningMemoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind = Kind.NOTE;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "source_session_id")
    private Long sourceSessionId;

    @Column(name = "source_message_id")
    private Long sourceMessageId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public enum Kind {
        PREFERENCE("偏好"),
        QUESTION("提问"),
        MISCONCEPTION("易错点"),
        HABIT("习惯"),
        NOTE("其他");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        /**
         * 宽松解析：接受枚举名或中文标签，供 Agent 抽取与手工入参容错
         */
        public static Kind parse(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("记忆类型不能为空");
            }
            String value = raw.trim();
            for (Kind kind : values()) {
                if (kind.name().equalsIgnoreCase(value) || kind.label.equals(value)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException(
                "记忆类型必须是 PREFERENCE(偏好)/QUESTION(提问)/MISCONCEPTION(易错点)/HABIT(习惯)/NOTE(其他) 之一: "
                    + value);
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
