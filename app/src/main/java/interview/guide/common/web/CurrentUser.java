package interview.guide.common.web;

/**
 * 当前登录人（极简选人模式）
 * 由 {@link CurrentUserArgumentResolver} 从 X-User-Id 请求头解析而来
 */
public record CurrentUser(Long id, String nickname) {
}
