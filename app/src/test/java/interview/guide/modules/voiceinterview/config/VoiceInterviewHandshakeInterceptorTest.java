package interview.guide.modules.voiceinterview.config;

import interview.guide.modules.auth.config.AuthProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("语音面试 WebSocket 握手鉴权")
class VoiceInterviewHandshakeInterceptorTest {

  @Mock
  private ServerHttpRequest request;

  @Mock
  private ServerHttpResponse response;

  @Mock
  private WebSocketHandler wsHandler;

  @Test
  @DisplayName("过渡期无 token 允许连接（不带归属信息）")
  void shouldAllowLegacyConnectionWithoutToken() {
    VoiceInterviewHandshakeInterceptor interceptor = newInterceptor(true);
    when(request.getURI()).thenReturn(URI.create("/ws/voice-interview/1"));
    Map<String, Object> attributes = new HashMap<>();

    boolean allowed = interceptor.beforeHandshake(request, response, wsHandler, attributes);

    assertThat(allowed).isTrue();
    assertThat(attributes).doesNotContainKey(VoiceInterviewHandshakeInterceptor.USER_ID_ATTRIBUTE);
    verify(response, never()).setStatusCode(org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("关闭过渡开关后无 token 拒绝握手（401）")
  void shouldRejectConnectionWithoutTokenWhenLegacyDisabled() {
    VoiceInterviewHandshakeInterceptor interceptor = newInterceptor(false);
    when(request.getURI()).thenReturn(URI.create("/ws/voice-interview/1"));

    boolean allowed = interceptor.beforeHandshake(request, response, wsHandler, new HashMap<>());

    assertThat(allowed).isFalse();
    verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
  }

  @Test
  @DisplayName("token 无效时拒绝握手（401）")
  void shouldRejectConnectionWithInvalidToken() {
    VoiceInterviewHandshakeInterceptor interceptor = newInterceptor(true);
    when(request.getURI())
        .thenReturn(URI.create("/ws/voice-interview/1?token=not-a-real-token"));

    boolean allowed = interceptor.beforeHandshake(request, response, wsHandler, new HashMap<>());

    assertThat(allowed).isFalse();
    verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
  }

  private VoiceInterviewHandshakeInterceptor newInterceptor(boolean legacyEnabled) {
    AuthProperties authProperties = new AuthProperties();
    authProperties.setLegacyHeaderEnabled(legacyEnabled);
    return new VoiceInterviewHandshakeInterceptor(authProperties);
  }
}
