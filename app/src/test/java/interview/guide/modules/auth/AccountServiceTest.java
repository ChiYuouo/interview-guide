package interview.guide.modules.auth;

import interview.guide.common.config.AuthProperties;
import interview.guide.modules.auth.model.AccountRole;
import interview.guide.modules.auth.model.UserAccountEntity;
import interview.guide.modules.auth.repository.UserAccountRepository;
import interview.guide.modules.auth.service.AccountService;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {
  @Mock
  private UserAccountRepository accounts;

  @Mock
  private PasswordEncoder passwordEncoder;

  @InjectMocks
  private AccountService accountService;

  @Test
  @DisplayName("管理员密码少于 8 位时启动配置失败")
  void rejectsAdminPasswordShorterThanEightChars() {
    var admin = adminEntity();
    when(accounts.findById(1L)).thenReturn(Optional.of(admin));

    var properties = new AuthProperties();
    properties.setUsername("admin");
    properties.setPassword("1234567");

    assertThatThrownBy(() -> accountService.configureAdmin(properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("请配置有效的管理员账号（1–64 字符）和密码（8–256 字符）");
  }

  @Test
  @DisplayName("管理员密码为 8 位时允许完成配置")
  void acceptsEightCharAdminPassword() {
    var admin = adminEntity();
    when(accounts.findById(1L)).thenReturn(Optional.of(admin));
    when(accounts.findByUsername("admin")).thenReturn(Optional.of(admin));
    when(passwordEncoder.encode("Admin123")).thenReturn("encoded-hash");

    var properties = new AuthProperties();
    properties.setUsername("admin");
    properties.setPassword("Admin123");

    accountService.configureAdmin(properties);

    assertThat(admin.getUsername()).isEqualTo("admin");
    assertThat(admin.getPasswordHash()).isEqualTo("encoded-hash");
  }

  private static UserAccountEntity adminEntity() {
    var admin = new UserAccountEntity();
    admin.setId(1L);
    admin.setUsername("admin");
    admin.setRole(AccountRole.ADMIN);
    return admin;
  }
}
