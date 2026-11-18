package com.infinitecanvas.canvas.infra.prompt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 第三方 JSON 解析与归一化。
 *
 * <p>上游是"公开仓库里手工维护的 JSON"，质量参差不齐，所以这里的原则是
 * <b>能救就救，救不了就跳过，整体格式不对才报错</b>：
 *
 * <ul>
 *   <li>缺 title 或 prompt 的条目直接丢弃（没有这两样就没有意义）；</li>
 *   <li>缺 id 的用 {@code {sourceKey}-{序号}} 补一个，保证幂等同步有键可用；</li>
 *   <li>数组字段可能给成字符串、null、数字，统一转成字符串数组；</li>
 *   <li>相对路径用源 URL 补成绝对地址，否则前端拿到 {@code ./images/a.png} 会 404；</li>
 *   <li>超长文本按列宽截断，避免一条脏数据让整批 INSERT 失败（严格模式会直接报错）。</li>
 * </ul>
 *
 * <p>单条数据的问题不应该让整个源失败——这是"批量同步"和"单条校验"最重要的区别。
 */
@Component
public class PromptJsonParser {

    /** 单源最大条数。防止某个源返回巨大数组，把一次 INSERT 撑爆 max_allowed_packet。 */
    private static final int MAX_ITEMS = 5000;

    private static final int MAX_KEY = 128;
    private static final int MAX_TITLE = 300;
    private static final int MAX_DESCRIPTION = 1000;
    private static final int MAX_PREVIEW = 2000;
    private static final int MAX_AUTHOR = 200;
    private static final int MAX_URL = 1024;
    private static final int MAX_TAG = 64;
    private static final int MAX_MODEL = 128;
    private static final int MAX_SHORT = 64;

    private final ObjectMapper objectMapper;

    public PromptJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<ParsedPrompt> parse(String body, String sourceKey, String sourceUrl) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new PromptSyncException("返回内容不是合法 JSON：" + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isArray()) {
            throw new PromptSyncException("返回内容不是 JSON 数组（顶层必须是 [ ... ]）");
        }

        List<ParsedPrompt> items = new ArrayList<>();
        // 用 LinkedHashSet 去重：同一个源里重复的 id 只保留第一条，顺序不变
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        int index = 0;
        for (JsonNode node : root) {
            if (items.size() >= MAX_ITEMS) {
                break;
            }
            if (node == null || !node.isObject()) {
                index++;
                continue;
            }
            String title = cut(text(node, "title").trim(), MAX_TITLE);
            String prompt = text(node, "prompt").trim();
            index++;
            if (title.isEmpty() || prompt.isEmpty()) {
                continue;
            }
            String key = cut(text(node, "id").trim(), MAX_KEY);
            if (key.isEmpty()) {
                key = sourceKey + "-" + String.format("%04d", index);
            }
            if (!seen.add(key)) {
                continue;
            }

            List<String> referenceImageUrls = stringArray(node, "referenceImageUrls").stream()
                    .map(url -> cut(PromptUrls.resolve(sourceUrl, url), MAX_URL))
                    .filter(url -> !url.isEmpty())
                    .toList();
            String coverUrl = cut(PromptUrls.resolve(sourceUrl, text(node, "coverUrl")), MAX_URL);
            if (coverUrl.isEmpty() && !referenceImageUrls.isEmpty()) {
                coverUrl = referenceImageUrls.get(0);
            }

            items.add(new ParsedPrompt(
                    key,
                    title,
                    prompt,
                    cut(text(node, "description").trim(), MAX_DESCRIPTION),
                    coverUrl,
                    referenceImageUrls,
                    stringArray(node, "tags").stream().map(tag -> cut(tag, MAX_TAG)).toList(),
                    cut(text(node, "preview"), MAX_PREVIEW),
                    cut(text(node, "author").trim(), MAX_AUTHOR),
                    cut(PromptUrls.resolve(sourceUrl, text(node, "sourceUrl")), MAX_URL),
                    cut(text(node, "imageMode").trim(), MAX_SHORT),
                    cut(text(node, "imageModel").trim(), MAX_MODEL),
                    cut(text(node, "imageSize").trim(), MAX_SHORT),
                    positiveInt(node.get("imageCount")),
                    parseDate(text(node, "createdAt")),
                    parseDate(text(node, "updatedAt"))
            ));
        }
        return items;
    }

    /** 字符串/数字都当文本取；对象、数组、null 一律当空串，不去猜结构。 */
    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.isValueNode() ? value.asText() : "";
    }

    private List<String> stringArray(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode item : value) {
            if (item == null || !item.isValueNode()) {
                continue;
            }
            String text = item.asText().trim();
            if (!text.isEmpty()) {
                values.add(text);
            }
        }
        return List.copyOf(values);
    }

    private Integer positiveInt(JsonNode value) {
        if (value == null || !value.isNumber()) {
            return null;
        }
        int number = value.asInt();
        return number > 0 ? number : null;
    }

    /**
     * 时间字段的兼容解析。上游可能给 ISO 带时区、不带时区、只有日期，甚至给个空串。
     * 解析不出来就返回 null（前端会显示空），而不是让整条数据失败。
     */
    private LocalDateTime parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            return OffsetDateTime.parse(value).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // 继续尝试下一种格式
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            // 继续
        }
        try {
            return LocalDate.parse(value).atStartOfDay();
        } catch (DateTimeParseException ignored) {
            // 继续
        }
        try {
            // 最后试一下毫秒时间戳（个别源会这么写）
            long epoch = Long.parseLong(value);
            return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epoch), ZoneId.systemDefault());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String cut(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
