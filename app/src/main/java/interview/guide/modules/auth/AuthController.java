package interview.guide.modules.auth;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.auth.model.AccountPrincipal;
import interview.guide.modules.auth.model.CreateAccountRequest;
import interview.guide.modules.auth.model.CsrfTokenResponse;
import interview.guide.modules.auth.model.LoginRequest;
import interview.guide.modules.auth.model.SessionResponse;
import interview.guide.modules.auth.service.AccountService;
import interview.guide.modules.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
  private final AuthService authService;
  private final AccountService accountService;

  @GetMapping("/csrf")
  public Result<CsrfTokenResponse> csrf(CsrfToken csrfToken) {
    return Result.success(new CsrfTokenResponse(csrfToken.getHeaderName(), csrfToken.getToken()));
  }

  @PostMapping("/login")
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 8, timeUnit = RateLimit.TimeUnit.MINUTES)
  @RateLimit(count = 30, timeUnit = RateLimit.TimeUnit.MINUTES)
  public Result<SessionResponse> login(
      @Valid @RequestBody LoginRequest login,
      HttpServletRequest request,
      HttpServletResponse response) {
    return Result.success(authService.login(login, request, response));
  }

  @GetMapping("/session")
  public Result<SessionResponse> session(@AuthenticationPrincipal AccountPrincipal account) {
    return Result.success(new SessionResponse(account.getUsername(), account.isAdmin()));
  }

  @PostMapping("/users")
  public Result<Void> createUser(@Valid @RequestBody CreateAccountRequest request) {
    accountService.createUser(request);
    return Result.success();
  }
}
