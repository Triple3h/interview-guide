package interview.guide.common.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;

/**
 * SSE 事件统一构建器
 *
 * 全站 SSE 事件约定：
 * 1. 类型化事件：event 名 = 事件类型，data 为单行 JSON（Jackson 会转义换行，不会破坏 SSE 帧）
 * 2. 纯文本流：data 为文本分片，换行转义为字面 \n，由前端按行拼接还原
 * 3. 错误契约：event 名为 "error"，data 为 {"type":"error","message":...}，
 *    前端 streamSse 收到后统一走 onError 回调
 */
@Component
@RequiredArgsConstructor
public class SseEventWriter {

    private final ObjectMapper objectMapper;

    /**
     * 类型化事件：event 名 + 单行 JSON data
     */
    public <T> ServerSentEvent<String> typed(String eventName, T payload) {
        return ServerSentEvent.<String>builder()
            .event(eventName)
            .data(writeJson(payload))
            .build();
    }

    /**
     * 纯文本分片事件（旧版 RAG 流协议，换行转义为字面 \n）
     */
    public ServerSentEvent<String> text(String chunk) {
        return ServerSentEvent.<String>builder()
            .data(chunk.replace("\n", "\\n").replace("\r", "\\r"))
            .build();
    }

    public String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "SSE 事件序列化失败");
        }
    }
}
