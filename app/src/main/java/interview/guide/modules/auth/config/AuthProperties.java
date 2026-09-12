package interview.guide.modules.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 鉴权配置（app.auth）
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    /**
     * 管理端固定登录 Token（只放 .env，不得提交仓库）
     */
    private String adminToken = "";

    /**
     * 过渡开关：允许旧版 X-User-Id 请求头兜底（前后端全部切到 SA-Token 后关闭）
     */
    private boolean legacyHeaderEnabled = true;
}
