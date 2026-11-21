package interview.guide.modules.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
    @NotBlank(message = "请输入账号") @Size(max = 64) String username,
    @NotBlank(message = "请输入密码") @Size(max = 256) String password
) {}
