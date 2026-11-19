package com.infinitecanvas.canvas.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 保存渠道配置（PUT，局部语义：null = 本次不改）。
 *
 * <p>三个字段的"空"含义各不相同，必须写清楚，否则前端很容易传错：
 * <ul>
 *   <li>{@code apiKey} 为 null 或空串 = <b>保持原 Key 不变</b>。不能理解成"清空"，
 *       否则用户改个名字就会把 Key 弄丢；真要换 Key 就传新的明文。</li>
 *   <li>{@code models} 为 null = 不动模型列表；传空数组 = <b>清空</b>所有模型。
 *       这两个必须区分，因为"我只想改个名字"和"我把模型全删了"是完全不同的意图。</li>
 *   <li>{@code enabled} 为 null = 不改。</li>
 * </ul>
 *
 * <p>模型列表传上来时按名字做差量（新增 / 更新 / 删除），而不是先全删再全插：
 * 全删全插会让每条记录的 {@code created_at} 每次都变，也会把 {@code id} 换掉——
 * 后面阶段 AI 生成任务要引用"用户上次用的模型"，引用 id 比引用名字可靠。
 */
@Schema(description = "保存渠道请求（null = 不修改）")
public record UpdateChannelRequest(

        @Schema(description = "显示名")
        @Size(max = 100, message = "渠道名最长 100 字符")
        String name,

        @Schema(description = "接口根地址")
        @Size(max = 512, message = "接口地址最长 512 字符")
        String baseUrl,

        @Schema(description = "新的 API Key 明文；留空表示保持原值")
        @Size(max = 512, message = "API Key 最长 512 字符")
        String apiKey,

        @Schema(description = "接口协议：openai / gemini")
        String apiFormat,

        @Schema(description = "启用/停用")
        Boolean enabled,

        @Schema(description = "排序")
        Integer sortIndex,

        @Schema(description = "模型列表；null 表示不动，空数组表示清空")
        List<ChannelModelDTO> models
) {
}
