package com.infinitecanvas.canvas.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "孤儿文件清理结果")
public record CleanupResultVO(

        @Schema(description = "扫描到的孤儿文件数（受单次上限 500 限制）")
        int scanned,

        @Schema(description = "实际删掉的磁盘文件数。同一份内容被别的用户还引用着时不删磁盘，只删记录")
        int removedFiles,

        @Schema(description = "释放的字节数")
        long freedBytes,

        @Schema(description = "是否还有剩余未处理，true 表示可以再调一次")
        boolean hasMore
) {
}
