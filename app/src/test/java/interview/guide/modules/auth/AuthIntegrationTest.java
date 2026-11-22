package interview.guide.modules.auth;

import interview.guide.common.config.CorsConfig;
import interview.guide.common.config.CorsProperties;
import interview.guide.common.config.SecurityConfig;
import interview.guide.common.exception.GlobalExceptionHandler;
import interview.guide.modules.auth.model.LoginRequest;
import interview.guide.modules.auth.model.UserAccountEntity;
import interview.guide.modules.auth.repository.UserAccountRepository;
import interview.guide.modules.auth.service.AccountService;
import interview.guide.modules.auth.service.AuthService;
import interview.guide.modules.interviewschedule.InterviewScheduleController;
import interview.guide.modules.interviewschedule.model.InterviewScheduleEntity;
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository;
import interview.guide.modules.interviewschedule.service.InterviewParseService;
import interview.guide.modules.interviewschedule.service.InterviewScheduleService;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitWebConfig(AuthIntegrationTest.WebConfig.class)
class AuthIntegrationTest {
  private static final String TEST_PASSWORD = UUID.randomUUID().toString();
  private static final String LOGIN_PATH = "/api/auth/login";
  private static final String SESSION_PATH = "/api/auth/session";
  private static final String USERS_PATH = "/api/auth/users";
  private static final String SCHEDULE_PATH = "/api/interview-schedule";
  private static final int SCHEDULE_NOT_FOUND = 9001;

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
        .andExpect(jsonPath("$.data.admin").value(true))
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

