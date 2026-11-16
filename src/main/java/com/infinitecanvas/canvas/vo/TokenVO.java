package com.infinitecanvas.canvas.vo;

/**
 * 令牌出参。accessToken 放 Authorization 头短期使用，
 * refreshToken 只在 /auth/refresh 用一次、用完立刻换新（轮换）。
 *
 * accessExpiresIn / refreshExpiresIn 单位是秒：前端可以用它提前 60 秒静默刷新，
 * 不必等到接口返回 401 才跳登录页。
 */
public record TokenVO(

        String accessToken,
        String tokenType,
        long accessExpiresIn,
        String refreshToken,
        long refreshExpiresIn,
        UserVO user
) {
}
