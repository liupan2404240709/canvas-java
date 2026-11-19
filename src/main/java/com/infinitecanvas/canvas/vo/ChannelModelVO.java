package com.infinitecanvas.canvas.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 渠道下的模型出参。
 *
 * <p><b>不输出数据库主键</b>：前端认模型靠"渠道键 + 模型名"，两者合起来就是唯一键，
 * 拿 id 还得再维持一层映射。这跟画布节点只暴露 {@code node_key} 是同一个道理。
 */
@Schema(description = "渠道模型")
public record ChannelModelVO(

        @Schema(description = "上游模型名")
        String name,

        @Schema(description = "image / video / text / audio")
        String capability,

        boolean enabled,

        int sortIndex,

        @Schema(description = "自定义请求脚本；没有则为空")
        String script
) {
}
