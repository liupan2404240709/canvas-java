package com.infinitecanvas.canvas.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 更新素材，PATCH 语义：**只更新传了的字段**。
 *
 * <p>这里的"没传"和"传 null"区分不了（JSON 里都是 null），所以规则是：
 * 想清空某个文本字段就传空字符串，想清空标签就传 {@code []}。
 * {@code kind} 不允许改——换了类型等于换了整条素材，应该删了重建。
 */
@Schema(description = "更新素材请求，只传需要改的字段")
public record UpdateAssetRequest(

        @Schema(description = "类型，只能保持原值；这里保留字段是为了让前端回传整个对象时不报错")
        @Pattern(regexp = "text|image|video|audio", message = "素材类型只能是 text/image/video/audio")
        String kind,

        @Schema(description = "标题")
        @Size(max = 200, message = "标题最长 200 字符")
        String title,

        @Schema(description = "封面地址")
        @Size(max = 1024, message = "封面地址最长 1024 字符")
        String coverUrl,

        @Schema(description = "来源")
        @Size(max = 100, message = "来源最长 100 字符")
        String source,

        @Schema(description = "备注，传空字符串即清空")
        @Size(max = 500, message = "备注最长 500 字符")
        String note,

        @Schema(description = "文本正文，只对 kind=text 生效")
        String content,

        @Schema(description = "标签，传 [] 即清空")
        List<@Size(max = 32, message = "单个标签最长 32 字符") String> tags,

        @Schema(description = "扩展字段")
        JsonNode metadata
) {
}
