package interview.guide.modules.voiceinterview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 语音面试会话响应
 *
 * <p>WebSocket 地址由前端按当前站点地址拼装并附登录 token（浏览器 WS 无法自定义请求头），
 * 服务端不再下发 URL。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionResponseDTO {
    private Long sessionId;
    private String roleType;
    private String currentPhase;
    private String status;
    private LocalDateTime startTime;
    private Integer plannedDuration;
}
