package interview.guide.modules.auth.model;

import java.util.List;
import lombok.Getter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

@Getter
public final class AccountPrincipal extends User {
  private final Long userId;
  private final AccountRole role;

  public AccountPrincipal(UserAccountEntity account) {
    super(account.getUsername(), account.getPasswordHash(),
        List.of(new SimpleGrantedAuthority("ROLE_" + account.getRole().name())));
    this.userId = account.getId();
    this.role = account.getRole();
  }

  public boolean isAdmin() {
    return role == AccountRole.ADMIN;
  }
}
