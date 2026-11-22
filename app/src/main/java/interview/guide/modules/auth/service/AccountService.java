package interview.guide.modules.auth.service;

import interview.guide.common.config.AuthProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.model.AccountPrincipal;
import interview.guide.modules.auth.model.AccountRole;
import interview.guide.modules.auth.model.CreateAccountRequest;
import interview.guide.modules.auth.model.UserAccountEntity;
import interview.guide.modules.auth.repository.UserAccountRepository;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccountService implements UserDetailsService {
  // 与 Flyway 保留的管理员 ID 一致，修改登录名时已有日程归属不变。
  private static final long ADMIN_ID = 1L;

  private final UserAccountRepository accounts;
  private final PasswordEncoder passwordEncoder;

  @Transactional
  public void configureAdmin(AuthProperties properties) {
    var admin = accounts.findById(ADMIN_ID)
        .filter(account -> account.getRole() == AccountRole.ADMIN)
        .orElseThrow(() -> new IllegalStateException("缺少管理员数据，请检查 Flyway 迁移"));
    if (!properties.hasCredentials()) {
      admin.setPasswordHash(null);
      return;
    }

    String username = properties.getUsername();
    String password = properties.getPassword();
    if (username == null || username.isBlank() || !username.equals(username.strip())
        || normalize(username).length() > 64 || password.length() < 12
        || password.length() > 256) {
      throw new IllegalStateException("请配置有效的管理员账号（1–64 字符）和密码（12–256 字符）");
    }
    String normalized = normalize(username);
    if (accounts.findByUsername(normalized).filter(other -> other.getId() != ADMIN_ID).isPresent()) {
      throw new IllegalStateException("管理员账号与已有普通账号重名");
    }
    admin.setUsername(normalized);
    if (admin.getPasswordHash() == null || !passwordEncoder.matches(password, admin.getPasswordHash())) {
      admin.setPasswordHash(passwordEncoder.encode(password));
    }
  }

  @Transactional
  public void createUser(CreateAccountRequest request) {
    String username = normalize(request.username());
    if (username.length() > 64) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "账号最多 64 个字符");
    }
    var account = new UserAccountEntity();
    account.setUsername(username);
    account.setPasswordHash(passwordEncoder.encode(request.password()));
    account.setRole(AccountRole.USER);
    try {
      accounts.saveAndFlush(account);
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "账号已存在");
    }
  }

  @Override
  @Transactional(readOnly = true)
  public AccountPrincipal loadUserByUsername(String username) {
    return accounts.findByUsername(normalize(username))
        .filter(account -> account.getPasswordHash() != null)
        .map(AccountPrincipal::new)
        .orElseThrow(() -> new UsernameNotFoundException("账号不存在"));
  }

  private static String normalize(String username) {
    return username.strip().toLowerCase(Locale.ROOT);
  }
}
