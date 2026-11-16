package com.infinitecanvas.canvas.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发与解析。整个项目只有这一个地方碰 io.jsonwebtoken，
 * 以后换库（比如 Nimbus）只改这个类。
 *
 * 用 HS256 对称签名：单体服务自己签自己验，不需要公钥分发。
 * 如果以后有多个服务都要验 token，再换成 RS256 并把公钥公布出去。
 */
@Component
public class JwtService {

    public static final String CLAIM_TYPE = "type";
    public static final String CLAIM_USERNAME = "username";
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        // 密钥长度不够会在这里抛 WeakKeyException，属于启动期快速失败
        this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public IssuedToken issueAccessToken(Long userId, String username) {
        return build(userId, username, TYPE_ACCESS, properties.getAccessTokenTtl());
    }

    public IssuedToken issueRefreshToken(Long userId, String username) {
        return build(userId, username, TYPE_REFRESH, properties.getRefreshTokenTtl());
    }

    private IssuedToken build(Long userId, String username, String type, Duration ttl) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        String jti = UUID.randomUUID().toString().replace("-", "");
        String token = Jwts.builder()
                .id(jti)
                .issuer(properties.getIssuer())
                .subject(String.valueOf(userId))
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_TYPE, type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                // 显式指定 HS256。只写 signWith(key) 的话，jjwt 会按密钥长度自动选择：
                // 32~47 字节选 HS256、48~63 字节选 HS384、64 字节以上选 HS512。
                // 当前 secret 是 52 字节，不指定就会悄悄签成 HS384，头和文档对不上，也不好排查。
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, jti, expiresAt, ttl.toSeconds());
    }

    /**
     * 校验签名 + 签发者 + 过期时间，任何一项不满足都会抛 JwtException 的子类。
     * 调用方负责把它们翻译成业务码，这里只做协议层判断。
     */
    public Claims parse(String token) throws JwtException {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(properties.getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long userIdOf(Claims claims) {
        return Long.valueOf(claims.getSubject());
    }

    public String usernameOf(Claims claims) {
        return claims.get(CLAIM_USERNAME, String.class);
    }

    public String typeOf(Claims claims) {
        return claims.get(CLAIM_TYPE, String.class);
    }

    public Duration accessTokenTtl() {
        return properties.getAccessTokenTtl();
    }

    public Duration refreshTokenTtl() {
        return properties.getRefreshTokenTtl();
    }
}
