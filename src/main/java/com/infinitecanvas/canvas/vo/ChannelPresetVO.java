package com.infinitecanvas.canvas.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 内置厂商预设，供前端"一键选厂商"。
 *
 * <p>它的价值是免掉三件事：查文档找 Base URL、猜该填哪种协议、手敲模型名。
 * 但<b>不含 Key</b>——Key 是用户自己的资产，预设只是脚手架。
 *
 * <p>{@code note} 是给用户看的一行灰字，专门用来装那些"必须提醒但又不适合放进代码逻辑"的事，
 * 比如"火山方舟的文本模型 ID 带日期后缀，请从控制台复制"。
 */
@Schema(description = "厂商预设")
public record ChannelPresetVO(

        @Schema(description = "预设标识，如 bailian / ark / zhipu")
        String key,

        @Schema(description = "厂商显示名")
        String name,

        @Schema(description = "推荐的 Base URL")
        String baseUrl,

        @Schema(description = "openai / gemini")
        String apiFormat,

        @Schema(description = "官网/控制台地址，方便去申请 Key")
        String homepage,

        @Schema(description = "一行提示")
        String note,

        @Schema(description = "推荐模型")
        List<Model> models
) {

    @Schema(description = "预设里的模型")
    public record Model(String name, String capability) {
    }
}
