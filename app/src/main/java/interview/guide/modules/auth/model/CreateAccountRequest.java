package interview.guide.modules.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
    @NotBlank(message = "请输入账号") @Size(max = 64) String username,
    @NotBlank(message = "请输入密码") @Size(min = 12, max = 256) String password
) {}