  @Test
  @DisplayName("两个普通账号的日程相互隔离，旧日程归原管理员")
  void isolatesSchedules() throws Exception {
    var admin = csrf(login("admin"));
    createUser(admin, "alice");
    createUser(admin, "bob");
    var alice = csrf(login("alice"));
    var bob = csrf(login("bob"));

    mvc.perform(get(SESSION_PATH).session(alice.session()))
        .andExpect(jsonPath("$.data.admin").value(false));
    mvc.perform(get(SCHEDULE_PATH + "/1").session(admin.session()))
        .andExpect(jsonPath("$.data.companyName").value("迁移前日程"));
    mvc.perform(get(SCHEDULE_PATH + "/1").session(alice.session()))
        .andExpect(jsonPath("$.code").value(SCHEDULE_NOT_FOUND));

    long aliceId = createSchedule(alice, "Alice 的日程");
    long bobId = createSchedule(bob, "Bob 的日程");
    var alicePath = SCHEDULE_PATH + "/" + aliceId;
    var bobPath = SCHEDULE_PATH + "/" + bobId;

    mvc.perform(get(SCHEDULE_PATH).session(alice.session()))
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].id").value((int) aliceId));
    mvc.perform(get(SCHEDULE_PATH).session(bob.session()).param("status", "PENDING"))
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].id").value((int) bobId));
    mvc.perform(get(SCHEDULE_PATH).session(bob.session())
            .param("start", "2035-01-01T00:00:00").param("end", "2035-12-31T23:59:59"))
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].id").value((int) bobId));

    String aliceUserId = accounts.findByUsername("alice").orElseThrow().getId().toString();
    mvc.perform(get(alicePath).session(bob.session()).header("X-User-Id", aliceUserId))
        .andExpect(jsonPath("$.code").value(SCHEDULE_NOT_FOUND));
    mvc.perform(get(alicePath).session(admin.session()))
        .andExpect(jsonPath("$.code").value(SCHEDULE_NOT_FOUND));
    mvc.perform(put(alicePath).session(bob.session()).header(bob.headerName(), bob.token())
            .contentType("application/json").content(scheduleBody("跨账号修改")))
        .andExpect(jsonPath("$.code").value(SCHEDULE_NOT_FOUND));
    mvc.perform(patch(alicePath + "/status").session(bob.session())
            .header(bob.headerName(), bob.token()).param("status", "COMPLETED"))
        .andExpect(jsonPath("$.code").value(SCHEDULE_NOT_FOUND));
    mvc.perform(delete(alicePath).session(bob.session()).header(bob.headerName(), bob.token()))
        .andExpect(jsonPath("$.code").value(SCHEDULE_NOT_FOUND));
    mvc.perform(get(bobPath).session(alice.session()))
        .andExpect(jsonPath("$.code").value(SCHEDULE_NOT_FOUND));

    mvc.perform(get(alicePath).session(alice.session()))
        .andExpect(jsonPath("$.data.companyName").value("Alice 的日程"));
    mvc.perform(put(alicePath).session(alice.session()).header(alice.headerName(), alice.token())
            .contentType("application/json").content(scheduleBody("Alice 改后的日程")))
        .andExpect(jsonPath("$.data.companyName").value("Alice 改后的日程"));
    mvc.perform(patch(alicePath + "/status").session(alice.session())
            .header(alice.headerName(), alice.token()).param("status", "COMPLETED"))
        .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    mvc.perform(delete(alicePath).session(alice.session()).header(alice.headerName(), alice.token()))
        .andExpect(jsonPath("$.code").value(200));
    mvc.perform(get(alicePath).session(alice.session()))
        .andExpect(jsonPath("$.code").value(SCHEDULE_NOT_FOUND));
  }

  @Test
  @DisplayName("普通账号无法开户或访问尚未隔离的模块")
  void restrictsNormalAccounts() throws Exception {
    createUser(csrf(login("admin")), "trial");
    var trial = csrf(login("trial"));
    for (var path : new String[]{"/api/resumes", "/api/knowledgebase", "/api/llm-provider",
        "/api/interview/sessions", "/ws/voice-interview/42"}) {
      mvc.perform(get(path).session(trial.session())).andExpect(status().isForbidden());
    }
    mvc.perform(post(USERS_PATH).session(trial.session()).header(trial.headerName(), trial.token())
            .contentType("application/json").content(accountBody("unauthorized")))
        .andExpect(status().isForbidden());
  }

  private MockHttpSession login(String username) throws Exception {
    var anonymous = csrf(null);
    var response = mvc.perform(post(LOGIN_PATH).session(anonymous.session())
            .header(anonymous.headerName(), anonymous.token())
            .contentType("application/json").content(accountBody(username)))
        .andExpect(jsonPath("$.code").value(200))
        .andReturn();
    return (MockHttpSession) response.getRequest().getSession(false);
  }

  private void createUser(CsrfSession admin, String username) throws Exception {
    mvc.perform(post(USERS_PATH).session(admin.session()).header(admin.headerName(), admin.token())
            .contentType("application/json").content(accountBody(username)))
        .andExpect(jsonPath("$.code").value(200));
  }

  private long createSchedule(CsrfSession owner, String company) throws Exception {
    var result = mvc.perform(post(SCHEDULE_PATH).session(owner.session())
            .header(owner.headerName(), owner.token()).contentType("application/json")
            .content(scheduleBody(company)))
        .andExpect(jsonPath("$.code").value(200))
        .andReturn();
    return mapper.readTree(result.getResponse().getContentAsByteArray()).at("/data/id").asLong();
  }

  private String scheduleBody(String company) {
    return mapper.writeValueAsString(Map.of(
        "companyName", company, "position", "后端工程师", "interviewTime", "2035-04-01T10:00:00"));
  }

  private String accountBody(String username) {
    return mapper.writeValueAsString(new LoginRequest(username, TEST_PASSWORD));
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
  @EnableTransactionManagement(proxyTargetClass = true)
  @EnableJpaRepositories(basePackageClasses = {
      UserAccountRepository.class, InterviewScheduleRepository.class
  })
  @EnableConfigurationProperties(CorsProperties.class)
  @Import({SecurityConfig.class, CorsConfig.class, AuthController.class, AuthService.class,
      AccountService.class, GlobalExceptionHandler.class, InterviewScheduleController.class,
      InterviewScheduleService.class})
  static class WebConfig {
    @Bean
    SingleConnectionDataSource dataSource() {
      var dataSource = new SingleConnectionDataSource(
          "jdbc:h2:mem:auth-" + UUID.randomUUID() + ";MODE=PostgreSQL", "sa", "", true);
      var jdbc = new JdbcTemplate(dataSource);
      jdbc.execute("""
          CREATE TABLE interview_schedule (
            id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
            company_name VARCHAR(255) NOT NULL,
            position VARCHAR(255) NOT NULL,
            interview_time TIMESTAMP(6) NOT NULL,
            interview_type VARCHAR(255),
            meeting_link TEXT,
            round_number INTEGER,
            interviewer VARCHAR(255),
            notes TEXT,
            status VARCHAR(255) NOT NULL,
            created_at TIMESTAMP(6),
            updated_at TIMESTAMP(6)
          )
          """);
      jdbc.update("""
          INSERT INTO interview_schedule (company_name, position, interview_time, status)
          VALUES ('迁移前日程', 'Java', TIMESTAMP '2035-01-01 09:00:00', 'PENDING')
          """);
      new ResourceDatabasePopulator(new ClassPathResource(
          "db/migration/V20261122__isolate_interview_schedules.sql")).execute(dataSource);
      return dataSource;
    }

    @Bean
    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
      var factory = new LocalContainerEntityManagerFactoryBean();
      factory.setDataSource(dataSource);
      factory.setPackagesToScan(UserAccountEntity.class.getPackageName(),
          InterviewScheduleEntity.class.getPackageName());
      factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
      factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
      return factory;
    }

    @Bean
    JpaTransactionManager transactionManager(EntityManagerFactory factory) {
      return new JpaTransactionManager(factory);
    }

    @Bean
    InterviewParseService parseService() {
      return mock(InterviewParseService.class);
    }

    @Bean
    ObjectMapper mapper() {
      return JsonMapper.builder().build();
    }
  }
}
