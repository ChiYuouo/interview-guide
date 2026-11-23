package interview.guide.modules.llmprovider;

import com.sun.net.httpserver.HttpServer;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.SecurityConfig;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.GlobalExceptionHandler;
import interview.guide.modules.auth.model.AccountPrincipal;
import interview.guide.modules.auth.model.AccountRole;
import interview.guide.modules.auth.model.UserAccountEntity;
import interview.guide.modules.auth.repository.UserAccountRepository;
import interview.guide.modules.auth.service.AccountService;
import interview.guide.modules.llmprovider.controller.LlmProviderController;
import interview.guide.modules.llmprovider.dto.CreateProviderRequest;
import interview.guide.modules.llmprovider.dto.DefaultProviderDTO;
import interview.guide.modules.llmprovider.dto.UpdateProviderRequest;
import interview.guide.modules.llmprovider.dto.AsrConfigRequest;
import interview.guide.modules.llmprovider.dto.TtsConfigRequest;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import interview.guide.modules.llmprovider.service.ApiKeyEncryptionService;
import interview.guide.modules.llmprovider.service.LlmProviderConfigService;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.service.QwenAsrService;
import interview.guide.modules.voiceinterview.service.QwenTtsService;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import jakarta.persistence.EntityManagerFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitWebConfig(LlmProviderIsolationIntegrationTest.Config.class)
@DisplayName("原模型配置接口按账号隔离")
class LlmProviderIsolationIntegrationTest {
  @Autowired private WebApplicationContext context;
  @Autowired private UserAccountRepository accounts;
  @Autowired private LlmProviderRepository providers;
  @Autowired private LlmGlobalSettingRepository settings;
  @Autowired private LlmProviderConfigService service;
  @Autowired private LlmProviderRegistry registry;
  @Autowired private ObjectMapper mapper;
  @Autowired private DataSource source;
  private UserAccountEntity alice;
  private UserAccountEntity bob;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    registry.reload();
    settings.deleteAll();
    providers.deleteAll();
    accounts.deleteAll();
    new JdbcTemplate(source).execute("ALTER TABLE app_users ALTER COLUMN id RESTART WITH 1");
    account("admin", AccountRole.ADMIN);
    alice = account("alice", AccountRole.USER);
    bob = account("bob", AccountRole.USER);
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @DisplayName("原列表接口初始化五个独立预设，不复制管理员Key且不重复初始化")
  void initializesPresets() throws Exception {
    mvc.perform(get("/api/llm-provider/list").with(user(new AccountPrincipal(alice))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(5))
        .andExpect(jsonPath("$.data[0].apiKeyConfigured").value(false));
    assertThat(service.listProviders(alice.getId())).hasSize(5);
    assertThat(service.listProviders(bob.getId())).hasSize(5);
    assertThat(providers.findByUserIdOrderByIdAsc(alice.getId()))
        .allMatch(p -> p.getApiKeyCiphertext() == null && p.getApiKeyNonce() == null);
    assertThat(settings.findById(alice.getId()).orElseThrow().getDefaultChatProviderId()).isNull();
    service.deleteProvider(alice.getId(), "deepseek");
    assertThat(service.listProviders(alice.getId())).hasSize(4);
    assertThat(service.listProviders(bob.getId())).hasSize(5);
  }

  @Test
  @DisplayName("两个账号通过原接口添加同名Provider时各自读写并设置独立默认模型")
  void sameNameIsScoped() throws Exception {
    for (var account : List.of(alice, bob)) {
      mvc.perform(post("/api/llm-provider").with(user(new AccountPrincipal(account))).with(csrf())
              .contentType("application/json").content(mapper.writeValueAsString(
                  request("shared-name", account.getUsername() + "-secret", "https://example.com/v1"))))
          .andExpect(jsonPath("$.code").value(200));
      mvc.perform(put("/api/llm-provider/default-provider").with(user(new AccountPrincipal(account)))
              .with(csrf()).contentType("application/json")
              .content("{\"defaultProvider\":\"shared-name\"}"))
          .andExpect(jsonPath("$.code").value(200));
    }
    assertThat(providers.findByIdAndUserId("shared-name", alice.getId())).isPresent();
    assertThat(providers.findByIdAndUserId("shared-name", bob.getId())).isPresent();
    String response = mvc.perform(get("/api/llm-provider/shared-name")
            .with(user(new AccountPrincipal(alice))))
        .andExpect(jsonPath("$.data.apiKeyConfigured").value(true))
        .andExpect(jsonPath("$.data.maskedApiKey").value("已配置"))
        .andReturn().getResponse().getContentAsString();
    assertThat(response).doesNotContain("alice-secret", "bob-secret", "apiKeyCiphertext");
    service.updateProvider(bob.getId(), "kimi",
        new UpdateProviderRequest(null, "bob-kimi-key", null, null, null, null, null));
    service.updateDefaultProvider(bob.getId(), new DefaultProviderDTO("kimi"));
    assertThat(service.getDefaultProvider(alice.getId()).defaultProvider()).isEqualTo("shared-name");
    assertThat(service.getDefaultProvider(bob.getId()).defaultProvider()).isEqualTo("kimi");
  }

  @Test
  @DisplayName("其他账号的Provider无法通过查询修改删除或测试接口访问")
  void rejectsCrossAccountAccess() throws Exception {
    service.createProvider(alice.getId(), request("alice-only", "alice-secret", "https://example.com/v1"));
    var asBob = user(new AccountPrincipal(bob));
    mvc.perform(get("/api/llm-provider/alice-only").with(asBob))
        .andExpect(jsonPath("$.code").value(11001));
    mvc.perform(put("/api/llm-provider/alice-only").with(asBob).with(csrf())
            .contentType("application/json").content("{\"model\":\"stolen\"}"))
        .andExpect(jsonPath("$.code").value(11001));
    mvc.perform(delete("/api/llm-provider/alice-only").with(asBob).with(csrf()))
        .andExpect(jsonPath("$.code").value(11001));
    mvc.perform(post("/api/llm-provider/alice-only/test").with(asBob).with(csrf()))
        .andExpect(jsonPath("$.code").value(11001));
    assertThat(service.getProvider(alice.getId(), "alice-only").model()).isEqualTo("chat-model");
  }

  @Test
  @DisplayName("同名模型发出的HTTP调用分别携带各自Key，修改一方配置不会清空另一方缓存")
  void isolatesRuntimeAndCache() throws Exception {
    var headers = new CopyOnWriteArrayList<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      headers.add(exchange.getRequestHeaders().getFirst("Authorization"));
      exchange.getRequestBody().readAllBytes();
      byte[] body = "{\"id\":\"test\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"chat-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
      service.createProvider(alice.getId(), request("same-model", "alice-secret", base));
      service.createProvider(bob.getId(), request("same-model", "bob-secret", base));
      var a = registry.getPlainChatClient(alice.getId(), "same-model");
      var b = registry.getPlainChatClient(bob.getId(), "same-model");
      assertThat(a.prompt().user("Hi").call().content()).isEqualTo("OK");
      assertThat(b.prompt().user("Hi").call().content()).isEqualTo("OK");
      service.updateProvider(alice.getId(), "same-model",
          new UpdateProviderRequest(null, "alice-new-key", null, null, null, null, null));
      assertThat(registry.getPlainChatClient(bob.getId(), "same-model")).isSameAs(b);
      assertThat(registry.getPlainChatClient(alice.getId(), "same-model")).isNotSameAs(a);
      registry.getPlainChatClient(alice.getId(), "same-model").prompt().user("Hi").call().content();
      b.prompt().user("Hi").call().content();
      assertThat(headers).containsExactly("Bearer alice-secret", "Bearer bob-secret",
          "Bearer alice-new-key", "Bearer bob-secret");
    } finally {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("未配置Key不能测试或设默认且不会回退管理员聊天模型")
  void requiresOwnKey() throws Exception {
    service.listProviders(alice.getId());
    mvc.perform(post("/api/llm-provider/deepseek/test")
            .with(user(new AccountPrincipal(alice))).with(csrf()))
        .andExpect(jsonPath("$.message").value("请先配置模型和 API Key"));
    assertThatThrownBy(() -> service.updateDefaultProvider(alice.getId(), new DefaultProviderDTO("deepseek")))
        .isInstanceOf(BusinessException.class).hasMessage("请先配置模型和 API Key");
    assertThatThrownBy(() -> registry.getChatClientOrDefault(alice.getId(), null))
        .isInstanceOf(BusinessException.class).hasMessage("请先配置模型和 API Key");
  }

  @Test
  @DisplayName("语音和向量配置也按登录账号开放，写操作仍要求CSRF")
  void protectsOwnedConfiguration() throws Exception {
    mvc.perform(get("/api/llm-provider/list")).andExpect(status().isUnauthorized());
    var asAlice = user(new AccountPrincipal(alice));
    mvc.perform(post("/api/llm-provider").with(asAlice)).andExpect(status().isForbidden());
    mvc.perform(get("/api/llm-provider/voice/asr").with(asAlice))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.maskedApiKey").value("未配置"));
    mvc.perform(put("/api/llm-provider/default-embedding-provider").with(asAlice).with(csrf())
            .contentType("application/json").content("{}"))
        .andExpect(jsonPath("$.code").value(400));
    service.createProvider(alice.getId(), new CreateProviderRequest(
        "vector", "https://example.com", "secret", "chat", "embedding", 1024, true, 0.2));
    assertThat(service.getProvider(alice.getId(), "vector").supportsEmbedding()).isTrue();
  }

  private CreateProviderRequest request(String id, String key, String url) {
    return new CreateProviderRequest(id, url, key, "chat-model", null, null, false, 0.2);
  }

  @Test
  @DisplayName("Embedding同名配置和默认选择按账号隔离，实际请求携带各自Key")
  void embeddingUsesOwnedCredentials() throws Exception {
    var headers = new CopyOnWriteArrayList<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/embeddings", exchange -> {
      headers.add(exchange.getRequestHeaders().getFirst("Authorization"));
      exchange.getRequestBody().readAllBytes();
      byte[] response = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.1,0.2]}],\"model\":\"own-vector\",\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
      for (var account : List.of(alice, bob)) {
        service.createProvider(account.getId(), new CreateProviderRequest("same-vector", url,
            account.getUsername() + "-vector-key", "chat", "own-vector", 1024, true, 0.2));
        service.updateDefaultEmbeddingProvider(account.getId(), new DefaultProviderDTO(null, "same-vector"));
      }
      var a = registry.getDefaultEmbeddingModel(alice.getId());
      var b = registry.getDefaultEmbeddingModel(bob.getId());
      assertThat(a.embed("hello")).hasSize(2);
      assertThat(b.embed("hello")).hasSize(2);
      service.updateProvider(alice.getId(), "same-vector",
          new UpdateProviderRequest(null, "alice-vector-new", null, null, null, null, null));
      assertThat(registry.getDefaultEmbeddingModel(bob.getId())).isSameAs(b);
      registry.getDefaultEmbeddingModel(alice.getId()).embed("hello");
      b.embed("hello");
      assertThat(headers).containsExactly("Bearer alice-vector-key", "Bearer bob-vector-key",
          "Bearer alice-vector-new", "Bearer bob-vector-key");
    } finally {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("ASR和TTS分别加密保存，各账号与两种服务之间的Key互不覆盖")
  void speechSettingsAreIndependent() throws Exception {
    for (var account : List.of(alice, bob)) {
      service.getAsrConfig(account.getId());
      service.updateAsrConfig(account.getId(), new AsrConfigRequest(null, "own-asr",
          account.getUsername() + "-asr-key", null, null, null, null, null, null, null));
      service.updateTtsConfig(account.getId(), new TtsConfigRequest("own-tts",
          account.getUsername() + "-tts-key", "Cherry", null, null, null, null, null, null));
    }
    service.updateAsrConfig(alice.getId(), new AsrConfigRequest(null, null,
        "alice-asr-updated", null, null, null, null, null, null, null));
    assertThat(service.getAsrRuntimeConfig(alice.getId()).getApiKey()).isEqualTo("alice-asr-updated");
    assertThat(service.getAsrRuntimeConfig(bob.getId()).getApiKey()).isEqualTo("bob-asr-key");
    assertThat(service.getTtsRuntimeConfig(alice.getId()).getApiKey()).isEqualTo("alice-tts-key");
    assertThat(service.getTtsRuntimeConfig(bob.getId()).getApiKey()).isEqualTo("bob-tts-key");
    var persisted = settings.findById(alice.getId()).orElseThrow();
    assertThat(persisted.getAsrConfigJson()).doesNotContain("alice-asr");
    assertThat(persisted.getTtsConfigJson()).doesNotContain("alice-tts-key");
    assertThat(persisted.getAsrApiKeyCiphertext()).doesNotContain("alice-asr-updated");
    mvc.perform(get("/api/llm-provider/voice/asr").with(user(new AccountPrincipal(bob))))
        .andExpect(jsonPath("$.data.maskedApiKey").value("已配置"));
    service.updateTtsConfig(bob.getId(), new TtsConfigRequest(null, null, "Serena", null, null, null, null, null, null));
    assertThat(service.getTtsRuntimeConfig(bob.getId()).getApiKey()).isEqualTo("bob-tts-key");
  }

  @Test
  @DisplayName("语音SDK根据会话所属账号读取Key，实际WebSocket握手使用各自配置")
  void speechRuntimeUsesSessionOwner() throws Exception {
    var headers = new CopyOnWriteArrayList<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      headers.add(exchange.getRequestHeaders().getFirst("Authorization"));
      exchange.sendResponseHeaders(401, -1);
      exchange.close();
    });
    server.start();
    var repository = mock(VoiceInterviewSessionRepository.class);
    when(repository.findById(42L)).thenReturn(Optional.of(VoiceInterviewSessionEntity.builder()
        .userId(alice.getId()).id(42L).build()));
    when(repository.findById(43L)).thenReturn(Optional.of(VoiceInterviewSessionEntity.builder()
        .userId(bob.getId()).id(43L).build()));
    var properties = new VoiceInterviewProperties();
    properties.setTtsConnectTimeoutSeconds(1);
    var asr = new QwenAsrService(service, repository);
    var tts = new QwenTtsService(properties, service);
    try {
      String url = "ws://127.0.0.1:" + server.getAddress().getPort() + "/voice";
      for (var account : List.of(alice, bob)) {
        service.updateAsrConfig(account.getId(), new AsrConfigRequest(url, "test-asr",
            account.getUsername() + "-asr-key", null, null, null, null, null, null, null));
        service.updateTtsConfig(account.getId(), new TtsConfigRequest("test-tts",
            account.getUsername() + "-tts-key", null, null, null, null, null, null, null, url));
      }
      tts.synthesize(alice.getId(), "hello");
      tts.synthesize(bob.getId(), "hello");
      for (Long sessionId : List.of(42L, 43L)) {
        var failed = new CountDownLatch(1);
        asr.startTranscription(sessionId, text -> {}, error -> failed.countDown());
        assertThat(failed.await(5, TimeUnit.SECONDS)).isTrue();
      }
      assertThat(headers).contains("Bearer alice-tts-key", "Bearer bob-tts-key",
          "Bearer alice-asr-key", "Bearer bob-asr-key");
    } finally {
      asr.destroy();
      tts.destroy();
      server.stop(0);
    }
  }

  private UserAccountEntity account(String name, AccountRole role) {
    var account = new UserAccountEntity();
    account.setUsername(name);
    account.setPasswordHash("test-hash");
    account.setRole(role);
    return accounts.saveAndFlush(account);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  @EnableTransactionManagement(proxyTargetClass = true)
  @EnableJpaRepositories(basePackageClasses = {UserAccountRepository.class, LlmProviderRepository.class})
  @Import({SecurityConfig.class, GlobalExceptionHandler.class, LlmProviderController.class,
      LlmProviderConfigService.class, ApiKeyEncryptionService.class, LlmProviderRegistry.class})
  static class Config {
    @Bean DataSource source() {
      return new DriverManagerDataSource("jdbc:h2:mem:providers-" + UUID.randomUUID()
          + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
    }
    @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
      var factory = new LocalContainerEntityManagerFactoryBean();
      factory.setDataSource(source);
      factory.setPackagesToScan(UserAccountEntity.class.getPackageName(), LlmProviderEntity.class.getPackageName());
      factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
      factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
      return factory;
    }
    @Bean JpaTransactionManager transactionManager(EntityManagerFactory factory) {
      return new JpaTransactionManager(factory);
    }
    @Bean LlmProviderProperties providerProperties() {
      var properties = new LlmProviderProperties();
      properties.getSecurity().setApiKeyEncryptionKey("provider-isolation-test");
      properties.getAdvisors().setEnabled(false);
      properties.getAdvisors().setSafeguardEnabled(false);
      var presets = new LinkedHashMap<String, LlmProviderProperties.ProviderConfig>();
      for (String id : new String[]{"dashscope", "lmstudio", "kimi", "deepseek", "glm"}) {
        var config = new LlmProviderProperties.ProviderConfig();
        config.setBaseUrl("https://example.com/v1");
        config.setModel("chat-model");
        config.setApiKey("admin-only-secret");
        presets.put(id, config);
      }
      properties.setProviders(presets);
      return properties;
    }
    @Bean AccountService accountService() { return mock(AccountService.class); }
    @Bean VoiceInterviewProperties voiceProperties() { return new VoiceInterviewProperties(); }
    @Bean QwenAsrService asrService() { return mock(QwenAsrService.class); }
    @Bean QwenTtsService ttsService() { return mock(QwenTtsService.class); }
    @Bean ObjectMapper mapper() { return JsonMapper.builder().build(); }
  }
}
