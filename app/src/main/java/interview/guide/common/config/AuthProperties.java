package interview.guide.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties("app.auth")
public class AuthProperties {
  private String username;
  private String password;
  private boolean registrationEnabled = true;

  public boolean hasCredentials() {
    return password != null && !password.isBlank();
  }
}
