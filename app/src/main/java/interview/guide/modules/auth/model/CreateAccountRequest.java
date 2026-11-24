package interview.guide.modules.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
    @NotBlank(message = "请输入账号") @Size(max = 64, message = "账号最多 64 个字符") String username,
    @NotBlank(message = "请输入密码") @Size(min = 8, max = 256, message = "密码长度需在 8–256 个字符之间") String password
) {}
