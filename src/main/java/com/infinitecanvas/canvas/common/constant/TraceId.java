package com.infinitecanvas.canvas.common.constant;

import org.slf4j.MDC;

/**
 * 链路追踪标识：请求头名、MDC key 与读取入口集中在这里，避免各处写魔法字符串。
 */
public final class TraceId {

    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";
    private static final String EMPTY = "-";

    private TraceId() {
    }

    /** 读取当前线程的 traceId，非 Web 线程（如定时任务）返回占位符，保证响应字段不为 null。 */
    public static String current() {
        String value = MDC.get(MDC_KEY);
        return value == null || value.isBlank() ? EMPTY : value;
    }
}
