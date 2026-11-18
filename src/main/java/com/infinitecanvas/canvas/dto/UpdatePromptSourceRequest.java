package com.infinitecanvas.canvas.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 局部更新提示词源（PATCH 语义）：字段为 null 表示"这次不改"，不是"改成空"。
 *
 * <p>内置源只允许改 {@code enabled}（停用/启用），改 name/url 会返回 40941——
 * 内置源是所有用户共享的"上游地址"，谁都能改就等于谁都能把别人的库指向别处。
 */
@Schema(description = "更新提示词源请求")
public record UpdatePromptSourceRequest(

        @Schema(description = "新名称")
        @Size(max = 100, message = "源名称最长 100 字符")
        String name,

        @Schema(description = "新的 JSON 地址")
        @Size(max = 1024, message = "地址最长 1024 字符")
        String url,

        @Schema(description = "新主页")
        @Size(max = 1024, message = "主页最长 1024 字符")
        String homepage,

        @Schema(description = "启用/停用")
        Boolean enabled,

        @Schema(description = "刷新间隔（分钟），0 = 只手动刷新")
        @Min(value = 0, message = "刷新间隔不能为负")
        @Max(value = 10080, message = "刷新间隔最长 7 天")
        Integer refreshIntervalMinutes
) {
}
