package com.infinitecanvas.canvas.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 提示词源出参。同时包含"配置"和"同步状态"两部分：
 * 前端配置页要显示每个源的条数、上次成功时间、上次错误，分成两个接口反而要多一轮请求。
 */
@Schema(description = "提示词源")
public record PromptSourceVO(

        @Schema(description = "业务键，前端当作 sourceId")
        String id,

        String name,

        String url,

        String homepage,

        boolean enabled,

        @Schema(description = "内置源：只能停用，不能改地址或删除")
        boolean builtIn,

        @Schema(description = "刷新间隔（分钟），0 = 只手动刷新")
        int refreshIntervalMinutes,

        @Schema(description = "上次同步后的条数")
        int count,

        @Schema(description = "最近一次成功时间，ISO 格式；从未成功则为空串")
        String lastSuccessAt,

        @Schema(description = "最近一次尝试时间，ISO 格式")
        String lastFetchedAt,

        @Schema(description = "最近一次失败原因，成功后为空串")
        String lastError
) {
}
