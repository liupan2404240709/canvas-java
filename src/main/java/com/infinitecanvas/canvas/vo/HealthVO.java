package com.infinitecanvas.canvas.vo;

import java.time.LocalDateTime;

/**
 * 健康检查结果。
 *
 * @param app       服务名
 * @param version   版本号
 * @param status    整体状态：UP / DOWN
 * @param db        数据库探针
 * @param redis     缓存探针
 * @param checkedAt 检查时间
 */
public record HealthVO(String app, String version, String status, Component db, Component redis, LocalDateTime checkedAt) {

    /**
     * @param status    组件状态：UP / DOWN
     * @param latencyMs 探测耗时（毫秒）
     * @param error     失败原因，成功时为 null
     */
    public record Component(String status, long latencyMs, String error) {

        public static Component up(long latencyMs) {
            return new Component("UP", latencyMs, null);
        }

        public static Component down(long latencyMs, String error) {
            return new Component("DOWN", latencyMs, error);
        }
    }
}
