package com.infinitecanvas.canvas.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建提示词源。
 *
 * <p>{@code sourceKey} 不由客户端传：内置源用 registry 里的 id，自定义源由后端生成，
 * 客户端只需要关心"名字 + JSON 地址"。这和画布节点刚好相反——节点的 id 是前端生成的，
 * 因为它要在本地先渲染出来；而源是纯后端资源，前端拿到返回值再用。
 */
@Schema(description = "新建提示词源请求")
public record CreatePromptSourceRequest(

        @Schema(description = "源名称，同时作为提示词的分栏 category", example = "我的提示词")
        @NotBlank(message = "源名称不能为空")
        @Size(max = 100, message = "源名称最长 100 字符")
        String name,

        @Schema(description = "JSON 地址，必须是 http/https", example = "https://example.com/prompts.json")
        @NotBlank(message = "JSON 地址不能为空")
        @Size(max = 1024, message = "地址最长 1024 字符")
        String url,

        @Schema(description = "主页，可为空；用于提示词的「原始链接」兜底")
        @Size(max = 1024, message = "主页最长 1024 字符")
        String homepage,

        @Schema(description = "是否启用，默认 true")
        Boolean enabled,

        @Schema(description = "刷新间隔（分钟），0 表示只手动刷新", example = "30")
        @Min(value = 0, message = "刷新间隔不能为负")
        @Max(value = 10080, message = "刷新间隔最长 7 天")
        Integer refreshIntervalMinutes
) {
}
