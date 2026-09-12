package interview.guide.common.web;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.StpUserUtil;
import interview.guide.modules.auth.config.AuthProperties;
import interview.guide.modules.user.model.UserEntity;
import interview.guide.modules.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 解析当前登录学员（@LoginUser CurrentUser）
 *
 * <p>优先取学员体系（SA-Token）登录态；过渡期内允许旧版 X-User-Id 请求头兜底
 * （app.auth.legacy-header-enabled=true 时生效，前后端切换完成后关闭）。
 * 解析后统一查库校验账号存在且未被禁用，禁用后已登录会话立即失效。</p>
 */
@RequiredArgsConstructor
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    /**
     * 旧版选人模式的请求头（过渡期兼容用）
     */
    public static final String USER_ID_HEADER = "X-User-Id";

    private final UserRepository userRepository;
    private final AuthProperties authProperties;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(LoginUser.class)
            && parameter.getParameterType().equals(CurrentUser.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        Long userId = resolveUserId(webRequest);

        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "用户不存在，请重新登录"));

        if (!UserEntity.STATUS_ACTIVE.equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已被禁用，请联系管理员");
        }

        return new CurrentUser(user.getId(), user.getNickname());
    }

    private Long resolveUserId(NativeWebRequest webRequest) {
        if (StpUserUtil.isLogin()) {
            return StpUserUtil.getLoginIdAsLong();
        }

        if (authProperties.isLegacyHeaderEnabled()) {
            String rawUserId = webRequest.getHeader(USER_ID_HEADER);
            if (rawUserId != null && !rawUserId.isBlank()) {
                try {
                    return Long.parseLong(rawUserId.trim());
                } catch (NumberFormatException e) {
                    throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户标识无效，请重新登录");
                }
            }
        }

        throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
    }
}
