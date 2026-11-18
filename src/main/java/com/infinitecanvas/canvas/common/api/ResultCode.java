package com.infinitecanvas.canvas.common.api;

import lombok.Getter;

/**
 * 业务错误码。code 给前端判断分支，httpStatus 给网关 / 浏览器 / 监控识别，
 * 两者分开，避免"业务失败一律返回 200"这种后期很难排查的写法。
 */
@Getter
public enum ResultCode {

    SUCCESS(0, "success", 200),
    BAD_REQUEST(40000, "请求参数有误", 400),
    UNAUTHORIZED(40100, "未登录或登录已过期", 401),
    FORBIDDEN(40300, "没有访问权限", 403),
    NOT_FOUND(40400, "资源不存在", 404),
    // 路径匹配上了但方法不对（比如把 POST 打到只有 GET 的地址）单独给 405：
    // 以前这种情况掉进兜底变 500，看起来像服务挂了，实际只是接口调错了
    METHOD_NOT_ALLOWED(40500, "请求方法不支持", 405),
    CONFLICT(40900, "资源状态冲突", 409),
    TOO_MANY_REQUESTS(42900, "请求过于频繁", 429),
    INTERNAL_ERROR(50000, "服务暂时不可用", 500),

    // ---- 账号体系 401xx：凭证问题。前端统一拦到登录页 ----
    BAD_CREDENTIALS(40110, "用户名或密码错误", 401),
    ACCOUNT_DISABLED(40111, "账号已被禁用", 401),
    TOKEN_INVALID(40112, "登录凭证无效，请重新登录", 401),
    TOKEN_EXPIRED(40113, "登录已过期，请重新登录", 401),
    TOKEN_REVOKED(40114, "登录凭证已失效，请重新登录", 401),

    // ---- 账号体系 409xx：注册冲突 ----
    USERNAME_TAKEN(40910, "用户名已被占用", 409),
    EMAIL_TAKEN(40911, "邮箱已被占用", 409),

    // ---- 账号体系 429xx：限流。和"请求太频繁"区分开，前端能提示"稍后再试" ----
    ACCOUNT_LOCKED(42910, "登录失败次数过多，请稍后再试", 429),

    // ---- 画布项目 404xx：资源不存在 ----
    // 统一返回 40400 也能用，但前端要区分"画布被删了"和"接口写错了"，单独给一个码能少一轮沟通
    PROJECT_NOT_FOUND(40410, "画布不存在或已被删除", 404),

    // ---- 画布项目 409xx：并发写冲突 ----
    // 前端收到这个码应该提示"画布已在其他窗口修改"，并重新拉快照，而不是直接覆盖
    PROJECT_VERSION_CONFLICT(40920, "画布已被其他窗口修改，请刷新后重试", 409),

    // ---- 文件与素材 400xx：上传内容不合法 ----
    // 这两类错误前端要给出"换张图"还是"压缩一下"的不同提示，所以分开
    UNSUPPORTED_FILE_TYPE(40020, "不支持的文件类型", 400),
    FILE_TOO_LARGE(40021, "文件超出大小限制", 400),
    FILE_EMPTY(40022, "文件内容为空", 400),

    // ---- 文件与素材 404xx ----
    FILE_NOT_FOUND(40430, "文件不存在或已被清理", 404),
    ASSET_NOT_FOUND(40431, "素材不存在或已被删除", 404),

    // ---- 文件与素材 409xx：文件还被引用，不能删 ----
    FILE_IN_USE(40930, "文件已被素材引用，请先删除对应素材", 409),

    // ---- 提示词库 400xx：源地址不合法 ----
    PROMPT_SOURCE_URL_INVALID(40040, "源地址必须是 http/https 的 JSON 地址", 400),

    // ---- 提示词库 404xx ----
    PROMPT_SOURCE_NOT_FOUND(40440, "提示词源不存在或已被删除", 404),

    // ---- 提示词库 409xx ----
    PROMPT_SOURCE_NAME_TAKEN(40940, "同名提示词源已存在", 409),
    PROMPT_SOURCE_READ_ONLY(40941, "内置源不能修改地址或删除，只能停用", 409),

    // ---- 提示词库 429xx：同一源正在同步 ----
    // 不排队等待：同步是用户手动触发的重操作，直接告诉他"正在同步"比让他干等 30 秒更好
    PROMPT_SOURCE_BUSY(42920, "该源正在同步中，请稍后再试", 429);

    private final int code;
    private final String message;
    private final int httpStatus;

    ResultCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}
