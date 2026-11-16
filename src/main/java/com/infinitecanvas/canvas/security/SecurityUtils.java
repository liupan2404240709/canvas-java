package com.infinitecanvas.canvas.security;

import com.infinitecanvas.canvas.common.api.ResultCode;
import com.infinitecanvas.canvas.common.exception.BizException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 从 SecurityContext 取当前用户。Controller / Service 里不要自己去看 Header 或 HttpServletRequest，
 * 统一走这里，以后换成别的认证方式（比如网关透传用户）只改一处。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /** 取当前用户，未登录抛 401（正常流程走不到，Security 过滤链已经拦过一次）。 */
    public static LoginUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof LoginUser loginUser)) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        return loginUser;
    }

    public static Long currentUserId() {
        return currentUser().id();
    }
}
