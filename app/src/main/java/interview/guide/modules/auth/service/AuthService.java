package interview.guide.modules.auth.service;

import interview.guide.common.config.AuthProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.model.LoginRequest;
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
  private final AuthProperties properties;
  private final AuthenticationManager authenticationManager;
  private final SessionAuthenticationStrategy sessionStrategy;
  private final SecurityContextRepository contexts;

  public SessionResponse login(
      LoginRequest login, HttpServletRequest request, HttpServletResponse response) {
    if (!properties.hasCredentials()) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "管理员登录尚未配置");
    }

    Authentication authentication;
    try {
      authentication = authenticationManager.authenticate(
          UsernamePasswordAuthenticationToken.unauthenticated(login.username(), login.password()));
    } catch (BadCredentialsException e) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号或密码错误");
    }

    sessionStrategy.onAuthentication(authentication, request, response);
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    contexts.saveContext(context, request, response);
    return new SessionResponse(authentication.getName());
  }
}
