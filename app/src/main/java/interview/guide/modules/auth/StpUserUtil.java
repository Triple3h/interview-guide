package interview.guide.modules.auth;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpLogic;

/**
 * 学员账号体系（loginType = user）
 *
 * <p>与管理端体系 {@link StpAdminUtil} 完全隔离：两者即使共用同一请求头名，
 * 也各自在自己的 loginType 空间下解析 token，互不影响。</p>
 */
public final class StpUserUtil {

    public static final String TYPE = "user";

    public static final StpLogic stpLogic = new StpLogic(TYPE);

    private StpUserUtil() {
    }

    public static void login(Object id) {
        stpLogic.login(id);
    }

    public static void logout() {
        stpLogic.logout();
    }

    /**
     * 踢人下线（后台禁用学员等场景）
     */
    public static void kickout(Object loginId) {
        stpLogic.kickout(loginId);
    }

    public static boolean isLogin() {
        return stpLogic.isLogin();
    }

    public static void checkLogin() {
        stpLogic.checkLogin();
    }

    public static long getLoginIdAsLong() {
        return stpLogic.getLoginIdAsLong();
    }

    public static String getTokenValue() {
        return stpLogic.getTokenValue();
    }

    /**
     * 通过 token 值解析登录用户 ID（WebSocket 握手等非 MVC 场景使用）；无效/过期返回 null
     */
    public static Long getLoginIdByTokenOrNull(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Object loginId = stpLogic.getLoginIdByToken(token);
            if (loginId == null) {
                return null;
            }
            return Long.parseLong(loginId.toString());
        } catch (Exception e) {
            return null;
        }
    }

    public static SaTokenInfo getTokenInfo() {
        return stpLogic.getTokenInfo();
    }
}
