package com.infinitecanvas.canvas.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.infinitecanvas.canvas.common.api.ResultCode;
import com.infinitecanvas.canvas.common.exception.BizException;
import com.infinitecanvas.canvas.dto.LoginRequest;
import com.infinitecanvas.canvas.dto.RegisterRequest;
import com.infinitecanvas.canvas.entity.User;
import com.infinitecanvas.canvas.mapper.UserMapper;
import com.infinitecanvas.canvas.security.IssuedToken;
import com.infinitecanvas.canvas.security.JwtService;
import com.infinitecanvas.canvas.security.LoginAttemptGuard;
import com.infinitecanvas.canvas.security.LoginUser;
import com.infinitecanvas.canvas.security.TokenStore;
import com.infinitecanvas.canvas.service.AuthService;
import com.infinitecanvas.canvas.vo.TokenVO;
import com.infinitecanvas.canvas.vo.UserVO;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenStore tokenStore;
    private final LoginAttemptGuard loginAttemptGuard;

    /**
     * 给"账号不存在"这条分支用的假哈希。它存在的唯一目的是让这次校验也走一遍 BCrypt，
     * 使"用户不存在"和"密码错误"的响应耗时接近，避免通过响应时间差枚举出哪些账号真实存在。
     */
    private final String dummyHash;

    public AuthServiceImpl(UserMapper userMapper,
                           PasswordEncoder passwordEncoder,
                           JwtService jwtService,
                           TokenStore tokenStore,
                           LoginAttemptGuard loginAttemptGuard) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.tokenStore = tokenStore;
        this.loginAttemptGuard = loginAttemptGuard;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Override
    public TokenVO register(RegisterRequest request) {
        String username = request.username().trim();
        String email = normalizeEmail(request.email());

        // 先查一次给出友好提示；真正的唯一性由数据库唯一索引保证，
        // 因为"查完再插"之间永远有并发窗口，只靠应用层判断挡不住重复注册。
        if (findByUsername(username) != null) {
            throw new BizException(ResultCode.USERNAME_TAKEN);
        }
        if (email != null && findByEmail(email) != null) {
            throw new BizException(ResultCode.EMAIL_TAKEN);
        }

        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setNickname(StringUtils.hasText(request.nickname()) ? request.nickname().trim() : username);
        user.setStatus(User.STATUS_ENABLED);

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发注册时后到的那一个会在这里被唯一索引拦下，翻译成同样的业务错误
            String message = e.getMessage() == null ? "" : e.getMessage();
            throw new BizException(message.contains("uk_user_email") ? ResultCode.EMAIL_TAKEN : ResultCode.USERNAME_TAKEN);
        }

        log.info("用户注册成功 userId={} username={}", user.getId(), user.getUsername());
        return issueTokens(user);
    }

    @Override
    public TokenVO login(LoginRequest request, String clientIp) {
        String account = request.account().trim();

        long lockedSeconds = loginAttemptGuard.lockedSeconds(account);
        if (lockedSeconds > 0) {
            throw new BizException(ResultCode.ACCOUNT_LOCKED,
                    "登录失败次数过多，请 " + Math.max(1, lockedSeconds / 60) + " 分钟后再试");
        }

        User user = findByAccount(account);

        // 账号不存在也要走一次 BCrypt 再返回同样的错误文案，避免被用来探测账号是否存在
        String hash = user == null ? dummyHash : user.getPasswordHash();
        boolean matched = passwordEncoder.matches(request.password(), hash);

        if (user == null || !matched) {
            int remaining = loginAttemptGuard.recordFailure(account);
            if (remaining <= 0) {
                throw new BizException(ResultCode.ACCOUNT_LOCKED,
                        "登录失败次数过多，请 " + (loginAttemptGuard.lockDuration().toMinutes()) + " 分钟后再试");
            }
            throw new BizException(ResultCode.BAD_CREDENTIALS, "用户名或密码错误，还可尝试 " + remaining + " 次");
        }

        if (!user.active()) {
            throw new BizException(ResultCode.ACCOUNT_DISABLED);
        }

        loginAttemptGuard.clear(account);
        touchLastLogin(user.getId(), clientIp);

        log.info("用户登录成功 userId={} username={} ip={}", user.getId(), user.getUsername(), clientIp);
        return issueTokens(user);
    }

    @Override
    public TokenVO refresh(String refreshToken) {
        Claims claims;
        try {
            claims = jwtService.parse(refreshToken);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BizException(ResultCode.TOKEN_INVALID);
        }

        // 类型不对说明拿的是 access token，必须拒绝：否则 access 过期后又能换出一对新的，等于永不过期
        if (!JwtService.TYPE_REFRESH.equals(jwtService.typeOf(claims))) {
            throw new BizException(ResultCode.TOKEN_INVALID);
        }

        String jti = claims.getId();
        Long owner = tokenStore.findRefreshTokenOwner(jti);
        if (owner == null) {
            // 签名有效但白名单里没有：已登出 / 已被轮换 / 被撤销。属于典型的 token 重放场景
            log.warn("refresh token 不在白名单中，疑似重放 jti={} userId={}", jti, claims.getSubject());
            throw new BizException(ResultCode.TOKEN_REVOKED);
        }

        User user = userMapper.selectById(owner);
        if (user == null || !user.active()) {
            tokenStore.revokeRefreshToken(jti);
            throw new BizException(ResultCode.ACCOUNT_DISABLED);
        }

        // 轮换：旧 refresh token 立刻作废，换一对新的。这样即使旧 token 泄露，也只能用一次
        tokenStore.revokeRefreshToken(jti);
        return issueTokens(user);
    }

    @Override
    public void logout(LoginUser current, String refreshToken) {
        // access token 本身没过期，只能靠黑名单提前失效；TTL 设成剩余寿命，到期自动清理
        tokenStore.blacklistAccessToken(current.tokenId(), current.tokenExpiresAt());

        if (StringUtils.hasText(refreshToken)) {
            try {
                Claims claims = jwtService.parse(refreshToken);
                Long owner = tokenStore.findRefreshTokenOwner(claims.getId());
                // 只允许撤销自己的 token，避免拿别人的 refresh token 来把别人踢下线
                if (owner != null && owner.equals(current.id())) {
                    tokenStore.revokeRefreshToken(claims.getId());
                }
            } catch (JwtException | IllegalArgumentException e) {
                // 登出必须幂等：refresh token 已过期或格式不对都不该报错，access token 拉黑已经生效
                log.debug("登出时忽略无效的 refreshToken: {}", e.getMessage());
            }
        }
    }

    private TokenVO issueTokens(User user) {
        IssuedToken access = jwtService.issueAccessToken(user.getId(), user.getUsername());
        IssuedToken refresh = jwtService.issueRefreshToken(user.getId(), user.getUsername());

        // 只有写进白名单的 refresh token 才能用来刷新，这是"可撤销"的基础
        tokenStore.saveRefreshToken(refresh.jti(), user.getId(), jwtService.refreshTokenTtl());

        return new TokenVO(
                access.token(),
                "Bearer",
                access.expiresInSeconds(),
                refresh.token(),
                refresh.expiresInSeconds(),
                UserVO.from(user));
    }

    private void touchLastLogin(Long userId, String clientIp) {
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .set(User::getLastLoginAt, LocalDateTime.now())
                .set(User::getLastLoginIp, clientIp));
    }

    private User findByAccount(String account) {
        // 用户名或邮箱都能登录；两者都有唯一索引，加上 limit 1 兜住"某人的用户名恰好是另一人的邮箱"的极端情况
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, account)
                .or()
                .eq(User::getEmail, account)
                .last("LIMIT 1"));
    }

    private User findByUsername(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username)
                .last("LIMIT 1"));
    }

    private User findByEmail(String email) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, email)
                .last("LIMIT 1"));
    }

    private String normalizeEmail(String email) {
        if (!StringUtils.hasText(email)) {
            return null;
        }
        // 邮箱统一小写存储，否则 A@x.com 和 a@x.com 会被当成两个账号，
        // 但唯一索引是大小写不敏感排序规则时又会撞键，行为不一致
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
