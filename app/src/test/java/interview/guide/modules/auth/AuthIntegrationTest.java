package interview.guide.modules.auth;

import interview.guide.common.config.CorsConfig;
import interview.guide.common.config.CorsProperties;
import interview.guide.common.config.SecurityConfig;
import interview.guide.common.exception.GlobalExceptionHandler;
import interview.guide.modules.auth.model.LoginRequest;
import interview.guide.modules.auth.service.AuthService;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitWebConfig(AuthIntegrationTest.WebConfig.class)
class AuthIntegrationTest {
  private static final String TEST_PASSWORD = UUID.randomUUID().toString();
  private static final String LOGIN_PATH = "/api/auth/login";
  private static final String SESSION_PATH = "/api/auth/session";

  @Autowired
  private WebApplicationContext context;

  @Autowired
  private ObjectMapper mapper;

  private MockMvc mvc;

  @DynamicPropertySource
  static void authProperties(DynamicPropertyRegistry registry) {
    registry.add("app.auth.username", () -> "admin");
    registry.add("app.auth.password", () -> TEST_PASSWORD);
  }

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @DisplayName("未登录时拒绝访问业务接口、会话和语音 WebSocket")
  void deniesAnonymousRequests() throws Exception {
    mvc.perform(get("/api/interview-schedule"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
    mvc.perform(post("/api/interview-schedule")).andExpect(status().isUnauthorized());
    mvc.perform(get(SESSION_PATH)).andExpect(status().isUnauthorized());
    mvc.perform(get("/ws/voice-interview/42").header("Upgrade", "websocket"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("登录校验 CSRF 和密码")
  void validatesLoginRequest() throws Exception {
    mvc.perform(post(LOGIN_PATH).contentType("application/json").content(loginBody(TEST_PASSWORD)))
        .andExpect(status().isForbidden());

    var anonymous = csrf(null);
    mvc.perform(post(LOGIN_PATH).session(anonymous.session())
            .header(anonymous.headerName(), anonymous.token())
            .contentType("application/json").content(loginBody("wrong-password")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(401))
        .andExpect(jsonPath("$.message").value("账号或密码错误"));
    mvc.perform(get(SESSION_PATH).session(anonymous.session()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("登录更换会话和 CSRF 令牌，退出后会话失效")
  void authenticatesAndLogsOut() throws Exception {
    var anonymous = csrf(null);
    String preLoginSessionId = anonymous.session().getId();

    var login = mvc.perform(post(LOGIN_PATH).session(anonymous.session())
            .header(anonymous.headerName(), anonymous.token())
            .contentType("application/json").content(loginBody(TEST_PASSWORD)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value("admin"))
        .andReturn();

    var session = (MockHttpSession) login.getRequest().getSession(false);
    assertThat(session.getId()).isNotEqualTo(preLoginSessionId);
    mvc.perform(get(SESSION_PATH).session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value("admin"));

    mvc.perform(post("/api/auth/logout").session(session)
            .header(anonymous.headerName(), anonymous.token()))
        .andExpect(status().isForbidden());

    var refreshed = csrf(session);
    mvc.perform(post("/api/auth/logout").session(session)
            .header(refreshed.headerName(), refreshed.token()))
        .andExpect(status().isOk())
        .andExpect(header().string("Set-Cookie", containsString("INTERVIEW_SESSION=")))
        .andExpect(jsonPath("$.code").value(200));
    assertThat(session.isInvalid()).isTrue();
    mvc.perform(get(SESSION_PATH).cookie(new Cookie("INTERVIEW_SESSION", session.getId())))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("受信任的前端可以执行带凭证的登录预检")
  void allowsCorsPreflight() throws Exception {
    mvc.perform(options(LOGIN_PATH).header("Origin", "http://localhost:5173")
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "content-type,x-csrf-token"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
        .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
  }

  private CsrfSession csrf(MockHttpSession existing) throws Exception {
    var request = get("/api/auth/csrf");
    if (existing != null) {
      request.session(existing);
    }
    var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
    var response = mapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    return new CsrfSession((MockHttpSession) result.getRequest().getSession(false),
        response.path("headerName").asString(), response.path("token").asString());
  }

  private String loginBody(String password) {
    return mapper.writeValueAsString(new LoginRequest("admin", password));
  }

  private record CsrfSession(MockHttpSession session, String headerName, String token) {}

  @TestConfiguration(proxyBeanMethods = false)
  @EnableWebMvc
  @EnableConfigurationProperties(CorsProperties.class)
  @Import({SecurityConfig.class, CorsConfig.class, AuthController.class, AuthService.class,
      GlobalExceptionHandler.class})
  static class WebConfig {
    @Bean
    ObjectMapper mapper() {
      return JsonMapper.builder().build();
    }
  }
}
