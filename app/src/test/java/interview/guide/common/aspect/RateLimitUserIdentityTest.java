package interview.guide.common.aspect;

import interview.guide.modules.auth.model.AccountPrincipal;
import interview.guide.modules.auth.model.AccountRole;
import interview.guide.modules.auth.model.UserAccountEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RateLimitUserIdentityTest {
  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  @DisplayName("用户限流按登录账号隔离且不能用请求头覆盖身份")
  void usesAuthenticatedAccount() {
    var request = new MockHttpServletRequest();
    request.addHeader("X-User-Id", "forged");
    request.setAttribute("userId", "another-user");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    var aspect = new RateLimitAspect(mock(RedissonClient.class));
    for (long id : new long[]{101, 102}) {
      var account = new UserAccountEntity();
      account.setId(id);
      account.setUsername("user-" + id);
      account.setPasswordHash("test-hash");
      account.setRole(AccountRole.USER);
      var principal = new AccountPrincipal(account);
      SecurityContextHolder.getContext().setAuthentication(
          UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
      assertThat((String) ReflectionTestUtils.invokeMethod(aspect, "getCurrentUserId"))
          .isEqualTo(Long.toString(id));
    }
  }

  @Test
  @DisplayName("未登录请求不能通过X-User-Id伪造用户限流身份")
  void ignoresUntrustedHeader() {
    var request = new MockHttpServletRequest();
    request.addHeader("X-User-Id", "forged");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    var aspect = new RateLimitAspect(mock(RedissonClient.class));
    assertThat((String) ReflectionTestUtils.invokeMethod(aspect, "getCurrentUserId"))
        .isEqualTo("anonymous");
  }
}
