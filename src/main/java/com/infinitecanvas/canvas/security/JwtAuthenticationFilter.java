package com.infinitecanvas.canvas.security;

import com.infinitecanvas.canvas.common.api.ResultCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 从 Authorization: Bearer xxx 里取 token，校验通过就把认证信息塞进 SecurityContext。
 *
 * 关键设计：**校验失败不直接返回 401，而是把失败原因挂在 request 上继续往下走**。
 * 原因有两个：
 * 1. 放行路径（/auth/login、/v1/health）带着一个过期 token 也应当能正常访问，
 *    在过滤器里直接拦死会让"token 过期后想重新登录都登不上"；
 * 2. 真正的拒绝交给后面的授权阶段，最终由 RestAuthenticationEntryPoint 统一输出 JSON，
 *    保证所有 401 的响应体结构一致。
 */
@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 认证失败原因，供 RestAuthenticationEntryPoint 决定返回哪个业务码。 */
    public static final String ATTR_AUTH_ERROR = JwtAuthenticationFilter.class.getName() + ".error";

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final TokenStore tokenStore;

    public JwtAuthenticationFilter(JwtService jwtService, TokenStore tokenStore) {
        this.jwtService = jwtService;
        this.tokenStore = tokenStore;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (token == null) {
            chain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = jwtService.parse(token);
            if (!JwtService.TYPE_ACCESS.equals(jwtService.typeOf(claims))) {
                // 有人拿 refresh token 当 access token 用，直接按无效处理
                request.setAttribute(ATTR_AUTH_ERROR, ResultCode.TOKEN_INVALID);
            } else if (tokenStore.isAccessTokenBlacklisted(claims.getId())) {
                request.setAttribute(ATTR_AUTH_ERROR, ResultCode.TOKEN_REVOKED);
            } else {
                LoginUser loginUser = new LoginUser(
                        jwtService.userIdOf(claims),
                        jwtService.usernameOf(claims),
                        claims.getId(),
                        claims.getExpiration().toInstant());

                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        loginUser, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (ExpiredJwtException e) {
            request.setAttribute(ATTR_AUTH_ERROR, ResultCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            // 签名不对、格式不对、issuer 不匹配都会走到这里，统一按无效处理，不向外暴露具体原因
            log.debug("token 解析失败: {}", e.getMessage());
            request.setAttribute(ATTR_AUTH_ERROR, ResultCode.TOKEN_INVALID);
        }

        chain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (!StringUtils.hasText(header) || !header.startsWith(PREFIX)) {
            return null;
        }
        String token = header.substring(PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
