package com.infinitecanvas.canvas.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 提示词出参，字段名严格对齐前端 {@code web/src/services/api/prompts.ts} 里的 {@code Prompt}。
 *
 * <p>三个字段容易被误解：
 * <ul>
 *   <li>{@code id} 是源内的 {@code prompt_key}，不是数据库主键；</li>
 *   <li>{@code category} 是源名称（前端用它做分栏），不是枚举；</li>
 *   <li>{@code githubUrl} 是该条的原链接，取不到时回落成源主页。</li>
 * </ul>
 */
@Schema(description = "提示词")
public record PromptVO(

        @Schema(description = "源内 id")
        String id,

        String title,

        String prompt,

        String description,

        String coverUrl,

        List<String> referenceImageUrls,

        List<String> tags,

        String preview,

        @Schema(description = "源里的创建时间，ISO 格式；解析不出则为空串")
        String createdAt,

        @Schema(description = "源里的更新时间，ISO 格式；解析不出则为空串")
        String updatedAt,

        String author,

        @Schema(description = "该条原始链接")
        String sourceUrl,

        @Schema(description = "所属源的业务键")
        String sourceId,

        @Schema(description = "所属源名称，前端当作 category 用")
        String category,

        @Schema(description = "githubUrl：sourceUrl 为空时回落成源主页")
        String githubUrl,

        String imageMode,

        String imageModel,

        String imageSize,

        Integer imageCount
) {
}
