package com.infinitecanvas.canvas.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 提示词列表出参。结构照抄前端 {@code PromptListResponse}：
 * 一次请求同时带回当页数据、可选的标签、可选的分栏，避免前端为了画筛选器再发两个请求。
 *
 * <p>注意 {@code tags} 是"忽略标签筛选"算出来的：勾上「人像」之后，
 * 其他标签仍然要留在列表里，否则多选就断了。
 */
@Schema(description = "提示词分页结果")
public record PromptListVO(

        List<PromptVO> items,

        @Schema(description = "标签云，已按字母序去重")
        List<String> tags,

        @Schema(description = "分栏（= 启用中的源名称）")
        List<String> categories,

        long total,

        long page,

        long pageSize
) {
}
