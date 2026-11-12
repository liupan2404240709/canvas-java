package com.infinitecanvas.canvas.common.constant;

/**
 * 接口路径常量。context-path 已经在 application.yml 里配置为 /api，
 * 所以这里只写版本段，最终对外路径是 /api/v1/xxx。
 */
public final class ApiPath {

    public static final String V1 = "/v1";

    private ApiPath() {
    }
}
