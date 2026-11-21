package interview.guide.common.config;

import interview.guide.common.exception.ErrorCode;
import interview.guide.common.result.Result;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {
  @Bean
  AuthenticationManager authenticationManager(AuthProperties properties) {
    var users = new InMemoryUserDetailsManager();
    var encoder = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    if (properties.hasCredentials()) {
      String username = properties.getUsername();
      String password = properties.getPassword();
      if (username == null || username.isBlank() || username.length() > 64
          || !username.equals(username.strip()) || password.length() < 12
          || password.length() > 256) {
        throw new IllegalStateException("请配置有效的管理员账号（1–64 字符）和密码（12–256 字符）");
      }
      users.createUser(User.withUsername(username)
          .password(encoder.encode(password))
          .roles("ADMIN")
          .build());
    }
    var provider = new DaoAuthenticationProvider(users);
    provider.setPasswordEncoder(encoder);
    return new ProviderManager(provider);
  }

  @Bean
  SecurityContextRepository securityContextRepository() {
    return new DelegatingSecurityContextRepository(
        new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());
  }

  @Bean
  HttpSessionCsrfTokenRepository csrfTokenRepository() {
    return new HttpSessionCsrfTokenRepository();
  }

  @Bean
  SessionAuthenticationStrategy sessionAuthenticationStrategy(
      HttpSessionCsrfTokenRepository csrfTokens) {
    return new CompositeSessionAuthenticationStrategy(List.of(
        new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrfTokens)));
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      SecurityContextRepository contexts,
      HttpSessionCsrfTokenRepository csrfTokens,
      ObjectMapper mapper) throws Exception {
    return http
        .cors(Customizer.withDefaults())
        .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens))
        .securityContext(config -> config.securityContextRepository(contexts))
        .requestCache(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(auth -> auth
            .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
            .requestMatchers(HttpMethod.GET, "/api/auth/csrf", "/actuator/health").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
            .anyRequest().authenticated())
        .exceptionHandling(errors -> errors
            .authenticationEntryPoint((request, response, error) ->
                writeLoginRequired(response, mapper))
            .accessDeniedHandler((request, response, error) -> {
              if (error instanceof CsrfException
                  && SecurityContextHolder.getContext().getAuthentication() == null
                  && !(request.getContextPath() + "/api/auth/login").equals(request.getRequestURI())) {
                writeLoginRequired(response, mapper);
              } else {
                writeJson(response, mapper, 403, Result.error(ErrorCode.FORBIDDEN,
                    "请求校验失败，请刷新页面后重试"));
              }
            }))
        .logout(logout -> logout
            .logoutUrl("/api/auth/logout")
            .deleteCookies("INTERVIEW_SESSION")
            .logoutSuccessHandler((request, response, authentication) ->
                writeJson(response, mapper, 200, Result.success())))
        .build();
  }

  private static void writeLoginRequired(HttpServletResponse response, ObjectMapper mapper)
      throws IOException {
    writeJson(response, mapper, 401, Result.error(ErrorCode.UNAUTHORIZED, "请先登录"));
  }

  private static void writeJson(
      HttpServletResponse response, ObjectMapper mapper, int status, Result<?> result)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    mapper.writeValue(response.getOutputStream(), result);
  }
}
