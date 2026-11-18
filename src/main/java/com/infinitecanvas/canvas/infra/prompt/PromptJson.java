package com.infinitecanvas.canvas.infra.prompt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 字符串数组 ↔ JSON 列的转换。
 *
 * <p>空数组统一写成 {@code []} 而不是 NULL：
 * <ul>
 *   <li>{@code JSON_CONTAINS} 遇到 NULL 会返回 NULL（既不是 true 也不是 false），
 *       在 WHERE 里等于不成立，容易写出"标签为空的素材永远查不到"这种坑；</li>
 *   <li>出参永远能返回数组，前端不用判空。</li>
 * </ul>
 */
public final class PromptJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PromptJson() {
    }

    public static String write(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        try {
            return MAPPER.writeValueAsString(values);
        } catch (Exception e) {
            return "[]";
        }
    }

    public static List<String> read(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = MAPPER.readValue(json, new TypeReference<List<String>>() {
            });
            return values == null ? List.of() : values;
        } catch (Exception e) {
            return List.of();
        }
    }
}
