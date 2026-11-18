package com.infinitecanvas.canvas.infra.prompt;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 归一化之后的一条提示词：字段已经是"能直接入库的形态"（相对 URL 补全、长度截断、
 * 时间解析成 {@link LocalDateTime}），但还没序列化成 JSON 字符串。
 *
 * <p>和数据库实体分开是刻意的：解析关心"源里有什么"，入库关心"表里存什么"，
 * 中间隔一层，以后换表结构不用改解析逻辑。
 */
public record ParsedPrompt(

        String promptKey,
        String title,
        String prompt,
        String description,
        String coverUrl,
        List<String> referenceImageUrls,
        List<String> tags,
        String preview,
        String author,
        String sourceUrl,
        String imageMode,
        String imageModel,
        String imageSize,
        Integer imageCount,
        LocalDateTime remoteCreatedAt,
        LocalDateTime remoteUpdatedAt
) {
}
