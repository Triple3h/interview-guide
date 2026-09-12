package interview.guide.common.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 探针测试（一次性，不是回归测试，验证完可删）。
 * <p>
 * 问题一：Spring AI 的原生「流式 + 工具循环」能否聚合方舟 deepseek 的流式工具调用分片
 * （历史注记说会抛 toolName cannot be null or empty，故学习帮手改用了手写非流式 ReAct 循环）。
 * <p>
 * 问题二：流式路径下 reasoning_content（思维链）会不会出现在 AssistantMessage metadata 里——
 * 决定「改用原生流式」之后能不能继续做真流式思维链展示。
 * <p>
 * 运行（未设置 ARK_API_KEY 时自动跳过）：
 * <pre>
 * ARK_API_KEY=ark-xxx ./gradlew :app:cleanTest :app:test \
 *   --tests "interview.guide.common.ai.ArkStreamingToolProbeTest" --no-daemon --console=plain
 * cat /tmp/ark-stream-tool-probe.txt
 * </pre>
 * 可选环境变量：ARK_BASE（默认 Agent Plan 端点 /api/plan/v3）、ARK_MODEL（默认 deepseek-v4-flash）。
 */
@DisplayName("方舟 deepseek 原生流式探针")
class ArkStreamingToolProbeTest {

    private static final String DEFAULT_BASE_URL = "https://ark.cn-beijing.volces.com/api/plan/v3";
    private static final String DEFAULT_MODEL = "deepseek-v4-flash";
    private static final String TOOL_PROMPT = "调用 get_weather 工具查询北京天气，必须调用工具，不要直接回答";
    private static final String REASONING_PROMPT = "先想清楚再回答：ReAct 和 Plan-and-Execute 的核心区别是什么？";

    @Test
    @DisplayName("A 流式 + 工具循环能否聚合（自动注册 Advisor）")
    void streamingToolLoop() {
        String apiKey = requireApiKey();
        OpenAiChatModel chatModel = buildChatModel(apiKey);

        AtomicInteger toolCalls = new AtomicInteger();
        ProbeRun run = run(client -> client.prompt()
            .user(TOOL_PROMPT)
            .tools(new WeatherProbeTools(toolCalls))
            .stream()
            .chatResponse());

        report(String.join(System.lineSeparator(),
            "[probe] A 流式+工具循环：",
            "        工具执行=" + toolCalls.get() + " 次",
            "        " + run.summary(),
            "        异常=" + (run.error() == null ? "无" : run.error())));

        assertThat(run.error()).as("原生流式工具循环抛异常了").isNull();
        assertThat(toolCalls.get()).as("工具应被真实执行").isGreaterThanOrEqualTo(1);
        assertThat(run.text()).as("应产出非空答案").isNotBlank();
    }

    @Test
    @DisplayName("B 纯流式（无工具）能否拿到 reasoning_content")
    void streamingReasoningVisibility() {
        String apiKey = requireApiKey();
        OpenAiChatModel chatModel = buildChatModel(apiKey);

        ProbeRun run = run(client -> client.prompt()
            .user(REASONING_PROMPT)
            .stream()
            .chatResponse());

        // 基线：非流式路径（学习帮手现在就在用，reasoningContent 一定拿得到）
        ChatResponse sync = ChatClient.builder(chatModel).build()
            .prompt().user(REASONING_PROMPT).call().chatResponse();
        String syncReasoning = reasoning(sync);
        String syncText = sync != null && sync.getResult() != null && sync.getResult().getOutput() != null
            ? sync.getResult().getOutput().getText() : null;

        report(String.join(System.lineSeparator(),
            "[probe] B 纯流式（无工具）：",
            "        " + run.summary(),
            "        reasoning 片段示例=" + abbreviate(run.firstReasoning(), 100),
            "        答案=" + abbreviate(run.text(), 100),
            "[probe] B 基线 非流式：",
            "        reasoning 长度=" + syncReasoning.length() + " 字, 示例=" + abbreviate(syncReasoning, 100),
            "        答案=" + abbreviate(syncText == null ? "" : syncText, 100)));

        assertThat(syncReasoning).as("非流式基线应能拿到 reasoning_content").isNotBlank();
    }

