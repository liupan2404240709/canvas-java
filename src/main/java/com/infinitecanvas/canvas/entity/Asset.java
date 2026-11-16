package com.infinitecanvas.canvas.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 素材，对应 t_asset。和 {@link CanvasProject} 一样是"聚合根"：
 * 它自己就是一条完整的业务数据，媒体文件只是通过 file_id 借用的外部资源。
 *
 * <p>设计上把 bytes / mime_type / width / height 从 t_file 冗余过来，
 * 是为了列表页一次查询就能渲染卡片（显示"1920x1080 · 2.3MB · image/png"），
 * 不用 join 文件表。
 */
@Data
@TableName("t_asset")
public class Asset {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    /** text / image / video / audio。后端只做白名单校验，不做枚举映射。 */
    private String kind;

    private String title;

    private String coverUrl;

    private String source;

    private String note;

    /** 文本素材的正文。用 MEDIUMTEXT，一条提示词几千字也放得下。 */
    private String content;

    /** 媒体素材对应的 t_file.id；文本素材为 null。 */
    private Long fileId;

    /** 冗余自 t_file，列表页拼访问地址时不用 join。 */
    private String storageKey;

    private Integer width;

    private Integer height;

    private Long bytes;

    private String mimeType;

    private Integer durationMs;

    /** JSON 数组文本，如 {@code ["人像","暖色"]}。存 String 而不是 JsonNode，理由同节点 metadata。 */
    private String tagsJson;

    private String metadataJson;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
