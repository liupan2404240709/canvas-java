package com.infinitecanvas.canvas.common.api;

import java.util.List;

/**
 * 分页响应体。字段名与前端 web/src/services/api 里既有分页结构保持一致，
 * 后续对接时不至于因为 list/records/items 命名不同而返工。
 */
public record PageResult<T>(long page, long pageSize, long total, List<T> list) {

    public static <T> PageResult<T> empty(long page, long pageSize) {
        return new PageResult<>(page, pageSize, 0, List.of());
    }
}