    @Test
    @DisplayName("C 自读流：真流式思维链 + 工具聚合 + reasoning_content 回传")
    void rawStreamClientToolLoop() {
        String apiKey = requireApiKey();
        OpenAiCompatibleStreamClient streamClient = new OpenAiCompatibleStreamClient(new ObjectMapper());
        OpenAiCompatibleStreamClient.Connection connection = new OpenAiCompatibleStreamClient.Connection(
            envOr("ARK_BASE", DEFAULT_BASE_URL), apiKey, envOr("ARK_MODEL", DEFAULT_MODEL), null);

        AtomicInteger toolCalls = new AtomicInteger();
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
            .toolObjects(new WeatherProbeTools(toolCalls))
            .build()
            .getToolCallbacks();
        ToolCallingChatOptions toolOptions = ToolCallingChatOptions.builder().toolCallbacks(callbacks).build();
        ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();
        List<ToolDefinition> toolDefinitions = toolCallingManager.resolveToolDefinitions(toolOptions);

        List<Message> conversation = new ArrayList<>(List.of(
            new SystemMessage("需要外部信息时必须调用工具，不要凭空回答。"),
            new UserMessage(TOOL_PROMPT)));

        List<String> reasoningDeltas = Collections.synchronizedList(new ArrayList<>());
        List<String> contentDeltas = Collections.synchronizedList(new ArrayList<>());
        OpenAiCompatibleStreamClient.StreamedAssistant first = streamClient.streamTurn(
            connection, conversation, toolDefinitions, delta -> {
                if (delta.reasoningContent() != null && !delta.reasoningContent().isEmpty()) {
                    reasoningDeltas.add(delta.reasoningContent());
                }
                if (delta.content() != null && !delta.content().isEmpty()) {
                    contentDeltas.add(delta.content());
                }
            });

        StringBuilder report = new StringBuilder();
        report.append("[probe] C 自读流 第一轮：reasoning 分片=").append(reasoningDeltas.size())
            .append(" 段/").append(String.join("", reasoningDeltas).length()).append(" 字")
            .append(", content 分片=").append(contentDeltas.size())
            .append(", finish=").append(first.finishReason())
            .append(", 工具调用=").append(first.toolCalls().size()).append(" 个");
        for (AssistantMessage.ToolCall call : first.toolCalls()) {
            report.append(System.lineSeparator())
                .append("        toolCall id=").append(call.id())
                .append(" name=").append(call.name())
                .append(" args=").append(call.arguments());
        }

        String round2Error = null;
        String round2Text = "";
        try {
            // 与生产一致：Prompt 只放"模型实际收到的前缀"，assistant 消息由 ToolCallingManager 追加
            ToolExecutionResult execution = toolCallingManager.executeToolCalls(
                new Prompt(List.copyOf(conversation), toolOptions), first.toChatResponse());
            conversation.clear();
            conversation.addAll(execution.conversationHistory());
            round2Text = streamClient.streamTurn(connection, conversation, toolDefinitions, delta -> { }).text();
        } catch (Throwable e) {
            round2Error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        report.append(System.lineSeparator())
            .append("[probe] C 第二轮（reasoning_content 已回传）：工具执行=").append(toolCalls.get()).append(" 次")
            .append(", 异常=").append(round2Error == null ? "无" : round2Error)
            .append(System.lineSeparator())
            .append("        答案=").append(abbreviate(round2Text, 120));
        report(report.toString());

        assertThat(first.hasToolCalls()).as("第一轮应产生工具调用").isTrue();
        assertThat(String.join("", reasoningDeltas)).as("自读流应拿到真流式思维链").isNotBlank();
        assertThat(round2Error).as("第二轮（回传 reasoning_content）不应报错").isNull();
        assertThat(round2Text).as("第二轮应产出答案").isNotBlank();
    }

    private ProbeRun run(java.util.function.Function<ChatClient, reactor.core.publisher.Flux<ChatResponse>> call) {
        ChatClient chatClient = ChatClient.builder(buildChatModel(requireApiKey()))
            .defaultAdvisors(ToolCallingAdvisor.builder()
                .toolCallingManager(ToolCallingManager.builder().build())
                .conversationHistoryEnabled(false)
                .build())
            .build();

        Map<String, String> metaSummary = Collections.synchronizedMap(new LinkedHashMap<>());
        List<String> reasoningChunks = Collections.synchronizedList(new ArrayList<>());
        List<Integer> textLens = Collections.synchronizedList(new ArrayList<>());
        StringBuilder text = new StringBuilder();
        try {
            call.apply(chatClient)
                .doOnNext(response -> collect(response, metaSummary, reasoningChunks, textLens, text))
                .blockLast(Duration.ofMinutes(3));
        } catch (Throwable e) {
            return new ProbeRun(metaSummary, reasoningChunks, textLens, text.toString(),
                e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return new ProbeRun(metaSummary, reasoningChunks, textLens, text.toString(), null);
    }

    private static void collect(ChatResponse response, Map<String, String> metaSummary, List<String> reasoningChunks,
                                List<Integer> textLens, StringBuilder text) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return;
        }
        AssistantMessage output = response.getResult().getOutput();
        if (output.getText() != null) {
            textLens.add(output.getText().length());
            text.append(output.getText());
        }
        Map<String, Object> metadata = output.getMetadata();
        if (metadata == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            Object value = entry.getValue();
            metaSummary.putIfAbsent(entry.getKey(), value == null
                ? "null"
                : value.getClass().getSimpleName() + "(" + String.valueOf(value).length() + "字)");
            if (entry.getKey().toLowerCase().contains("reason") && value != null) {
                String asText = value instanceof String str ? str : String.valueOf(value);
                if (!asText.isBlank()) {
                    reasoningChunks.add(asText);
                }
            }
        }
    }

    private static String reasoning(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        Object value = response.getResult().getOutput().getMetadata() == null
            ? null : response.getResult().getOutput().getMetadata().get("reasoningContent");
        return value instanceof String str ? str : "";
    }

    private record ProbeRun(Map<String, String> metaSummary, List<String> reasoningChunks, List<Integer> textLens,
                            String text, String error) {

        int reasoningChars() {
            return String.join("", reasoningChunks).length();
        }

        String firstReasoning() {
            return reasoningChunks.isEmpty() ? "" : reasoningChunks.get(0);
        }

        String summary() {
            return "流式 chunk 数=" + textLens.size()
                + ", 文本分片长度(前10)=" + textLens.subList(0, Math.min(10, textLens.size()))
                + ", metadata 键=" + metaSummary
                + ", reasoning 分片=" + reasoningChunks.size() + " 段/" + reasoningChars() + " 字";
        }
    }

    private String requireApiKey() {
        String apiKey = System.getenv("ARK_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
            "未设置 ARK_API_KEY，跳过方舟流式探针");
        return apiKey;
    }

    private static OpenAiChatModel buildChatModel(String apiKey) {
        String baseUrl = envOr("ARK_BASE", DEFAULT_BASE_URL);
        String modelName = envOr("ARK_MODEL", DEFAULT_MODEL);
        OpenAIClient openAiClient = ApiPathResolver.buildOpenAiClient(baseUrl, apiKey);
        return OpenAiChatModel.builder()
            .openAiClient(openAiClient)
            .openAiClientAsync(openAiClient.async())
            .options(OpenAiChatOptions.builder().model(modelName).build())
            .build();
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static String envOr(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void report(String content) {
        System.out.println(content);
        try {
            Files.writeString(Path.of("/tmp/ark-stream-tool-probe.txt"),
                content + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("[probe] 写报告文件失败: " + e.getMessage());
        }
    }

    /**
     * 探针工具：只统计调用次数，不做真实业务
     */
    static class WeatherProbeTools {

        private final AtomicInteger counter;

        WeatherProbeTools(AtomicInteger counter) {
            this.counter = counter;
        }

        @Tool(description = "查询指定城市的当前天气")
        public String getWeather(@ToolParam(description = "城市名，如 北京") String city) {
            counter.incrementAndGet();
            return city + "：26℃，晴";
        }
    }
}
