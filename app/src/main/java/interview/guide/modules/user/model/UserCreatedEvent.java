package interview.guide.modules.user.model;

/**
 * 用户创建完成事件
 * 供其他模块做初始化（如把无归属的存量 RAG 会话划给第一位成员）
 */
public record UserCreatedEvent(Long userId) {
}
