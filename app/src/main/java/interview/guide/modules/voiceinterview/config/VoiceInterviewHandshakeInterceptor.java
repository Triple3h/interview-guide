package interview.guide.modules.voiceinterview.config;

import interview.guide.modules.auth.StpUserUtil;
import interview.guide.modules.auth.config.AuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 语音面试 WebSocket 握手鉴权。
 *
 * <p>浏览器 WebSocket API 无法自定义请求头，因此学员 token 通过查询参数 {@code ?token=} 传递：
 * {@code /ws/voice-interview/{sessionId}?token=xxx}。握手通过后把登录用户 ID 写入
 * {@link org.springframework.web.socket.WebSocketSession} 的 attributes，供后续会话归属校验使用。</p>
 *
 * <p>过渡期（{@code app.auth.legacy-header-enabled=true}）允许无 token 连接，此时不携带归属信息，
 * 仅能完成握手、不做会话归属校验；前后端全部切到账号体系后关闭开关即强制携带 token。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VoiceInterviewHandshakeInterceptor implements HandshakeInterceptor {

    /**
     * 握手鉴权查询参数名（与前端约定）
     */
    public static final String TOKEN_PARAM = "token";

    /**
     * 握手成功后写入 WebSocketSession attributes 的登录用户 ID 键
     */
    public static final String USER_ID_ATTRIBUTE = "userId";

    private final AuthProperties authProperties;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = extractToken(request.getURI());
        if (token != null) {
            Long userId = StpUserUtil.getLoginIdByTokenOrNull(token);
            if (userId == null) {
                log.warn("语音面试 WebSocket 握手 token 无效: path={}", request.getURI().getPath());
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            attributes.put(USER_ID_ATTRIBUTE, userId);
            return true;
        }

        if (authProperties.isLegacyHeaderEnabled()) {
            log.debug("语音面试 WebSocket 过渡期放行（无 token）: path={}", request.getURI().getPath());
            return true;
        }

        log.warn("语音面试 WebSocket 握手缺少 token: path={}", request.getURI().getPath());
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需处理
    }

    private String extractToken(URI uri) {
        String query = uri.getQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8);
            if (!TOKEN_PARAM.equals(key)) {
                continue;
            }
            String value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            return value.isBlank() ? null : value;
        }
        return null;
    }
}
