package com.infinitecanvas.canvas.security;

import java.time.Instant;

/**
 * 认证通过后放进 SecurityContext 的主体。
 * 只放"当前请求用得到"的信息，不放 Entity：避免密码哈希这类敏感字段被无意带进日志或序列化。
 * tokenId / tokenExpiresAt 是为了登出时能把当前 access token 精准拉黑。
 */
public record LoginUser(Long id, String username, String tokenId, Instant tokenExpiresAt) {
}
