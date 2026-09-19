package interview.guide.modules.auth.config;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoForRedisson;
import cn.dev33.satoken.interceptor.SaInterceptor;
import interview.guide.common.web.CurrentUserArgumentResolver;
import interview.guide.modules.auth.StpUserUtil;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 鉴权装配：密码哈希 Bean + 登录态拦截器
 *
 * <p>全站只有一套登录态：学员与管理员走同一个 /api/auth/login 入口，
 * /api/admin/** 同样只要求「已登录」，具体能做什么由角色决定
 * （见 AdminUserService 里的 requirePermission：查看需管理员、改角色/删号/重置密码需超级管理员）。
 * 过渡期内（app.auth.legacy-header-enabled=true）允许旧 X-User-Id 请求头兜底。</p>
 */
@Configuration
@RequiredArgsConstructor
public class AuthWebConfig implements WebMvcConfigurer {

    private final AuthProperties authProperties;

    /**
     * BCrypt 密码哈希：仅引入 spring-security-crypto，不引 Spring Security 全家桶
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * SA-Token 会话存储：复用业务 RedissonClient（sa-token-redisson 为纯 DAO 插件，需手动注册）。
     * 不用 RedisTemplate 系插件：Redisson 4.x 的 Spring Data 适配层在 set(key,value,ttl) 上会递归溢出。
     */
    @Bean
    public SaTokenDao saTokenDao(RedissonClient redissonClient) {
        SaTokenDao dao = new SaTokenDaoForRedisson(redissonClient);
        SaManager.setSaTokenDao(dao);
        return dao;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> checkUserLogin()))
            .addPathPatterns("/api/**")
            .excludePathPatterns("/api/auth/login");
    }

    private void checkUserLogin() {
        if (StpUserUtil.isLogin()) {
            return;
        }
        if (authProperties.isLegacyHeaderEnabled() && hasLegacyUserHeader()) {
            return;
        }
        StpUserUtil.checkLogin();
    }

    /**
     * 过渡期兼容：请求携带旧版 X-User-Id 头时放行，由 CurrentUserArgumentResolver 负责校验
     */
    private boolean hasLegacyUserHeader() {
        ServletRequestAttributes attributes =
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return false;
        }
        String raw = attributes.getRequest().getHeader(CurrentUserArgumentResolver.USER_ID_HEADER);
        return raw != null && !raw.isBlank();
    }
}
