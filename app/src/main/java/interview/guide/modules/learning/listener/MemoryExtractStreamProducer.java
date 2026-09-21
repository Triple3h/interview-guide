package interview.guide.modules.learning.listener;

import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 学习对话结束后的个人记忆抽取任务生产者。
 */
@Slf4j
@Component
public class MemoryExtractStreamProducer
    extends AbstractStreamProducer<MemoryExtractStreamProducer.ExtractTaskPayload> {

    public record ExtractTaskPayload(Long userId, Long sessionId, Long messageId, int retryCount) {}

    public MemoryExtractStreamProducer(RedisService redisService) {
        super(redisService);
    }

    public boolean sendExtractTask(Long userId, Long sessionId, Long messageId) {
        return sendExtractTask(userId, sessionId, messageId, 0);
    }

    public boolean sendExtractTask(Long userId, Long sessionId, Long messageId, int retryCount) {
        return sendTask(new ExtractTaskPayload(userId, sessionId, messageId, retryCount));
    }

    @Override
    protected String taskDisplayName() {
        return "记忆抽取";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.LEARNING_MEMORY_EXTRACT_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(ExtractTaskPayload payload) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_USER_ID, payload.userId().toString(),
            AsyncTaskStreamConstants.FIELD_SESSION_ID, payload.sessionId().toString(),
            AsyncTaskStreamConstants.FIELD_MESSAGE_ID, payload.messageId().toString(),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(payload.retryCount())
        );
    }

    @Override
    protected String payloadIdentifier(ExtractTaskPayload payload) {
        return "userId=" + payload.userId()
            + ", sessionId=" + payload.sessionId()
            + ", messageId=" + payload.messageId();
    }

    @Override
    protected void onSendFailed(ExtractTaskPayload payload, String error) {
        log.warn("记忆抽取入队失败，不影响已完成的回答: {}", payloadIdentifier(payload));
    }
}
