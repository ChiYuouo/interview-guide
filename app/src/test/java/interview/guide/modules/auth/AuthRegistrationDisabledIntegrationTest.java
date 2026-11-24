package interview.guide.modules.auth;

import interview.guide.modules.auth.model.LoginRequest;
import interview.guide.modules.auth.model.RegisterRequest;
import interview.guide.modules.auth.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitWebConfig(AuthIntegrationTest.WebConfig.class)
class AuthRegistrationDisabledIntegrationTest {
  private static final String ADMIN_PASSWORD = "Admin123";
  private static final String CONFIG_PATH = "/api/auth/config";
  private static final String LOGIN_PATH = "/api/auth/login";
  private static final String REGISTER_PATH = "/api/auth/register";
  private static final String SESSION_PATH = "/api/auth/session";

  @Autowired
  private WebApplicationContext context;

  @Autowired
  private ObjectMapper mapper;

  @Autowired
  private UserAccountRepository accounts;

  private MockMvc mvc;

  @DynamicPropertySource
  static void authProperties(DynamicPropertyRegistry registry) {
    registry.add("app.auth.username", () -> "admin");
    registry.add("app.auth.password", () -> ADMIN_PASSWORD);
    registry.add("app.auth.registration-enabled", () -> "false");
  }

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @DisplayName("关闭注册开关时配置返回 false 且注册接口返回 403，不影响 8 位管理员登录")
  void blocksRegistrationWhenDisabled() throws Exception {
    mvc.perform(get(CONFIG_PATH))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.registrationEnabled").value(false));

    mvc.perform(post(REGISTER_PATH)
            .contentType("application/json")
            .content(mapper.writeValueAsString(new RegisterRequest("blocked", "Pass1234"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    var csrfResult = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
    var csrfData = mapper.readTree(csrfResult.getResponse().getContentAsByteArray()).path("data");
    var session = (MockHttpSession) csrfResult.getRequest().getSession(false);

    mvc.perform(post(REGISTER_PATH)
            .session(session)
            .header(csrfData.path("headerName").asString(), csrfData.path("token").asString())
            .contentType("application/json")
            .content(mapper.writeValueAsString(new RegisterRequest("blocked", "Pass1234"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(403))
        .andExpect(jsonPath("$.message").value("当前未开放账号注册"));

    assertThat(accounts.findByUsername("blocked")).isEmpty();
    mvc.perform(get(SESSION_PATH).session(session)).andExpect(status().isUnauthorized());

    mvc.perform(post(LOGIN_PATH)
            .session(session)
            .header(csrfData.path("headerName").asString(), csrfData.path("token").asString())
            .contentType("application/json")
            .content(mapper.writeValueAsString(new LoginRequest("admin", ADMIN_PASSWORD))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.username").value("admin"))
        .andExpect(jsonPath("$.data.admin").value(true));
  }
}
