package interview.guide.modules.auth;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpLogic;

/**
 * 管理端账号体系（loginType = admin）
 *
 * <p>管理员不是业务用户：登录凭证是 .env 中配置的固定 Token（APP_ADMIN_TOKEN），
 * 登录成功后在 admin 体系登记一个虚拟账号，与学员体系完全隔离。</p>
 */
public final class StpAdminUtil {

    public static final String TYPE = "admin";

    /** 管理端虚拟登录账号 id */
    public static final String ADMIN_LOGIN_ID = "admin";

    public static final StpLogic stpLogic = new StpLogic(TYPE);

    private StpAdminUtil() {
    }

    public static void login(Object id) {
        stpLogic.login(id);
    }

    public static void logout() {
        stpLogic.logout();
    }

    public static boolean isLogin() {
        return stpLogic.isLogin();
    }

    public static void checkLogin() {
        stpLogic.checkLogin();
    }

    public static String getTokenValue() {
        return stpLogic.getTokenValue();
    }

    public static SaTokenInfo getTokenInfo() {
        return stpLogic.getTokenInfo();
    }
}
