package com.infinitecanvas.canvas.security;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 登录失败保护参数。默认 5 次 / 15 分钟，写在配置里而不是硬编码，
 * 后续想按环境调整（例如测试环境放开到 100 次）改 yml 即可。
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.login-protection")
public class LoginProtectionProperties {

    /** 窗口期内允许的连续失败次数，达到后锁定。 */
    @Min(1)
    private int maxAttempts = 5;

    /** 计数窗口，同时也是锁定时长：锁定到期后计数自动清零。 */
    @NotNull
    private Duration lockDuration = Duration.ofMinutes(15);
}
