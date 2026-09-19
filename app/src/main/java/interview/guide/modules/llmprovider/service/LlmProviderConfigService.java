package interview.guide.modules.llmprovider.service;

import interview.guide.common.ai.ApiPathResolver;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.llmprovider.dto.AsrConfigDTO;
import interview.guide.modules.llmprovider.dto.AsrConfigRequest;
import interview.guide.modules.llmprovider.dto.CreateProviderRequest;
import interview.guide.modules.llmprovider.dto.DefaultProviderDTO;
import interview.guide.modules.llmprovider.dto.ProviderDTO;
import interview.guide.modules.llmprovider.dto.ProviderTestResult;
import interview.guide.modules.llmprovider.dto.TtsConfigDTO;
import interview.guide.modules.llmprovider.dto.TtsConfigRequest;
import interview.guide.modules.llmprovider.dto.UpdateProviderRequest;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.service.AsrService;
import interview.guide.modules.voiceinterview.service.TtsService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.InetAddressFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class LlmProviderConfigService {

  private final LlmProviderProperties properties;
  private final LlmProviderRegistry registry;
  private final LlmProviderRepository providerRepository;
  private final LlmGlobalSettingRepository globalSettingRepository;
  private final ApiKeyEncryptionService encryptionService;
  private final String yamlPath;
  private final String envPath;
  private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
  private final VoiceInterviewProperties voiceProperties;
  private final AsrService asrService;
  private final TtsService ttsService;

  private static final Map<String, String> RECOMMENDED_EMBEDDING_MODELS = Map.of(
      "dashscope", "text-embedding-v3",
      "glm", "embedding-3",
      "zhipu", "embedding-3",
      "baidu", "Embedding-V1",
      "minimax", "embo-01"
  );

  // ===== 语音服务提供方常量（dashscope 现役；volcengine 配置层已接入，运行时接入中） =====

  private static final String PROVIDER_DASHSCOPE = "dashscope";
  private static final String PROVIDER_VOLCENGINE = "volcengine";
  private static final Set<String> VOICE_PROVIDERS = Set.of(PROVIDER_DASHSCOPE, PROVIDER_VOLCENGINE);
  private static final String VOICE_ENV_KEY_DASHSCOPE = "AI_BAILIAN_API_KEY";
  private static final String VOICE_ENV_KEY_VOLC = "VOLC_AGENT_PLAN_VOICE_API_KEY";
  private static final Set<String> VOLC_ASR_FORMATS = Set.of("pcm", "wav", "ogg", "mp3");
  private static final int VOLC_ASR_SAMPLE_RATE = 16000;
  private static final int VOLC_ASR_BITS = 16;
  private static final Set<String> VOLC_TTS_FORMATS = Set.of("pcm", "mp3", "ogg_opus");
  private static final Set<Integer> VOLC_TTS_SAMPLE_RATES =
      Set.of(8000, 16000, 22050, 24000, 32000, 44100, 48000);
  private static final int VOLC_ASR_MIN_SEGMENT_MS = 100;
  private static final int VOLC_ASR_MAX_SEGMENT_MS = 1000;
  private static final String DASHSCOPE_REALTIME_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime";
  private static final String VOLC_TTS_TEST_TEXT = "你好";

  @Autowired
  public LlmProviderConfigService(
      LlmProviderProperties properties,
      LlmProviderRegistry registry,
      LlmProviderRepository providerRepository,
      LlmGlobalSettingRepository globalSettingRepository,
      ApiKeyEncryptionService encryptionService,
      VoiceInterviewProperties voiceProperties,
      AsrService asrService,
      TtsService ttsService) {
    this.properties = properties;
    this.registry = registry;
    this.providerRepository = providerRepository;
    this.globalSettingRepository = globalSettingRepository;
    this.encryptionService = encryptionService;
    this.yamlPath = properties.getConfigYamlPath();
    this.envPath = properties.getConfigEnvPath();
    this.voiceProperties = voiceProperties;
    this.asrService = asrService;
    this.ttsService = ttsService;
  }

  public LlmProviderConfigService(
      LlmProviderProperties properties,
      LlmProviderRegistry registry,
      VoiceInterviewProperties voiceProperties,
      AsrService asrService,
      TtsService ttsService) {
    this(properties, registry, null, null, null, voiceProperties, asrService, ttsService);
  }

  @PostConstruct
  void validateWritablePaths() {
    ensureParentWritable(yamlPath, "config-yaml-path");
    ensureParentWritable(envPath, "config-env-path");
  }

  private void ensureParentWritable(String rawPath, String label) {
    if (rawPath == null || rawPath.isBlank()) {
      log.warn("{} is not configured; runtime Provider edits will be skipped", label);
      return;
    }
    Path parent = Path.of(rawPath).toAbsolutePath().getParent();
    if (parent == null) {
      return;
    }
    try {
      Files.createDirectories(parent);
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED,
          label + " 的父目录不可创建: " + parent, e);
    }
    if (!Files.isWritable(parent)) {
      throw new BusinessException(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED,
          label + " 的父目录不可写: " + parent);
    }
    log.info("{} resolved to {} (parent writable)", label, rawPath);
  }

  // ===== Read operations (read lock) =====

  public List<ProviderDTO> listProviders() {
    rwLock.readLock().lock();
    try {
      if (!isDatabaseBacked()) {
        Map<String, ProviderConfig> providers = properties.getProviders();
        if (providers == null) return List.of();
        return providers.entrySet().stream()
            .map(e -> ProviderDTO.builder()
                .id(e.getKey())
                .baseUrl(e.getValue().getBaseUrl())
                .maskedApiKey(maskApiKey(e.getValue().getApiKey()))
                .model(e.getValue().getModel())
                .embeddingModel(e.getValue().getEmbeddingModel())
                .embeddingDimensions(resolveEmbeddingDimensions(e.getValue().getEmbeddingDimensions()))
                .supportsEmbedding(Boolean.TRUE.equals(e.getValue().getSupportsEmbedding())
                    || trimOrNull(e.getValue().getEmbeddingModel()) != null)
                .temperature(e.getValue().getTemperature())
                .defaultChatProvider(e.getKey().equals(properties.getDefaultProvider()))
                .defaultEmbeddingProvider(e.getKey().equals(properties.getDefaultEmbeddingProvider()))
                .build())
            .toList();
      }
      LlmGlobalSettingEntity setting = getGlobalSettingOrThrow();
      return providerRepository.findAll().stream()
          .map(provider -> ProviderDTO.builder()
              .id(provider.getId())
              .baseUrl(provider.getBaseUrl())
              .maskedApiKey(maskApiKey(decryptApiKey(provider)))
              .model(provider.getModel())
              .embeddingModel(provider.getEmbeddingModel())
              .embeddingDimensions(resolveEmbeddingDimensions(provider.getEmbeddingDimensions()))
              .supportsEmbedding(provider.isSupportsEmbedding())
              .temperature(provider.getTemperature())
              .defaultChatProvider(provider.getId().equals(setting.getDefaultChatProviderId()))
              .defaultEmbeddingProvider(provider.getId().equals(setting.getDefaultEmbeddingProviderId()))
              .build())
          .toList();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  public ProviderDTO getProvider(String id) {
    rwLock.readLock().lock();
    try {
      if (!isDatabaseBacked()) {
        ProviderConfig config = getLegacyProviderConfigOrThrow(id);
        return ProviderDTO.builder()
            .id(id)
            .baseUrl(config.getBaseUrl())
            .maskedApiKey(maskApiKey(config.getApiKey()))
            .model(config.getModel())
            .embeddingModel(config.getEmbeddingModel())
            .embeddingDimensions(resolveEmbeddingDimensions(config.getEmbeddingDimensions()))
            .supportsEmbedding(Boolean.TRUE.equals(config.getSupportsEmbedding())
                || trimOrNull(config.getEmbeddingModel()) != null)
            .temperature(config.getTemperature())
            .defaultChatProvider(id.equals(properties.getDefaultProvider()))
            .defaultEmbeddingProvider(id.equals(properties.getDefaultEmbeddingProvider()))
            .build();
      }
      LlmGlobalSettingEntity setting = getGlobalSettingOrThrow();
      LlmProviderEntity provider = getProviderEntityOrThrow(id);
      return ProviderDTO.builder()
          .id(id)
          .baseUrl(provider.getBaseUrl())
          .maskedApiKey(maskApiKey(decryptApiKey(provider)))
          .model(provider.getModel())
          .embeddingModel(provider.getEmbeddingModel())
          .embeddingDimensions(resolveEmbeddingDimensions(provider.getEmbeddingDimensions()))
          .supportsEmbedding(provider.isSupportsEmbedding())
          .temperature(provider.getTemperature())
          .defaultChatProvider(id.equals(setting.getDefaultChatProviderId()))
          .defaultEmbeddingProvider(id.equals(setting.getDefaultEmbeddingProviderId()))
          .build();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  public DefaultProviderDTO getDefaultProvider() {
    rwLock.readLock().lock();
    try {
      if (!isDatabaseBacked()) {
        return new DefaultProviderDTO(properties.getDefaultProvider(), properties.getDefaultEmbeddingProvider());
      }
      LlmGlobalSettingEntity setting = getGlobalSettingOrThrow();
      return new DefaultProviderDTO(
          setting.getDefaultChatProviderId(),
          setting.getDefaultEmbeddingProviderId());
    } finally {
      rwLock.readLock().unlock();
    }
  }

  public AsrConfigDTO getAsrConfig() {
    rwLock.readLock().lock();
    try {
      VoiceInterviewProperties.AsrConfig asr = voiceProperties.getQwen().getAsr();
      VoiceInterviewProperties.VolcAsrConfig volcAsr = voiceProperties.getVolc().getAsr();
      return AsrConfigDTO.builder()
          .provider(resolveVoiceProvider(voiceProperties.getAsrProvider()))
          .dashscope(AsrConfigDTO.DashscopeAsrConfig.builder()
              .url(asr.getUrl())
              .model(asr.getModel())
              .maskedApiKey(maskApiKey(asr.getApiKey()))
              .language(asr.getLanguage())
              .format(asr.getFormat())
              .sampleRate(asr.getSampleRate())
              .enableTurnDetection(asr.isEnableTurnDetection())
              .turnDetectionType(asr.getTurnDetectionType())
              .turnDetectionThreshold(asr.getTurnDetectionThreshold())
              .turnDetectionSilenceDurationMs(asr.getTurnDetectionSilenceDurationMs())
              .build())
          .volcengine(AsrConfigDTO.VolcAsrConfig.builder()
              .url(volcAsr.getUrl())
              .resourceId(volcAsr.getResourceId())
              .modelName(volcAsr.getModelName())
              .maskedApiKey(maskApiKey(volcAsr.getApiKey()))
              .format(volcAsr.getFormat())
              .sampleRate(volcAsr.getSampleRate())
              .bits(volcAsr.getBits())
              .channel(volcAsr.getChannel())
              .enableItn(volcAsr.isEnableItn())
              .enablePunc(volcAsr.isEnablePunc())
              .enableDdc(volcAsr.isEnableDdc())
              .enableNonstream(volcAsr.isEnableNonstream())
              .segmentMs(volcAsr.getSegmentMs())
              .build())
          .build();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  public TtsConfigDTO getTtsConfig() {
    rwLock.readLock().lock();
    try {
      VoiceInterviewProperties.QwenTtsConfig tts = voiceProperties.getQwen().getTts();
      VoiceInterviewProperties.VolcTtsConfig volcTts = voiceProperties.getVolc().getTts();
      return TtsConfigDTO.builder()
          .provider(resolveVoiceProvider(voiceProperties.getTtsProvider()))
          .dashscope(TtsConfigDTO.DashscopeTtsConfig.builder()
              .model(tts.getModel())
              .maskedApiKey(maskApiKey(tts.getApiKey()))
              .voice(tts.getVoice())
              .format(tts.getFormat())
              .sampleRate(tts.getSampleRate())
              .mode(tts.getMode())
              .languageType(tts.getLanguageType())
              .speechRate(tts.getSpeechRate())
              .volume(tts.getVolume())
              .build())
          .volcengine(TtsConfigDTO.VolcTtsConfig.builder()
              .url(volcTts.getUrl())
              .resourceId(volcTts.getResourceId())
              .speaker(volcTts.getSpeaker())
              .maskedApiKey(maskApiKey(volcTts.getApiKey()))
              .format(volcTts.getFormat())
              .sampleRate(volcTts.getSampleRate())
              .build())
          .build();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  public ProviderTestResult testProvider(String id) {
    rwLock.readLock().lock();
    try {
      ProviderRuntimeConfig config = isDatabaseBacked()
          ? getProviderRuntimeConfigOrThrow(id)
          : toRuntimeConfig(getLegacyProviderConfigOrThrow(id));
      return doTestProvider(config, id);
    } finally {
      rwLock.readLock().unlock();
    }
  }

  public ProviderTestResult testAsrConfig() {
    rwLock.readLock().lock();
    try {
      String provider = resolveVoiceProvider(voiceProperties.getAsrProvider());
      if (PROVIDER_VOLCENGINE.equals(provider)) {
        return testVolcAsrConnection();
      }
      VoiceInterviewProperties.AsrConfig asr = voiceProperties.getQwen().getAsr();
      return testTcpEndpoint(asr.getUrl(), "DashScope ASR", asr.getModel());
    } finally {
      rwLock.readLock().unlock();
    }
  }

  public ProviderTestResult testTtsConfig() {
    rwLock.readLock().lock();
    try {
      String provider = resolveVoiceProvider(voiceProperties.getTtsProvider());
      if (PROVIDER_VOLCENGINE.equals(provider)) {
        return testVolcTtsConnection();
      }
      VoiceInterviewProperties.QwenTtsConfig tts = voiceProperties.getQwen().getTts();
      return testTcpEndpoint(DASHSCOPE_REALTIME_URL, "DashScope TTS", tts.getModel());
    } finally {
      rwLock.readLock().unlock();
    }
  }

  /** TCP 连通性探活（DashScope 实时语音端点，SDK 无独立可配 URL）。 */
  private ProviderTestResult testTcpEndpoint(String url, String label, String model) {
    try {
      URI uri = URI.create(url);
      String host = uri.getHost();
      int port = uri.getPort() > 0
          ? uri.getPort()
          : ("wss".equals(uri.getScheme()) || "https".equals(uri.getScheme()) ? 443 : 80);
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress(host, port), 5000);
      }
      return ProviderTestResult.builder()
          .success(true)
          .message(label + " 连接成功: " + host)
          .model(model)
          .build();
    } catch (Exception e) {
      return ProviderTestResult.builder()
          .success(false)
          .message(label + " 连接失败: " + e.getMessage())
          .model(model)
          .build();
    }
  }

  /**
   * 火山方舟 TTS 连通性测试：真实合成一小段文本，验证专属 Key + Resource ID + 音色。
   * 走运行时同一套 {@link TtsService}（当前 tts-provider=volcengine）。
   */
  private ProviderTestResult testVolcTtsConnection() {
    VoiceInterviewProperties.VolcTtsConfig tts = voiceProperties.getVolc().getTts();
    if (trimOrNull(tts.getApiKey()) == null) {
      return ProviderTestResult.builder()
          .success(false)
          .message("未配置火山方舟语音 API Key（Agent Plan 专属 API Key）")
          .model(tts.getResourceId())
          .build();
    }
    long startedAt = System.currentTimeMillis();
    byte[] audio = ttsService.synthesize(VOLC_TTS_TEST_TEXT);
    long elapsed = System.currentTimeMillis() - startedAt;
    if (audio != null && audio.length > 0) {
      return ProviderTestResult.builder()
          .success(true)
          .message("火山方舟 TTS 合成成功（" + audio.length + " 字节音频，耗时 " + elapsed + "ms）")
          .model(tts.getResourceId())
          .build();
    }
    return ProviderTestResult.builder()
        .success(false)
        .message("火山方舟 TTS 合成失败：请检查专属 API Key / Resource ID / 音色，详见服务端日志")
        .model(tts.getResourceId())
        .build();
  }

  /**
   * 火山方舟 ASR 连通性测试：带鉴权头做一次 WebSocket 握手后立即关闭，不产生合成/识别调用。
   */
  private ProviderTestResult testVolcAsrConnection() {
    VoiceInterviewProperties.VolcAsrConfig asr = voiceProperties.getVolc().getAsr();
    String apiKey = trimOrNull(asr.getApiKey());
    if (apiKey == null) {
      return ProviderTestResult.builder()
          .success(false)
          .message("未配置火山方舟语音 API Key（Agent Plan 专属 API Key）")
          .model(asr.getModelName())
          .build();
    }
    try {
      HttpClient client = HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .build();
      WebSocket webSocket = client.newWebSocketBuilder()
          .header("X-Api-Key", apiKey)
          .header("X-Api-Resource-Id", asr.getResourceId())
          .header("X-Api-Connect-Id", UUID.randomUUID().toString())
          .buildAsync(URI.create(asr.getUrl()), new WebSocket.Listener() {})
          .get(8, TimeUnit.SECONDS);
      webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "test").join();
      return ProviderTestResult.builder()
          .success(true)
          .message("火山方舟 ASR WebSocket 握手成功: " + asr.getResourceId())
          .model(asr.getModelName())
          .build();
    } catch (ExecutionException e) {
      log.warn("Volc ASR handshake failed: url={}, resourceId={}, error={}",
          asr.getUrl(), asr.getResourceId(), e.getMessage(), e);
      return ProviderTestResult.builder()
          .success(false)
          .message("火山方舟 ASR 连接失败: " + describeVolcHandshakeFailure(e))
          .model(asr.getModelName())
          .build();
    } catch (Exception e) {
      log.warn("Volc ASR handshake failed: url={}, resourceId={}, error={}",
          asr.getUrl(), asr.getResourceId(), e.getMessage(), e);
      return ProviderTestResult.builder()
          .success(false)
          .message("火山方舟 ASR 连接失败: " + e.getMessage())
          .model(asr.getModelName())
          .build();
    }
  }

  private String describeVolcHandshakeFailure(ExecutionException e) {
    Throwable cause = e.getCause() != null ? e.getCause() : e;
    if (cause instanceof WebSocketHandshakeException handshake) {
      int status = handshake.getResponse().statusCode();
      String hint = status == 401 || status == 403
          ? "，请检查 Agent Plan 专属 API Key 与 X-Api-Resource-Id"
          : "";
      return "WebSocket 握手失败 HTTP " + status + hint;
    }
    return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
  }

  // ===== Write operations (write lock) =====

  @Transactional
  public void createProvider(CreateProviderRequest request) {
    rwLock.writeLock().lock();
    try {
      if (!isDatabaseBacked()) {
        createProviderLegacy(request);
        return;
      }
      String providerId = trimOrNull(request.id());
      if (providerRepository.existsById(providerId)) {
        throw new BusinessException(ErrorCode.PROVIDER_ALREADY_EXISTS,
            "Provider '" + request.id() + "' 已存在");
      }
      String baseUrl = requireNonBlank(request.baseUrl(), "baseUrl");
      String model = requireNonBlank(request.model(), "model");
      String apiKey = requireNonBlank(request.apiKey(), "apiKey");
      String embeddingModel = trimOrNull(request.embeddingModel());
      Integer embeddingDimensions = resolveEmbeddingDimensions(request.embeddingDimensions());
      boolean supportsEmbedding = request.supportsEmbedding() != null
          ? request.supportsEmbedding()
          : embeddingModel != null;
      validateEmbeddingConfig(providerId, supportsEmbedding, embeddingModel, embeddingDimensions);

      ApiKeyEncryptionService.EncryptedValue encrypted = encryptionService.encrypt(apiKey);
      providerRepository.save(LlmProviderEntity.builder()
          .id(providerId)
          .baseUrl(baseUrl)
          .apiKeyNonce(encrypted.nonce())
          .apiKeyCiphertext(encrypted.ciphertext())
          .model(model)
          .embeddingModel(embeddingModel)
          .embeddingDimensions(embeddingDimensions)
          .supportsEmbedding(supportsEmbedding)
          .temperature(request.temperature())
          .enabled(true)
          .builtin(false)
          .build());
      registry.reload();
      log.info("Created provider: id={}, baseUrl={}, model={}", providerId, baseUrl, model);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  @Transactional
  public void updateProvider(String id, UpdateProviderRequest request) {
    rwLock.writeLock().lock();
    try {
      if (!isDatabaseBacked()) {
        updateProviderLegacy(id, request);
        return;
      }
      LlmProviderEntity provider = getProviderEntityOrThrow(id);

      String trimmedBaseUrl = trimOrNull(request.baseUrl());
      if (request.baseUrl() != null && trimmedBaseUrl == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "baseUrl 不能为空字符串");
      }
      String trimmedModel = trimOrNull(request.model());
      if (request.model() != null && trimmedModel == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "model 不能为空字符串");
      }
      String trimmedApiKey = trimOrNull(request.apiKey());
      if (request.apiKey() != null && trimmedApiKey == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "apiKey 不能为空字符串");
      }

      if (trimmedBaseUrl != null) provider.setBaseUrl(trimmedBaseUrl);
      if (trimmedModel != null) provider.setModel(trimmedModel);
      if (request.embeddingModel() != null) {
        provider.setEmbeddingModel(trimOrNull(request.embeddingModel()));
      }
      if (request.embeddingDimensions() != null) {
        provider.setEmbeddingDimensions(resolveEmbeddingDimensions(request.embeddingDimensions()));
      }
      if (request.supportsEmbedding() != null) {
        provider.setSupportsEmbedding(request.supportsEmbedding());
      }
      validateEmbeddingConfig(
          id,
          provider.isSupportsEmbedding(),
          provider.getEmbeddingModel(),
          resolveEmbeddingDimensions(provider.getEmbeddingDimensions()));
      if (request.temperature() != null) {
        provider.setTemperature(request.temperature());
      }
      if (trimmedApiKey != null) {
        ApiKeyEncryptionService.EncryptedValue encrypted = encryptionService.encrypt(trimmedApiKey);
        provider.setApiKeyNonce(encrypted.nonce());
        provider.setApiKeyCiphertext(encrypted.ciphertext());
      }

      providerRepository.save(provider);
      registry.reload();
      log.info("Updated provider: id={}", id);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  @Transactional
  public void deleteProvider(String id) {
    rwLock.writeLock().lock();
    try {
      if (!isDatabaseBacked()) {
        deleteProviderLegacy(id);
        return;
      }
      LlmGlobalSettingEntity setting = getGlobalSettingOrThrow();
      if (id.equals(setting.getDefaultChatProviderId()) || id.equals(setting.getDefaultEmbeddingProviderId())) {
        throw new BusinessException(ErrorCode.PROVIDER_DEFAULT_CANNOT_DELETE,
            "默认 Provider '" + id + "' 不可删除，请先切换默认 Provider");
      }
      getProviderEntityOrThrow(id);

      providerRepository.deleteById(id);
      registry.reload();
      log.info("Deleted provider: id={}", id);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  @Transactional
  public void updateDefaultProvider(DefaultProviderDTO request) {
    rwLock.writeLock().lock();
    try {
      if (!isDatabaseBacked()) {
        updateDefaultProviderLegacy(request);
        return;
      }
      String providerId = trimOrNull(request.defaultProvider());
      if (providerId == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "defaultProvider 不能为空");
      }
      getProviderEntityOrThrow(providerId);
      LlmGlobalSettingEntity setting = getGlobalSettingOrThrow();
      setting.setDefaultChatProviderId(providerId);
      globalSettingRepository.save(setting);
      registry.reload();
      log.info("Updated default provider: {}", providerId);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  @Transactional
  public void updateDefaultEmbeddingProvider(DefaultProviderDTO request) {
    rwLock.writeLock().lock();
    try {
      String providerId = trimOrNull(request.defaultEmbeddingProvider());
      if (providerId == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "defaultEmbeddingProvider 不能为空");
      }
      LlmProviderEntity provider = getProviderEntityOrThrow(providerId);
      String embeddingModel = trimOrNull(provider.getEmbeddingModel());
      if (!provider.isSupportsEmbedding() || embeddingModel == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST,
            "Provider '" + providerId + "' 不支持 Embedding，不能设为默认向量服务");
      }
      validateEmbeddingConfig(
          providerId,
          true,
          embeddingModel,
          resolveEmbeddingDimensions(provider.getEmbeddingDimensions()));
      LlmGlobalSettingEntity setting = getGlobalSettingOrThrow();
      setting.setDefaultEmbeddingProviderId(providerId);
      globalSettingRepository.save(setting);
      registry.reload();
      log.info("Updated default embedding provider: {}", providerId);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  public void updateAsrConfig(AsrConfigRequest request) {
    rwLock.writeLock().lock();
    try {
      boolean dashscopeTouched = request.dashscope() != null;
      boolean volcTouched = request.volcengine() != null;
      boolean apiKeyChanged = false;

      if (request.provider() != null) {
        String provider = normalizeVoiceProvider(request.provider());
        voiceProperties.setAsrProvider(provider);
        writeVoiceProviderToYaml("asr-provider", provider);
      }
      if (dashscopeTouched) {
        apiKeyChanged = applyDashscopeAsrConfig(request.dashscope());
      }
      if (volcTouched) {
        apiKeyChanged = applyVolcAsrConfig(request.volcengine()) || apiKeyChanged;
      }

      asrService.reload(voiceProperties);
      if (apiKeyChanged) {
        ttsService.reload(voiceProperties);
      }
      log.info("Updated ASR config: provider={}, dashscope={}, volcengine={}",
          voiceProperties.getAsrProvider(), dashscopeTouched, volcTouched);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  public void updateTtsConfig(TtsConfigRequest request) {
    rwLock.writeLock().lock();
    try {
      boolean dashscopeTouched = request.dashscope() != null;
      boolean volcTouched = request.volcengine() != null;
      boolean apiKeyChanged = false;

      if (request.provider() != null) {
        String provider = normalizeVoiceProvider(request.provider());
        voiceProperties.setTtsProvider(provider);
        writeVoiceProviderToYaml("tts-provider", provider);
      }
      if (dashscopeTouched) {
        apiKeyChanged = applyDashscopeTtsConfig(request.dashscope());
      }
      if (volcTouched) {
        apiKeyChanged = applyVolcTtsConfig(request.volcengine()) || apiKeyChanged;
      }

      ttsService.reload(voiceProperties);
      if (apiKeyChanged) {
        asrService.reload(voiceProperties);
      }
      log.info("Updated TTS config: provider={}, dashscope={}, volcengine={}",
          voiceProperties.getTtsProvider(), dashscopeTouched, volcTouched);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  private boolean applyDashscopeAsrConfig(AsrConfigRequest.DashscopePart part) {
    VoiceInterviewProperties.AsrConfig asr = voiceProperties.getQwen().getAsr();
    if (part.url() != null) asr.setUrl(requireNonBlank(part.url(), "ASR WebSocket URL"));
    if (part.model() != null) asr.setModel(requireNonBlank(part.model(), "ASR 模型"));
    if (part.language() != null) asr.setLanguage(requireNonBlank(part.language(), "ASR 语言"));
    if (part.format() != null) asr.setFormat(requireNonBlank(part.format(), "ASR 音频格式"));
    if (part.sampleRate() != null) asr.setSampleRate(part.sampleRate());
    if (part.enableTurnDetection() != null) asr.setEnableTurnDetection(part.enableTurnDetection());
    if (part.turnDetectionType() != null) asr.setTurnDetectionType(requireNonBlank(part.turnDetectionType(), "Turn Detection 类型"));
    if (part.turnDetectionThreshold() != null) asr.setTurnDetectionThreshold(part.turnDetectionThreshold());
    if (part.turnDetectionSilenceDurationMs() != null) {
      asr.setTurnDetectionSilenceDurationMs(part.turnDetectionSilenceDurationMs());
    }

    boolean apiKeyChanged = false;
    String apiKey = requireApiKeyOrNull(part.apiKey());
    if (apiKey != null) {
      asr.setApiKey(apiKey);
      voiceProperties.getQwen().getTts().setApiKey(apiKey);
      writeEnvValue(VOICE_ENV_KEY_DASHSCOPE, apiKey);
      apiKeyChanged = true;
    }
    writeDashscopeAsrConfigToYaml(asr);
    return apiKeyChanged;
  }

  private boolean applyVolcAsrConfig(AsrConfigRequest.VolcPart part) {
    VoiceInterviewProperties.VolcAsrConfig volc = voiceProperties.getVolc().getAsr();
    if (part.url() != null) volc.setUrl(requireNonBlank(part.url(), "火山 ASR WebSocket URL"));
    if (part.resourceId() != null) volc.setResourceId(requireNonBlank(part.resourceId(), "X-Api-Resource-Id"));
    if (part.modelName() != null) volc.setModelName(requireNonBlank(part.modelName(), "ASR Model Name"));
    if (part.format() != null) {
      volc.setFormat(requireOneOf(part.format(), VOLC_ASR_FORMATS, "火山 ASR 音频格式"));
    }
    if (part.sampleRate() != null) {
      if (part.sampleRate() != VOLC_ASR_SAMPLE_RATE) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "火山方舟 ASR 采样率仅支持 16000");
      }
      volc.setSampleRate(part.sampleRate());
    }
    if (part.bits() != null) {
      if (part.bits() != VOLC_ASR_BITS) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "火山方舟 ASR 位深仅支持 16");
      }
      volc.setBits(part.bits());
    }
    if (part.channel() != null) {
      if (part.channel() != 1 && part.channel() != 2) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "火山方舟 ASR 声道数仅支持 1（单声道）或 2（立体声）");
      }
      volc.setChannel(part.channel());
    }
    if (part.enableItn() != null) volc.setEnableItn(part.enableItn());
    if (part.enablePunc() != null) volc.setEnablePunc(part.enablePunc());
    if (part.enableDdc() != null) volc.setEnableDdc(part.enableDdc());
    if (part.enableNonstream() != null) volc.setEnableNonstream(part.enableNonstream());
    if (part.segmentMs() != null) {
      if (part.segmentMs() < VOLC_ASR_MIN_SEGMENT_MS || part.segmentMs() > VOLC_ASR_MAX_SEGMENT_MS) {
        throw new BusinessException(ErrorCode.BAD_REQUEST,
            "火山方舟 ASR 分包时长需在 " + VOLC_ASR_MIN_SEGMENT_MS + "-" + VOLC_ASR_MAX_SEGMENT_MS + "ms（推荐 200ms）");
      }
      volc.setSegmentMs(part.segmentMs());
    }

    boolean apiKeyChanged = false;
    String apiKey = requireApiKeyOrNull(part.apiKey());
    if (apiKey != null) {
      volc.setApiKey(apiKey);
      voiceProperties.getVolc().getTts().setApiKey(apiKey);
      writeEnvValue(VOICE_ENV_KEY_VOLC, apiKey);
      apiKeyChanged = true;
    }
    writeVolcAsrConfigToYaml(volc);
    return apiKeyChanged;
  }

  private boolean applyDashscopeTtsConfig(TtsConfigRequest.DashscopePart part) {
    VoiceInterviewProperties.QwenTtsConfig tts = voiceProperties.getQwen().getTts();
    if (part.model() != null) tts.setModel(requireNonBlank(part.model(), "TTS 模型"));
    if (part.voice() != null) tts.setVoice(requireNonBlank(part.voice(), "TTS 音色"));
    if (part.format() != null) tts.setFormat(requireNonBlank(part.format(), "TTS 音频格式"));
    if (part.sampleRate() != null) tts.setSampleRate(part.sampleRate());
    if (part.mode() != null) tts.setMode(requireNonBlank(part.mode(), "TTS 模式"));
    if (part.languageType() != null) tts.setLanguageType(requireNonBlank(part.languageType(), "TTS 语言"));
    if (part.speechRate() != null) tts.setSpeechRate(part.speechRate());
    if (part.volume() != null) tts.setVolume(part.volume());

    boolean apiKeyChanged = false;
    String apiKey = requireApiKeyOrNull(part.apiKey());
    if (apiKey != null) {
      tts.setApiKey(apiKey);
      voiceProperties.getQwen().getAsr().setApiKey(apiKey);
      writeEnvValue(VOICE_ENV_KEY_DASHSCOPE, apiKey);
      apiKeyChanged = true;
    }
    writeDashscopeTtsConfigToYaml(tts);
    return apiKeyChanged;
  }

  private boolean applyVolcTtsConfig(TtsConfigRequest.VolcPart part) {
    VoiceInterviewProperties.VolcTtsConfig volc = voiceProperties.getVolc().getTts();
    if (part.url() != null) volc.setUrl(requireNonBlank(part.url(), "火山 TTS WebSocket URL"));
    if (part.resourceId() != null) volc.setResourceId(requireNonBlank(part.resourceId(), "X-Api-Resource-Id"));
    if (part.speaker() != null) volc.setSpeaker(requireNonBlank(part.speaker(), "TTS 音色 ID"));
    if (part.format() != null) {
      volc.setFormat(requireOneOf(part.format(), VOLC_TTS_FORMATS, "火山 TTS 音频格式"));
    }
    if (part.sampleRate() != null) {
      if (!VOLC_TTS_SAMPLE_RATES.contains(part.sampleRate())) {
        throw new BusinessException(ErrorCode.BAD_REQUEST,
            "火山方舟 TTS 采样率仅支持 8000 / 16000 / 22050 / 24000 / 32000 / 44100 / 48000");
      }
      volc.setSampleRate(part.sampleRate());
    }

    boolean apiKeyChanged = false;
    String apiKey = requireApiKeyOrNull(part.apiKey());
    if (apiKey != null) {
      volc.setApiKey(apiKey);
      voiceProperties.getVolc().getAsr().setApiKey(apiKey);
      writeEnvValue(VOICE_ENV_KEY_VOLC, apiKey);
      apiKeyChanged = true;
    }
    writeVolcTtsConfigToYaml(volc);
    return apiKeyChanged;
  }

  public void reloadProviders() {
    registry.reload();
    log.info("Manual provider reload triggered");
  }

  // ===== Internal helpers =====

  private boolean isDatabaseBacked() {
    return providerRepository != null && globalSettingRepository != null && encryptionService != null;
  }

  private Map<String, ProviderConfig> getLegacyProvidersOrThrow() {
    Map<String, ProviderConfig> providers = properties.getProviders();
    if (providers == null) {
      throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
          "Provider 配置未初始化");
    }
    return providers;
  }

  ProviderConfig getLegacyProviderConfigOrThrow(String id) {
    ProviderConfig config = getLegacyProvidersOrThrow().get(id);
    if (config == null) {
      throw new BusinessException(ErrorCode.PROVIDER_NOT_FOUND,
          "Provider '" + id + "' 不存在");
    }
    return config;
  }

  private void createProviderLegacy(CreateProviderRequest request) {
    Map<String, ProviderConfig> providers = getLegacyProvidersOrThrow();
    if (providers.containsKey(request.id())) {
      throw new BusinessException(ErrorCode.PROVIDER_ALREADY_EXISTS,
          "Provider '" + request.id() + "' 已存在");
    }

    ProviderConfig config = new ProviderConfig();
    config.setBaseUrl(request.baseUrl());
    config.setApiKey(request.apiKey());
    config.setModel(request.model());
    config.setEmbeddingModel(request.embeddingModel());
    config.setEmbeddingDimensions(request.embeddingDimensions());
    config.setSupportsEmbedding(request.supportsEmbedding());
    config.setTemperature(request.temperature());
    providers.put(request.id(), config);

    String envKey = toEnvKey(request.id());
    writeProviderToYaml(request.id(), config, envKey);
    writeEnvValue(envKey, request.apiKey());
    registry.reload();
  }

  private void updateProviderLegacy(String id, UpdateProviderRequest request) {
    ProviderConfig config = getLegacyProviderConfigOrThrow(id);
    String trimmedBaseUrl = trimOrNull(request.baseUrl());
    if (request.baseUrl() != null && trimmedBaseUrl == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "baseUrl 不能为空字符串");
    }
    String trimmedModel = trimOrNull(request.model());
    if (request.model() != null && trimmedModel == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "model 不能为空字符串");
    }
    String trimmedApiKey = trimOrNull(request.apiKey());
    if (request.apiKey() != null && trimmedApiKey == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "apiKey 不能为空字符串");
    }

    if (trimmedBaseUrl != null) config.setBaseUrl(trimmedBaseUrl);
    if (trimmedModel != null) config.setModel(trimmedModel);
    if (request.embeddingModel() != null) {
      config.setEmbeddingModel(trimOrNull(request.embeddingModel()));
    }
    if (request.embeddingDimensions() != null) {
      config.setEmbeddingDimensions(resolveEmbeddingDimensions(request.embeddingDimensions()));
    }
    if (request.supportsEmbedding() != null) {
      config.setSupportsEmbedding(request.supportsEmbedding());
    }
    if (request.temperature() != null) {
      config.setTemperature(request.temperature());
    }
    if (trimmedApiKey != null) {
      config.setApiKey(trimmedApiKey);
      updateEnvValue(toEnvKey(id), trimmedApiKey);
    }

    writeProviderToYaml(id, config, toEnvKey(id));
    registry.reload();
  }

  private void deleteProviderLegacy(String id) {
    if (id.equals(properties.getDefaultProvider())) {
      throw new BusinessException(ErrorCode.PROVIDER_DEFAULT_CANNOT_DELETE,
          "默认 Provider '" + id + "' 不可删除，请先切换默认 Provider");
    }
    getLegacyProviderConfigOrThrow(id);
    getLegacyProvidersOrThrow().remove(id);
    String envKey = toEnvKey(id);
    removeProviderFromYaml(id);
    removeFromEnv(envKey);
    registry.reload();
  }

  private void updateDefaultProviderLegacy(DefaultProviderDTO request) {
    String providerId = trimOrNull(request.defaultProvider());
    if (providerId == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "defaultProvider 不能为空");
    }
    getLegacyProviderConfigOrThrow(providerId);
    properties.setDefaultProvider(providerId);
    writeDefaultProviderToYaml(providerId);
    registry.reload();
  }

  private ProviderRuntimeConfig toRuntimeConfig(ProviderConfig config) {
    return new ProviderRuntimeConfig(
        config.getBaseUrl(),
        config.getApiKey(),
        config.getModel(),
        config.getEmbeddingModel(),
        resolveEmbeddingDimensions(config.getEmbeddingDimensions()),
        Boolean.TRUE.equals(config.getSupportsEmbedding()) || trimOrNull(config.getEmbeddingModel()) != null,
        config.getTemperature()
    );
  }

  LlmProviderEntity getProviderEntityOrThrow(String id) {
    return providerRepository.findById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.PROVIDER_NOT_FOUND,
            "Provider '" + id + "' 不存在"));
  }

  private LlmGlobalSettingEntity getGlobalSettingOrThrow() {
    return globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID)
        .orElseThrow(() -> new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
            "默认 Provider 配置未初始化"));
  }

  private ProviderRuntimeConfig getProviderRuntimeConfigOrThrow(String id) {
    LlmProviderEntity provider = getProviderEntityOrThrow(id);
    return new ProviderRuntimeConfig(
        provider.getBaseUrl(),
        decryptApiKey(provider),
        provider.getModel(),
        provider.getEmbeddingModel(),
        resolveEmbeddingDimensions(provider.getEmbeddingDimensions()),
        provider.isSupportsEmbedding(),
        provider.getTemperature()
    );
  }

  private String decryptApiKey(LlmProviderEntity provider) {
    return encryptionService.decrypt(provider.getApiKeyNonce(), provider.getApiKeyCiphertext());
  }

  String maskApiKey(String apiKey) {
    if (apiKey == null || apiKey.length() <= 6) {
      return "***";
    }
    return apiKey.substring(0, 3) + "***" + apiKey.substring(apiKey.length() - 3);
  }

  private String abbreviate(String text) {
    if (text == null || text.isBlank()) {
      return "[no body]";
    }
    String normalized = text.replaceAll("\\s+", " ").trim();
    if (normalized.length() <= 200) {
      return normalized;
    }
    return normalized.substring(0, 200) + "...";
  }

  private List<String> buildConnectivityTestUrls(String baseUrl) {
    String normalizedBaseUrl = ApiPathResolver.stripTrailingSlashes(baseUrl);
    LinkedHashSet<String> candidateUrls = new LinkedHashSet<>();

    candidateUrls.add(normalizedBaseUrl + "/chat/completions");
    if (!ApiPathResolver.baseUrlContainsVersion(normalizedBaseUrl)) {
      candidateUrls.add(normalizedBaseUrl + "/v1/chat/completions");
    }

    return List.copyOf(candidateUrls);
  }

  private Map<String, Object> buildConnectivityTestRequestBody(String model) {
    Map<String, Object> requestBody = new LinkedHashMap<>();
    requestBody.put("model", model);
    requestBody.put("messages", List.of(Map.of(
        "role", "user",
        "content", "Reply with OK only."
    )));
    requestBody.put("max_tokens", 1);
    return requestBody;
  }

  private String trimOrNull(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private String requireNonBlank(String value, String fieldName) {
    String normalized = trimOrNull(value);
    if (normalized == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, fieldName + " 不能为空");
    }
    return normalized;
  }

  private String normalizeVoiceProvider(String rawProvider) {
    String provider = trimOrNull(rawProvider);
    if (provider == null || !VOICE_PROVIDERS.contains(provider)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "语音服务提供方仅支持 dashscope / volcengine");
    }
    return provider;
  }

  private String resolveVoiceProvider(String configuredProvider) {
    String provider = trimOrNull(configuredProvider);
    return provider == null ? PROVIDER_DASHSCOPE : provider;
  }

  private String requireOneOf(String value, Set<String> allowed, String fieldName) {
    String normalized = trimOrNull(value);
    if (normalized == null || !allowed.contains(normalized)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          fieldName + " 仅支持 " + String.join(" / ", allowed));
    }
    return normalized;
  }

  private String requireApiKeyOrNull(String rawApiKey) {
    if (rawApiKey == null) {
      return null;
    }
    String apiKey = trimOrNull(rawApiKey);
    if (apiKey == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "API Key 不能为空字符串（留空表示保持原值）");
    }
    return apiKey;
  }

  private void validateEmbeddingConfig(
      String providerId,
      boolean supportsEmbedding,
      String embeddingModel,
      Integer embeddingDimensions) {
    String normalizedModel = trimOrNull(embeddingModel);
    if (!supportsEmbedding) {
      return;
    }
    if (normalizedModel == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "支持 Embedding 的 Provider 必须填写 embeddingModel");
    }
    if (looksLikeChatModel(normalizedModel)) {
      String recommendation = RECOMMENDED_EMBEDDING_MODELS.get(providerId.toLowerCase());
      String suffix = recommendation != null
          ? "，推荐填写 " + recommendation
          : "，请填写该厂商真实的 Embedding 模型名";
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "Embedding Model 不能填写聊天模型 '" + normalizedModel + "'" + suffix);
    }
    if (embeddingDimensions == null || embeddingDimensions <= 0) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "向量维度必须为正整数");
    }
  }

  private Integer resolveEmbeddingDimensions(Integer configuredDimensions) {
    if (configuredDimensions != null && configuredDimensions > 0) {
      return configuredDimensions;
    }
    return properties.getEmbeddingDimensions();
  }

  private boolean looksLikeChatModel(String model) {
    String lower = model.toLowerCase();
    return lower.startsWith("glm-")
        || lower.startsWith("deepseek")
        || lower.startsWith("kimi")
        || lower.startsWith("moonshot")
        || lower.startsWith("qwen")
        || lower.startsWith("ernie");
  }

  private String toEnvKey(String providerId) {
    return "PROVIDER_" + providerId.toUpperCase().replace("-", "_") + "_API_KEY";
  }

  // ===== Provider test logic (called under read lock) =====

  private ProviderTestResult doTestProvider(ProviderRuntimeConfig config, String id) {
    try {
      HttpClientSettings settings = HttpClientSettings.defaults()
          .withConnectTimeout(Duration.ofSeconds(5))
          .withReadTimeout(Duration.ofSeconds(10))
          .withInetAddressFilter(
              InetAddressFilter.externalAddresses()
                  .or(InetAddressFilter.adapt(InetAddress::isLoopbackAddress))
                  .or("198.18.0.0/15"));

      RestClient restClient = RestClient.builder()
          .defaultHeader("Authorization", "Bearer " + config.apiKey())
          .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(settings))
          .build();

      Map<String, Object> requestBody = buildConnectivityTestRequestBody(config.model());

      List<String> candidateUrls = buildConnectivityTestUrls(config.baseUrl());
      String lastFailureMessage = "Unknown error";

      for (String targetUrl : candidateUrls) {
        try {
          restClient.post()
              .uri(URI.create(targetUrl))
              .body(requestBody)
              .retrieve()
              .toEntity(String.class);
          log.info("Provider connectivity test succeeded: providerId={}, baseUrl={}, targetUrl={}, model={}",
              id, config.baseUrl(), targetUrl, config.model());
          return ProviderTestResult.builder()
              .success(true)
              .message("连接成功")
              .model(config.model())
              .build();
        } catch (RestClientResponseException e) {
          String responseBody = abbreviate(e.getResponseBodyAsString());
          lastFailureMessage = String.format(
              "HTTP %s on %s, body=%s",
              e.getStatusCode().value(),
              targetUrl,
              responseBody
          );
          log.warn(
              "Provider connectivity test failed with response: providerId={}, baseUrl={}, targetUrl={}, model={}, status={}, body={}",
              id,
              config.baseUrl(),
              targetUrl,
              config.model(),
              e.getStatusCode().value(),
              responseBody,
              e
          );
        } catch (Exception e) {
          lastFailureMessage = String.format(
              "%s on %s: %s",
              e.getClass().getSimpleName(),
              targetUrl,
              e.getMessage()
          );
          log.warn(
              "Provider connectivity test failed: providerId={}, baseUrl={}, targetUrl={}, model={}, error={}",
              id,
              config.baseUrl(),
              targetUrl,
              config.model(),
              e.getMessage(),
              e
          );
        }
      }
      return ProviderTestResult.builder()
          .success(false)
          .message("连接失败: " + lastFailureMessage)
          .model(config.model())
          .build();
    } catch (Exception e) {
      log.warn("Provider connectivity test setup failed: providerId={}, baseUrl={}, model={}, error={}",
          id, config.baseUrl(), config.model(), e.getMessage(), e);
      return ProviderTestResult.builder()
          .success(false)
          .message("连接失败: " + e.getMessage())
          .model(config.model())
          .build();
    }
  }

  // ===== YAML text editing (preserves comments & formatting) =====

  private void writeProviderToYaml(String id, ProviderConfig config, String envKey) {
    mutateYamlText(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED, "写入 YAML 配置失败", editor -> {
      LinkedHashMap<String, Object> values = new LinkedHashMap<>();
      values.put("base-url", config.getBaseUrl());
      values.put("api-key", "${" + envKey + "}");
      values.put("model", config.getModel());
      if (config.getEmbeddingModel() != null) {
        values.put("embedding-model", config.getEmbeddingModel());
      }
      if (config.getEmbeddingDimensions() != null) {
        values.put("embedding-dimensions", config.getEmbeddingDimensions());
      }
      if (config.getTemperature() != null) {
        values.put("temperature", config.getTemperature());
      }
      editor.setBlock(new String[]{"app", "ai", "providers"}, id, values);
    });
  }

  private void removeProviderFromYaml(String id) {
    mutateYamlText(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED, "删除 YAML 配置失败", editor -> {
      editor.removeSection(new String[]{"app", "ai", "providers"}, id);
    });
  }

  private void writeDefaultProviderToYaml(String defaultProvider) {
    mutateYamlText(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED, "写入默认 Provider 配置失败", editor -> {
      editor.setScalar(new String[]{"app", "ai", "default-provider"}, defaultProvider);
      editor.removeSection(new String[]{"app", "ai"}, "module-defaults");
    });
  }

  private void writeVoiceProviderToYaml(String key, String provider) {
    mutateYamlText(ErrorCode.VOICE_CONFIG_WRITE_FAILED, "写入语音服务提供方失败", editor ->
        editor.setScalar(new String[]{"app", "voice-interview", key}, provider));
  }

  private void writeDashscopeAsrConfigToYaml(VoiceInterviewProperties.AsrConfig asr) {
    mutateYamlText(ErrorCode.VOICE_CONFIG_WRITE_FAILED, "写入 ASR 配置失败", editor -> {
      LinkedHashMap<String, Object> values = new LinkedHashMap<>();
      values.put("url", asr.getUrl());
      values.put("model", asr.getModel());
      values.put("api-key", "${AI_BAILIAN_API_KEY}");
      values.put("language", asr.getLanguage());
      values.put("format", asr.getFormat());
      values.put("sample-rate", asr.getSampleRate());
      values.put("enable-turn-detection", asr.isEnableTurnDetection());
      values.put("turn-detection-type", asr.getTurnDetectionType());
      values.put("turn-detection-threshold", asr.getTurnDetectionThreshold());
      values.put("turn-detection-silence-duration-ms", asr.getTurnDetectionSilenceDurationMs());
      editor.setBlock(new String[]{"app", "voice-interview", "qwen"}, "asr", values);
    });
  }

  private void writeDashscopeTtsConfigToYaml(VoiceInterviewProperties.QwenTtsConfig tts) {
    mutateYamlText(ErrorCode.VOICE_CONFIG_WRITE_FAILED, "写入 TTS 配置失败", editor -> {
      LinkedHashMap<String, Object> values = new LinkedHashMap<>();
      values.put("model", tts.getModel());
      values.put("api-key", "${AI_BAILIAN_API_KEY}");
      values.put("voice", tts.getVoice());
      values.put("format", tts.getFormat());
      values.put("sample-rate", tts.getSampleRate());
      values.put("mode", tts.getMode());
      values.put("language-type", tts.getLanguageType());
      values.put("speech-rate", tts.getSpeechRate());
      values.put("volume", tts.getVolume());
      editor.setBlock(new String[]{"app", "voice-interview", "qwen"}, "tts", values);
    });
  }

  private void writeVolcAsrConfigToYaml(VoiceInterviewProperties.VolcAsrConfig asr) {
    mutateYamlText(ErrorCode.VOICE_CONFIG_WRITE_FAILED, "写入火山 ASR 配置失败", editor -> {
      LinkedHashMap<String, Object> values = new LinkedHashMap<>();
      values.put("url", asr.getUrl());
      values.put("api-key", "${VOLC_AGENT_PLAN_VOICE_API_KEY}");
      values.put("resource-id", asr.getResourceId());
      values.put("model-name", asr.getModelName());
      values.put("format", asr.getFormat());
      values.put("sample-rate", asr.getSampleRate());
      values.put("bits", asr.getBits());
      values.put("channel", asr.getChannel());
      values.put("enable-itn", asr.isEnableItn());
      values.put("enable-punc", asr.isEnablePunc());
      values.put("enable-ddc", asr.isEnableDdc());
      values.put("enable-nonstream", asr.isEnableNonstream());
      values.put("segment-ms", asr.getSegmentMs());
      editor.setBlock(new String[]{"app", "voice-interview", "volc"}, "asr", values);
    });
  }

  private void writeVolcTtsConfigToYaml(VoiceInterviewProperties.VolcTtsConfig tts) {
    mutateYamlText(ErrorCode.VOICE_CONFIG_WRITE_FAILED, "写入火山 TTS 配置失败", editor -> {
      LinkedHashMap<String, Object> values = new LinkedHashMap<>();
      values.put("url", tts.getUrl());
      values.put("api-key", "${VOLC_AGENT_PLAN_VOICE_API_KEY}");
      values.put("resource-id", tts.getResourceId());
      values.put("speaker", tts.getSpeaker());
      values.put("format", tts.getFormat());
      values.put("sample-rate", tts.getSampleRate());
      editor.setBlock(new String[]{"app", "voice-interview", "volc"}, "tts", values);
    });
  }

  private void mutateYamlText(ErrorCode errorCode, String errorMessage, Consumer<YamlTextEditor> mutator) {
    if (yamlPath == null || yamlPath.isBlank()) {
      log.warn("YAML path not configured, skip writing");
      return;
    }
    try {
      Path path = Path.of(yamlPath);
      List<String> lines;
      if (Files.exists(path)) {
        lines = new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8));
      } else {
        lines = new ArrayList<>();
      }

      YamlTextEditor editor = new YamlTextEditor(lines);
      mutator.accept(editor);

      String content = String.join("\n", editor.getLines());
      if (!content.endsWith("\n")) {
        content += "\n";
      }
      Files.writeString(path, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new BusinessException(errorCode, errorMessage + ": " + e.getMessage());
    }
  }

  // ===== .env file operations =====

  private void writeEnvValue(String key, String value) {
    if (envPath == null || envPath.isBlank()) return;
    try {
      Path path = Path.of(envPath);
      if (!Files.exists(path)) {
        Files.writeString(path, key + "=" + value + "\n", StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return;
      }
      String content = Files.readString(path, StandardCharsets.UTF_8);
      if (content.contains(key + "=")) {
        content = content.replaceAll("(?m)^" + Pattern.quote(key) + "=.*",
            Matcher.quoteReplacement(key + "=" + value));
      } else {
        if (!content.endsWith("\n")) {
          content += "\n";
        }
        content += key + "=" + value + "\n";
      }
      Files.writeString(path, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      log.warn("写入 .env 失败: {}", e.getMessage());
    }
  }

  private void updateEnvValue(String key, String value) {
    writeEnvValue(key, value);
  }

  private void removeFromEnv(String key) {
    if (envPath == null || envPath.isBlank()) return;
    try {
      Path path = Path.of(envPath);
      if (!Files.exists(path)) return;
      String content = Files.readString(path, StandardCharsets.UTF_8);
      content = content.replaceAll("(?m)^" + Pattern.quote(key) + "=.*\\R?", "");
      Files.writeString(path, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      log.warn("删除 .env 条目失败: {}", e.getMessage());
    }
  }

  // ===== YamlTextEditor: text-based YAML editing that preserves comments & formatting =====

  static class YamlTextEditor {
    private final List<String> lines;

    YamlTextEditor(List<String> lines) {
      this.lines = new ArrayList<>(lines);
    }

    List<String> getLines() {
      return lines;
    }

    void setScalar(String[] path, String value) {
      int searchFrom = path.length > 1
          ? ensureParents(Arrays.copyOf(path, path.length - 1))
          : 0;
      int indent = (path.length - 1) * 2;
      String key = path[path.length - 1];
      String newLine = " ".repeat(indent) + key + ": " + value;

      int found = findKey(key, indent, searchFrom);
      if (found >= 0) {
        lines.set(found, newLine);
      } else {
        int parentIndent = indent >= 2 ? indent - 2 : -1;
        int insertPos = findSectionEnd(searchFrom, parentIndent);
        lines.add(insertPos, newLine);
      }
    }

    void setBlock(String[] parentPath, String blockKey, LinkedHashMap<String, Object> values) {
      int parentSearchFrom = ensureParents(parentPath);
      int blockIndent = parentPath.length * 2;
      int valueIndent = blockIndent + 2;

      int blockLine = findKey(blockKey, blockIndent, parentSearchFrom);
      if (blockLine < 0) {
        int parentEnd = parentPath.length >= 1 ? blockIndent - 2 : -1;
        int insertPos = findSectionEnd(parentSearchFrom, parentEnd);
        lines.add(insertPos, " ".repeat(blockIndent) + blockKey + ":");
        blockLine = insertPos;
      }

      int blockEnd = findSectionEnd(blockLine + 1, blockIndent);

      for (Map.Entry<String, Object> entry : values.entrySet()) {
        String valueLine = " ".repeat(valueIndent) + entry.getKey() + ": " + formatValue(entry.getValue());
        int existing = findKeyInRange(entry.getKey(), valueIndent, blockLine + 1, blockEnd);
        if (existing >= 0) {
          lines.set(existing, valueLine);
        } else {
          lines.add(blockEnd, valueLine);
          blockEnd++;
        }
      }
    }

    void removeSection(String[] parentPath, String sectionKey) {
      int parentSearchFrom = navigateTo(parentPath);
      if (parentSearchFrom < 0) return;

      int sectionIndent = parentPath.length * 2;
      int sectionLine = findKey(sectionKey, sectionIndent, parentSearchFrom);
      if (sectionLine < 0) return;

      int endLine = sectionLine + 1;
      while (endLine < lines.size()) {
        String line = lines.get(endLine);
        if (line.isBlank()) { endLine++; continue; }
        if (indentOf(line) <= sectionIndent) break;
        endLine++;
      }

      for (int i = endLine - 1; i >= sectionLine; i--) {
        lines.remove(i);
      }
    }

    private int ensureParents(String[] path) {
      int searchFrom = 0;
      for (int i = 0; i < path.length; i++) {
        int indent = i * 2;
        int found = findKey(path[i], indent, searchFrom);
        if (found < 0) {
          int parentIndent = i > 0 ? indent - 2 : -1;
          int insertPos = findSectionEnd(searchFrom, parentIndent);
          lines.add(insertPos, " ".repeat(indent) + path[i] + ":");
          searchFrom = insertPos + 1;
        } else {
          searchFrom = found + 1;
        }
      }
      return searchFrom;
    }

    private int navigateTo(String[] path) {
      int searchFrom = 0;
      for (int i = 0; i < path.length; i++) {
        int indent = i * 2;
        int found = findKey(path[i], indent, searchFrom);
        if (found < 0) return -1;
        searchFrom = found + 1;
      }
      return searchFrom;
    }

    private int findKey(String key, int indent, int searchFrom) {
      String prefix = " ".repeat(indent) + key + ":";
      for (int i = searchFrom; i < lines.size(); i++) {
        String line = lines.get(i);
        if (line.isBlank() || line.trim().startsWith("#")) continue;
        if (line.startsWith(prefix)) return i;
        if (indentOf(line) < indent) break;
      }
      return -1;
    }

    private int findKeyInRange(String key, int indent, int start, int end) {
      String prefix = " ".repeat(indent) + key + ":";
      for (int i = start; i < end && i < lines.size(); i++) {
        String line = lines.get(i);
        if (line.isBlank() || line.trim().startsWith("#")) continue;
        if (line.startsWith(prefix)) return i;
        if (indentOf(line) < indent) break;
      }
      return -1;
    }

    private int findSectionEnd(int searchFrom, int parentIndent) {
      for (int i = searchFrom; i < lines.size(); i++) {
        String line = lines.get(i);
        if (line.isBlank() || line.trim().startsWith("#")) continue;
        if (indentOf(line) <= parentIndent) return i;
      }
      return lines.size();
    }

    private int indentOf(String line) {
      int count = 0;
      while (count < line.length() && line.charAt(count) == ' ') count++;
      return count;
    }

    private String formatValue(Object value) {
      if (value instanceof Boolean b) return b.toString();
      if (value instanceof Number n) return n.toString();
      return value.toString();
    }
  }

  private record ProviderRuntimeConfig(
      String baseUrl,
      String apiKey,
      String model,
      String embeddingModel,
      Integer embeddingDimensions,
      boolean supportsEmbedding,
      Double temperature
  ) {
  }
}
