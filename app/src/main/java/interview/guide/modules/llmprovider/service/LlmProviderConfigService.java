package interview.guide.modules.llmprovider.service;

import interview.guide.common.ai.ApiPathResolver;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.repository.UserAccountRepository;
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
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.http.client.InetAddressFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class LlmProviderConfigService {

  private static final ObjectMapper VOICE_JSON = JsonMapper.builder().build();

  private final LlmProviderProperties properties;
  private final LlmProviderRegistry registry;
  private final LlmProviderRepository providerRepository;
  private final LlmGlobalSettingRepository globalSettingRepository;
  private final ApiKeyEncryptionService encryptionService;
  private final UserAccountRepository accounts;
  private final String yamlPath;
  private final String envPath;
  private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
  private final VoiceInterviewProperties voiceProperties;

  @Autowired
  public LlmProviderConfigService(
      LlmProviderProperties properties,
      LlmProviderRegistry registry,
      LlmProviderRepository providerRepository,
      LlmGlobalSettingRepository globalSettingRepository,
      ApiKeyEncryptionService encryptionService,
      VoiceInterviewProperties voiceProperties,
      UserAccountRepository accounts) {
    this.accounts = accounts;
    this.properties = properties;
    this.registry = registry;
    this.providerRepository = providerRepository;
    this.globalSettingRepository = globalSettingRepository;
    this.encryptionService = encryptionService;
    this.yamlPath = properties.getConfigYamlPath();
    this.envPath = properties.getConfigEnvPath();
    this.voiceProperties = voiceProperties;

  }

  LlmProviderConfigService(
      LlmProviderProperties properties, LlmProviderRegistry registry,
      VoiceInterviewProperties voiceProperties) {
    this(properties, registry, null, null, null, voiceProperties, null);
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

  @Transactional
  public List<ProviderDTO> listProviders(Long userId) {
    initializeUserProviders(userId);
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
      LlmGlobalSettingEntity setting = getGlobalSettingOrThrow(userId);
      return providerRepository.findByUserIdOrderByIdAsc(userId).stream()
          .map(provider -> toProviderDto(provider, setting))
          .toList();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  public ProviderDTO getProvider(Long userId, String id) {
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
      LlmProviderEntity provider = getProviderEntityOrThrow(userId, id);
      LlmGlobalSettingEntity setting = getGlobalSettingOrThrow(userId);
      return toProviderDto(provider, setting);
    } finally {
      rwLock.readLock().unlock();
    }
  }

  @Transactional
  public DefaultProviderDTO getDefaultProvider(Long userId) {
    initializeUserProviders(userId);
    rwLock.readLock().lock();
    try {
      if (!isDatabaseBacked()) {
        return new DefaultProviderDTO(properties.getDefaultProvider(), properties.getDefaultEmbeddingProvider());
      }
      LlmGlobalSettingEntity setting = getGlobalSettingOrThrow(userId);
      return new DefaultProviderDTO(
          setting.getDefaultChatProviderId(),
          setting.getDefaultEmbeddingProviderId());
    } finally {
      rwLock.readLock().unlock();
    }
  }

  @Transactional
  public AsrConfigDTO getAsrConfig(Long userId) {
    initializeUserProviders(userId);
    rwLock.readLock().lock();
    try {
      VoiceInterviewProperties.AsrConfig asr = loadAsrConfig(userId, false);
      return AsrConfigDTO.builder()
          .url(asr.getUrl())
          .model(asr.getModel())
          .maskedApiKey(trimOrNull(asr.getApiKey()) == null ? "未配置" : "已配置")
          .language(asr.getLanguage())
          .format(asr.getFormat())
          .sampleRate(asr.getSampleRate())
          .enableTurnDetection(asr.isEnableTurnDetection())
          .turnDetectionType(asr.getTurnDetectionType())
          .turnDetectionThreshold(asr.getTurnDetectionThreshold())
          .turnDetectionSilenceDurationMs(asr.getTurnDetectionSilenceDurationMs())
          .build();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  @Transactional
  public TtsConfigDTO getTtsConfig(Long userId) {
    initializeUserProviders(userId);
    rwLock.readLock().lock();
    try {
      VoiceInterviewProperties.QwenTtsConfig tts = loadTtsConfig(userId, false);
      return TtsConfigDTO.builder()
          .url(tts.getUrl())
          .model(tts.getModel())
          .maskedApiKey(trimOrNull(tts.getApiKey()) == null ? "未配置" : "已配置")
          .voice(tts.getVoice())
          .format(tts.getFormat())
          .sampleRate(tts.getSampleRate())
          .mode(tts.getMode())
          .languageType(tts.getLanguageType())
          .speechRate(tts.getSpeechRate())
          .volume(tts.getVolume())
          .build();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  public ProviderTestResult testProvider(Long userId, String id) {
    rwLock.readLock().lock();
    try {
      ProviderRuntimeConfig config = isDatabaseBacked()
          ? getProviderRuntimeConfigOrThrow(userId, id)
          : toRuntimeConfig(getLegacyProviderConfigOrThrow(id));
      return doTestProvider(config, id, userId);
    } finally {
      rwLock.readLock().unlock();
    }
  }

  public ProviderTestResult testAsrConfig(Long userId) {
    rwLock.readLock().lock();
    try {
      VoiceInterviewProperties.AsrConfig asr = loadAsrConfig(userId, true);
      try {
        java.net.URI wsUri = java.net.URI.create(asr.getUrl());
        String host = wsUri.getHost();
        int port = wsUri.getPort() > 0 ? wsUri.getPort() : (wsUri.getScheme().equals("wss") ? 443 : 80);
        InetAddress resolved = InetAddress.getByName(host);
        if (!LlmGlobalSettingEntity.ADMIN_USER_ID.equals(userId) && (resolved.isAnyLocalAddress()
            || resolved.isLoopbackAddress() || resolved.isLinkLocalAddress()
            || resolved.isSiteLocalAddress() || resolved.isMulticastAddress())) {
          throw new BusinessException(ErrorCode.BAD_REQUEST, "语音服务测试仅支持公网地址");
        }
        java.net.InetSocketAddress address = new java.net.InetSocketAddress(resolved, port);
        java.net.Socket socket = new java.net.Socket();
        socket.connect(address, 5000);
        socket.close();
        return ProviderTestResult.builder()
            .success(true)
            .message("ASR 服务网络端口可达: " + host + "；尚未验证 API Key、模型权限及语音识别能力")
            .model(asr.getModel())
            .build();
      } catch (Exception e) {
        return ProviderTestResult.builder()
            .success(false)
            .message("ASR 连接失败: " + e.getMessage())
            .model(asr.getModel())
            .build();
      }
    } finally {
      rwLock.readLock().unlock();
    }
  }

  // ===== Write operations (write lock) =====

  @Transactional
  public void createProvider(Long userId, CreateProviderRequest request) {
    initializeUserProviders(userId);
    rwLock.writeLock().lock();
    try {
      if (!isDatabaseBacked()) {
        createProviderLegacy(request);
        return;
      }
      String providerId = trimOrNull(request.id());
      if (providerRepository.existsByIdAndUserId(providerId, userId)) {
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
      validateEmbeddingConfig(supportsEmbedding, embeddingModel, embeddingDimensions);

      ApiKeyEncryptionService.EncryptedValue encrypted = encryptionService.encrypt(apiKey);
      providerRepository.save(LlmProviderEntity.builder()
          .id(providerId)
          .userId(userId)
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
      registry.reload(userId);
      log.info("Created provider: id={}, baseUrl={}, model={}", providerId, baseUrl, model);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  @Transactional
  public void updateProvider(Long userId, String id, UpdateProviderRequest request) {
    rwLock.writeLock().lock();
    try {
      if (!isDatabaseBacked()) {
        updateProviderLegacy(id, request);
        return;
      }
      LlmProviderEntity provider = getProviderEntityOrThrow(userId, id);

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
      registry.reload(userId);
      log.info("Updated provider: id={}", id);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  @Transactional
  public void deleteProvider(Long userId, String id) {
    rwLock.writeLock().lock();
    try {
      if (!isDatabaseBacked()) {
        deleteProviderLegacy(id);
        return;
      }
      getProviderEntityOrThrow(userId, id);
      LlmGlobalSettingEntity setting = getGlobalSettingOrThrow(userId);
      if (id.equals(setting.getDefaultChatProviderId()) || id.equals(setting.getDefaultEmbeddingProviderId())) {
        throw new BusinessException(ErrorCode.PROVIDER_DEFAULT_CANNOT_DELETE,
            "默认 Provider '" + id + "' 不可删除，请先切换默认 Provider");
      }
      providerRepository.deleteById(new LlmProviderEntity.Key(userId, id));
      registry.reload(userId);
      log.info("Deleted provider: id={}", id);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  @Transactional
  public void updateDefaultProvider(Long userId, DefaultProviderDTO request) {
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
      requireApiKey(getProviderEntityOrThrow(userId, providerId));
      LlmGlobalSettingEntity setting = getGlobalSettingOrThrow(userId);
      setting.setDefaultChatProviderId(providerId);
      globalSettingRepository.save(setting);
      registry.reload(userId);
      log.info("Updated default provider: {}", providerId);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  @Transactional
  public void updateDefaultEmbeddingProvider(Long userId, DefaultProviderDTO request) {
    rwLock.writeLock().lock();
    try {
      String providerId = trimOrNull(request.defaultEmbeddingProvider());
      if (providerId == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "defaultEmbeddingProvider 不能为空");
      }
      LlmProviderEntity provider = getProviderEntityOrThrow(userId, providerId);
      String embeddingModel = trimOrNull(provider.getEmbeddingModel());
      if (!provider.isSupportsEmbedding() || embeddingModel == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST,
            "Provider '" + providerId + "' 不支持 Embedding，不能设为默认向量服务");
      }
      validateEmbeddingConfig(
          true,
          embeddingModel,
          resolveEmbeddingDimensions(provider.getEmbeddingDimensions()));
      requireApiKey(provider);
      LlmGlobalSettingEntity setting = getGlobalSettingOrThrow(userId);
      setting.setDefaultEmbeddingProviderId(providerId);
      globalSettingRepository.save(setting);
      registry.reload(userId);
      log.info("Updated default embedding provider: {}", providerId);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  @Transactional
  public void updateAsrConfig(Long userId, AsrConfigRequest request) {
    initializeUserProviders(userId);
    var setting = getGlobalSettingOrThrow(userId);
    var config = VOICE_JSON.readValue(setting.getAsrConfigJson(),
        VoiceInterviewProperties.AsrConfig.class);
    config.setApiKey(null);
    if (request.url() != null) config.setUrl(validateVoiceUrl(request.url()));
    if (request.model() != null) config.setModel(request.model());
    if (request.language() != null) config.setLanguage(request.language());
    if (request.format() != null) config.setFormat(request.format());
    if (request.sampleRate() != null) config.setSampleRate(request.sampleRate());
    if (request.enableTurnDetection() != null) config.setEnableTurnDetection(request.enableTurnDetection());
    if (request.turnDetectionType() != null) config.setTurnDetectionType(request.turnDetectionType());
    if (request.turnDetectionThreshold() != null) config.setTurnDetectionThreshold(request.turnDetectionThreshold());
    if (request.turnDetectionSilenceDurationMs() != null) config.setTurnDetectionSilenceDurationMs(request.turnDetectionSilenceDurationMs());
    if (trimOrNull(request.apiKey()) != null) {
      var key = encryptionService.encrypt(request.apiKey().trim());
      setting.setAsrApiKeyNonce(key.nonce());
      setting.setAsrApiKeyCiphertext(key.ciphertext());
    }
    setting.setAsrConfigJson(VOICE_JSON.writeValueAsString(config));
    globalSettingRepository.save(setting);
  }

  @Transactional
  public void updateTtsConfig(Long userId, TtsConfigRequest request) {
    initializeUserProviders(userId);
    var setting = getGlobalSettingOrThrow(userId);
    var config = VOICE_JSON.readValue(setting.getTtsConfigJson(),
        VoiceInterviewProperties.QwenTtsConfig.class);
    config.setApiKey(null);
    if (request.url() != null) config.setUrl(validateVoiceUrl(request.url()));
    if (request.model() != null) config.setModel(request.model());
    if (request.voice() != null) config.setVoice(request.voice());
    if (request.format() != null) config.setFormat(request.format());
    if (request.sampleRate() != null) config.setSampleRate(request.sampleRate());
    if (request.mode() != null) config.setMode(request.mode());
    if (request.languageType() != null) config.setLanguageType(request.languageType());
    if (request.speechRate() != null) config.setSpeechRate(request.speechRate());
    if (request.volume() != null) config.setVolume(request.volume());
    if (trimOrNull(request.apiKey()) != null) {
      var key = encryptionService.encrypt(request.apiKey().trim());
      setting.setTtsApiKeyNonce(key.nonce());
      setting.setTtsApiKeyCiphertext(key.ciphertext());
    }
    setting.setTtsConfigJson(VOICE_JSON.writeValueAsString(config));
    globalSettingRepository.save(setting);
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

  @Transactional
  public void initializeUserSettings(Long userId) {
    initializeUserProviders(userId);
  }

  private void initializeUserProviders(Long userId) {
    if (!isDatabaseBacked() || accounts == null) return;
    accounts.findByIdForUpdate(userId).orElseThrow(() ->
        new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录"));
    var setting = globalSettingRepository.findById(userId)
        .orElseGet(() -> LlmGlobalSettingEntity.builder().id(userId).build());
    initializeVoiceSettings(setting, userId);
    if (setting.isPresetsInitialized()) { globalSettingRepository.save(setting); return; }
    var existing = new HashSet<String>();
    providerRepository.findByUserIdOrderByIdAsc(userId).forEach(p -> existing.add(p.getId()));
    var presets = new ArrayList<LlmProviderEntity>();
    if (properties.getProviders() != null) {
      properties.getProviders().forEach((id, config) -> {
        if (!existing.contains(id) && config != null && trimOrNull(config.getBaseUrl()) != null
            && trimOrNull(config.getModel()) != null) {
          presets.add(LlmProviderEntity.builder().userId(userId).id(id)
              .baseUrl(config.getBaseUrl()).model(config.getModel())
              .temperature(config.getTemperature()).embeddingModel(config.getEmbeddingModel())
              .embeddingDimensions(config.getEmbeddingDimensions())
              .supportsEmbedding(Boolean.TRUE.equals(config.getSupportsEmbedding()) || trimOrNull(config.getEmbeddingModel()) != null)
              .enabled(true).builtin(true).build());
        }
      });
    }
    providerRepository.saveAll(presets);
    setting.setPresetsInitialized(true);
    globalSettingRepository.save(setting);
  }

  private void initializeVoiceSettings(LlmGlobalSettingEntity setting, Long userId) {
    boolean admin = LlmGlobalSettingEntity.ADMIN_USER_ID.equals(userId);
    if (setting.getAsrConfigJson() == null) {
      var defaults = new VoiceInterviewProperties.AsrConfig();
      BeanUtils.copyProperties(voiceProperties.getQwen().getAsr(), defaults);
      String key = defaults.getApiKey();
      defaults.setApiKey(null);
      setting.setAsrConfigJson(VOICE_JSON.writeValueAsString(defaults));
      if (admin && trimOrNull(key) != null) {
        var encrypted = encryptionService.encrypt(key);
        setting.setAsrApiKeyNonce(encrypted.nonce());
        setting.setAsrApiKeyCiphertext(encrypted.ciphertext());
      }
    }
    if (setting.getTtsConfigJson() == null) {
      var defaults = new VoiceInterviewProperties.QwenTtsConfig();
      BeanUtils.copyProperties(voiceProperties.getQwen().getTts(), defaults);
      String key = defaults.getApiKey();
      defaults.setApiKey(null);
      setting.setTtsConfigJson(VOICE_JSON.writeValueAsString(defaults));
      if (admin && trimOrNull(key) != null) {
        var encrypted = encryptionService.encrypt(key);
        setting.setTtsApiKeyNonce(encrypted.nonce());
        setting.setTtsApiKeyCiphertext(encrypted.ciphertext());
      }
    }
  }

  public VoiceInterviewProperties.AsrConfig getAsrRuntimeConfig(Long userId) {
    return loadAsrConfig(userId, true);
  }

  public VoiceInterviewProperties.QwenTtsConfig getTtsRuntimeConfig(Long userId) {
    return loadTtsConfig(userId, true);
  }

  public LocalDateTime getVoiceSettingsUpdatedAt(Long userId) {
    return getGlobalSettingOrThrow(userId).getUpdatedAt();
  }

  public void requireChatConfigured(Long userId, String provider) {
    registry.resolveChatProviderId(userId, provider);
  }

  public void requireVoiceConfigured(Long userId) {
    loadAsrConfig(userId, true);
    loadTtsConfig(userId, true);
  }

  private VoiceInterviewProperties.AsrConfig loadAsrConfig(Long userId, boolean requireKey) {
    var setting = globalSettingRepository.findById(userId).orElseThrow(() ->
        new BusinessException(ErrorCode.BAD_REQUEST, "请先配置语音服务和 API Key"));
    var config = setting.getAsrConfigJson() == null ? new VoiceInterviewProperties.AsrConfig()
        : VOICE_JSON.readValue(setting.getAsrConfigJson(), VoiceInterviewProperties.AsrConfig.class);
    config.setApiKey(decryptVoiceKey(setting.getAsrApiKeyNonce(), setting.getAsrApiKeyCiphertext()));
    if (requireKey && trimOrNull(config.getApiKey()) == null)
      throw new BusinessException(ErrorCode.BAD_REQUEST, "请先配置语音识别模型和 API Key");
    return config;
  }

  private VoiceInterviewProperties.QwenTtsConfig loadTtsConfig(Long userId, boolean requireKey) {
    var setting = globalSettingRepository.findById(userId).orElseThrow(() ->
        new BusinessException(ErrorCode.BAD_REQUEST, "请先配置语音服务和 API Key"));
    var config = setting.getTtsConfigJson() == null ? new VoiceInterviewProperties.QwenTtsConfig()
        : VOICE_JSON.readValue(setting.getTtsConfigJson(), VoiceInterviewProperties.QwenTtsConfig.class);
    config.setApiKey(decryptVoiceKey(setting.getTtsApiKeyNonce(), setting.getTtsApiKeyCiphertext()));
    if (requireKey && trimOrNull(config.getApiKey()) == null)
      throw new BusinessException(ErrorCode.BAD_REQUEST, "请先配置语音合成模型和 API Key");
    return config;
  }

  private String decryptVoiceKey(String nonce, String ciphertext) {
    return ciphertext == null ? null : encryptionService.decrypt(nonce, ciphertext);
  }

  private String validateVoiceUrl(String value) {
    try {
      URI uri = URI.create(value.trim());
      if (!("wss".equalsIgnoreCase(uri.getScheme()) || "ws".equalsIgnoreCase(uri.getScheme()))
          || uri.getHost() == null || uri.getUserInfo() != null
          || uri.getRawQuery() != null || uri.getRawFragment() != null) {
        throw new IllegalArgumentException();
      }
      return uri.toString();
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "请填写有效的 WS/WSS 语音服务地址");
    }
  }

  private ProviderDTO toProviderDto(LlmProviderEntity provider, LlmGlobalSettingEntity setting) {
    boolean keyConfigured = hasApiKey(provider);
    String id = provider.getId();
    return ProviderDTO.builder()
        .id(id)
        .baseUrl(provider.getBaseUrl())
        .maskedApiKey(keyConfigured ? "已配置" : "未配置")
        .apiKeyConfigured(keyConfigured)
        .model(provider.getModel())
        .embeddingModel(provider.getEmbeddingModel())
        .embeddingDimensions(resolveEmbeddingDimensions(provider.getEmbeddingDimensions()))
        .supportsEmbedding(provider.isSupportsEmbedding())
        .temperature(provider.getTemperature())
        .defaultChatProvider(id.equals(setting.getDefaultChatProviderId()))
        .defaultEmbeddingProvider(id.equals(setting.getDefaultEmbeddingProviderId()))
        .build();
  }

  private boolean hasApiKey(LlmProviderEntity provider) {
    return trimOrNull(decryptApiKey(provider)) != null;
  }

  private void requireApiKey(LlmProviderEntity provider) {
    if (!hasApiKey(provider)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "请先配置模型和 API Key");
    }
  }

  private LlmProviderEntity getProviderEntityOrThrow(Long userId, String id) {
    return providerRepository.findByIdAndUserId(id, userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.PROVIDER_NOT_FOUND,
            "Provider '" + id + "' 不存在"));
  }

  private LlmGlobalSettingEntity getGlobalSettingOrThrow(Long userId) {
    return globalSettingRepository.findById(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
            "默认 Provider 配置未初始化"));
  }

  private ProviderRuntimeConfig getProviderRuntimeConfigOrThrow(Long userId, String id) {
    LlmProviderEntity provider = getProviderEntityOrThrow(userId, id);
    requireApiKey(provider);
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
    if (provider.getApiKeyCiphertext() == null || provider.getApiKeyCiphertext().isBlank()) return "";
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

  private void validateEmbeddingConfig(
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
    // 模型名和自定义别名不能证明能力；实际支持情况由 Embedding 接口确认。
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

  private String toEnvKey(String providerId) {
    return "PROVIDER_" + providerId.toUpperCase().replace("-", "_") + "_API_KEY";
  }

  // ===== Provider test logic (called under read lock) =====

  private ProviderTestResult doTestProvider(ProviderRuntimeConfig config, String id, Long userId) {
    try {
      InetAddressFilter addressFilter = InetAddressFilter.externalAddresses();
      if (LlmGlobalSettingEntity.ADMIN_USER_ID.equals(userId)) {
        addressFilter = addressFilter.or(InetAddressFilter.adapt(InetAddress::isLoopbackAddress))
            .or("198.18.0.0/15");
      }
      HttpClientSettings settings = HttpClientSettings.defaults()
          .withConnectTimeout(Duration.ofSeconds(5))
          .withReadTimeout(Duration.ofSeconds(10))
          .withRedirects(HttpRedirects.DONT_FOLLOW)
          .withInetAddressFilter(addressFilter);

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
