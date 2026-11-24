package interview.guide.modules.auth.service;

import interview.guide.common.config.AuthProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.model.AccountPrincipal;
import interview.guide.modules.auth.model.AuthConfigResponse;
import interview.guide.modules.auth.model.LoginRequest;
import interview.guide.modules.auth.model.RegisterRequest;
import interview.guide.modules.auth.model.SessionResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {
  private final AuthenticationManager authenticationManager;
  private final SessionAuthenticationStrategy sessionStrategy;
  private final SecurityContextRepository contexts;
  private final AccountService accountService;
  private final AuthProperties authProperties;

  public AuthConfigResponse getConfig() {
    return new AuthConfigResponse(authProperties.isRegistrationEnabled());
  }

  public SessionResponse login(
      LoginRequest login, HttpServletRequest request, HttpServletResponse response) {
    Authentication authentication;
    try {
      authentication = authenticationManager.authenticate(
          UsernamePasswordAuthenticationToken.unauthenticated(login.username(), login.password()));
    } catch (BadCredentialsException e) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号或密码错误");
    }

    return establishSession(authentication, request, response);
  }

  public SessionResponse register(
      RegisterRequest register, HttpServletRequest request, HttpServletResponse response) {
    if (!authProperties.isRegistrationEnabled()) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "当前未开放账号注册");
    }
    var account = accountService.registerUser(register);
    account.eraseCredentials();
    var authentication = UsernamePasswordAuthenticationToken.authenticated(
        account, null, account.getAuthorities());
    return establishSession(authentication, request, response);
  }

  private SessionResponse establishSession(
      Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
    sessionStrategy.onAuthentication(authentication, request, response);
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    contexts.saveContext(context, request, response);
    var account = (AccountPrincipal) authentication.getPrincipal();
    return new SessionResponse(account.getUsername(), account.isAdmin());
  }
}
