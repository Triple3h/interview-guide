package interview.guide.modules.user.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 学习成员实体（极简选人模式，无密码无登录态）
 * 前端首次进入选择成员并记住，后续请求携带 X-User-Id
 */
@Entity
@Table(name = "app_users", indexes = {
    @Index(name = "idx_user_nickname", columnList = "nickname")
})
@Getter
@Setter
@NoArgsConstructor
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 昵称（家庭内唯一，用于选人）
     */
    @Column(nullable = false, unique = true, length = 50)
    private String nickname;

    /**
     * 头像 Emoji（选人卡片展示用）
     */
    @Column(length = 8)
    private String avatarEmoji;

    /**
     * 职业（Agent 个性化举例用）
     */
    @Column(length = 100)
    private String occupation;

    /**
     * 学习方向
     */
    @Column(length = 100)
    private String learningDirection;

    /**
     * 当前水平
     */
    @Column(length = 200)
    private String currentLevel;

    /**
     * 学习目标
     */
    @Column(length = 500)
    private String learningGoal;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

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
