package interview.guide.modules.knowledgebase.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 知识库向量化节流配置
 *
 * <p>向量化消费者是单线程顺序消费（一次只处理一个知识库），但请求之间若不留间隔，
 * 短时间内连续调用 embedding 接口仍会触发供应商 QPS 限流
 * （DashScope 返回 429 Requests are too frequent），导致整批文档向量化失败。
 * 这里统一控制「每个请求之间等多久」与「被限流后等多久再试」。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.knowledgebase.vectorize")
public class KnowledgeBaseVectorizeProperties {

    /**
     * 两次 embedding 请求之间的最小间隔（毫秒），0 表示不限制。
     * 实测 500ms（约 2 QPS）在 DashScope 免费额度下仍会偶发 429，默认放缓到 1.5s（约 40 RPM）。
     */
    private long requestIntervalMillis = 1500;

    /** 单批次遇到限流后的最大重试次数 */
    private int rateLimitMaxRetries = 3;

    /** 限流退避基数（毫秒），按重试次数翻倍：3s → 6s → 12s */
    private long retryBackoffMillis = 3000;

    /**
     * 第 attempt 次重试前的退避时长（毫秒，attempt 从 0 开始），按次数翻倍并封顶 32 倍
     */
    public long retryDelayMillis(int attempt) {
        long base = Math.max(0L, retryBackoffMillis);
        return base << Math.min(Math.max(attempt, 0), 5);
    }
}
