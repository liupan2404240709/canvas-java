package com.infinitecanvas.canvas.common.constant;

/**
 * 接口路径常量。context-path 已经在 application.yml 里配置为 /api，
 * 所以这里只写版本段，最终对外路径是 /api/v1/xxx。
 */
public final class ApiPath {

    public static final String V1 = "/v1";

    public static final String AUTH = V1 + "/auth";

    public static final String USERS = V1 + "/users";

    public static final String PROJECTS = V1 + "/projects";

    public static final String FILES = V1 + "/files";

    public static final String ASSETS = V1 + "/assets";

    /**
     * 静态资源映射前缀。注意它**不经过 controller**，而是由 WebMvcConfig 的
     * ResourceHandler 直接读磁盘，所以路径里不带 /v1。
     * 对外真实地址还要拼上 context-path，即 /api/media/xxx。
     */
    public static final String MEDIA = "/media";

    private ApiPath() {
    }
}
