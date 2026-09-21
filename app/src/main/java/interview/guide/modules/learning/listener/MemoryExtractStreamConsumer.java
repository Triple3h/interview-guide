package interview.guide.modules.learning.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.learning.service.LearningMemoryExtractService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 学习个人记忆抽取 Stream 消费者。
 */
@Slf4j
@Component
public class MemoryExtractStreamConsumer
    extends AbstractStreamConsumer<MemoryExtractStreamConsumer.ExtractPayload> {

    /**
     * 单次抽取总时长上限：正常几秒到几十秒。
     * 60 秒会把「慢但成功」的调用误判成超时（重试还会堆在单线程 worker 后面），
     * 3 分钟覆盖慢调用，仍远小于 PENDING 回收阈值（5 分钟）
     */
    private static final long EXECUTION_TIMEOUT_MILLIS = 3 * 60 * 1000L;

    private final LearningMemoryExtractService extractService;
    private final MemoryExtractStreamProducer producer;

    public record ExtractPayload(Long userId, Long sessionId, Long messageId) {}

    public MemoryExtractStreamConsumer(RedisService redisService,
                                       LearningMemoryExtractService extractService,
                                       MemoryExtractStreamProducer producer) {
        super(redisService);
        this.extractService = extractService;
        this.producer = producer;
    }

    @Override
    protected long executionTimeoutMillis() {
        return EXECUTION_TIMEOUT_MILLIS;
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
    protected String groupName() {
        return AsyncTaskStreamConstants.LEARNING_MEMORY_EXTRACT_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.LEARNING_MEMORY_EXTRACT_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "memory-extract-consumer";
    }

    @Override
    protected ExtractPayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String userId = data.get(AsyncTaskStreamConstants.FIELD_USER_ID);
        String sessionId = data.get(AsyncTaskStreamConstants.FIELD_SESSION_ID);
        String chatMessageId = data.get(AsyncTaskStreamConstants.FIELD_MESSAGE_ID);
        if (userId == null || sessionId == null || chatMessageId == null) {
            log.warn("记忆抽取消息格式错误，丢弃: messageId={}", messageId);
            return null;
        }
        return new ExtractPayload(Long.parseLong(userId), Long.parseLong(sessionId), Long.parseLong(chatMessageId));
    }

    @Override
    protected String payloadIdentifier(ExtractPayload payload) {
        return "userId=" + payload.userId()
            + ", sessionId=" + payload.sessionId()
            + ", messageId=" + payload.messageId();
    }

    @Override
    protected void markProcessing(ExtractPayload payload) {
        // 无独立任务状态表：抽取失败由 Stream 重试，成功则静默落库
    }

    @Override
    protected void processBusiness(ExtractPayload payload) {
        extractService.extractFromCompletedTurn(payload.userId(), payload.sessionId(), payload.messageId());
    }

    @Override
    protected void markCompleted(ExtractPayload payload) {
        // 落库已在 extractService 内完成
    }

    @Override
    protected void markFailed(ExtractPayload payload, String error) {
        log.warn("记忆抽取最终失败: {}, error={}", payloadIdentifier(payload), error);
    }

    @Override
    protected void retryMessage(ExtractPayload payload, int retryCount) {
        producer.sendExtractTask(payload.userId(), payload.sessionId(), payload.messageId(), retryCount);
    }
}
