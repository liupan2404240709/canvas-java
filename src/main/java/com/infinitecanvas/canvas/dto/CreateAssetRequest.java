package com.infinitecanvas.canvas.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建素材。
 *
 * <p>媒体素材（image/video/audio）传 {@code fileId}，尺寸和字节数由后端从文件记录里带出来，
 * **不接受客户端传**——客户端传的宽高和真实文件不一致时，前端卡片布局会错位，
 * 而且这类错误排查起来极难。
 *
 * <p>文本素材（text）传 {@code content}，此时 {@code fileId} 必须为空。
 */
@Schema(description = "创建素材请求")
public record CreateAssetRequest(

        @Schema(description = "类型：text / image / video / audio", example = "image")
        @NotBlank(message = "素材类型不能为空")
        @Pattern(regexp = "text|image|video|audio", message = "素材类型只能是 text/image/video/audio")
        String kind,

        @Schema(description = "标题", example = "赛博朋克街景")
        @NotBlank(message = "标题不能为空")
        @Size(max = 200, message = "标题最长 200 字符")
        String title,

        @Schema(description = "封面地址。图片素材不传时后端自动用文件地址填充")
        @Size(max = 1024, message = "封面地址最长 1024 字符")
        String coverUrl,

        @Schema(description = "来源", example = "手动")
        @Size(max = 100, message = "来源最长 100 字符")
        String source,

        @Schema(description = "备注")
        @Size(max = 500, message = "备注最长 500 字符")
        String note,

        @Schema(description = "文本正文，kind=text 时必填")
        String content,

        @Schema(description = "文件 id，媒体素材必填")
        Long fileId,

        @Schema(description = "标签数组", example = "[\"人像\",\"暖色\"]")
        List<@Size(max = 32, message = "单个标签最长 32 字符") String> tags,

        @Schema(description = "音视频时长（毫秒），前端能算就传，后端不解析容器")
        Integer durationMs,

        @Schema(description = "扩展字段，原样存取")
        JsonNode metadata
) {
}
