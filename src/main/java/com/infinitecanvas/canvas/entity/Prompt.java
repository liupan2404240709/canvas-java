package com.infinitecanvas.canvas.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 提示词，对应 t_prompt。隶属于 {@link PromptSource}，没有独立的增删改接口。
 *
 * <p>没有 deleted 列：提示词不存在"单条删除"的业务语义，用户能做的只有"同步源"，
 * 同步时用差量删除（源里没了的就删掉）。所以它和 t_canvas_node 一样，是附属数据。
 *
 * <p>JSON 字段（reference_image_urls / tags_json）同样存成 String，理由和
 * t_canvas_node.metadata 一致：后端不需要理解内部结构，原样存取最省事。
 */
@Data
@TableName("t_prompt")
public class Prompt {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 冗余自源。有了它，列表查询不用 join t_prompt_source。 */
    private Long userId;

    private Long sourceId;

    /** 源内 id，对外就是 Prompt.id。缺失时由后端补造成 {sourceKey}-0001。 */
    private String promptKey;

    private String title;

    private String prompt;

    private String description;

    private String coverUrl;

    private String referenceImageUrls;

    private String tagsJson;

    private String preview;

    private String author;

    private String sourceUrl;

    private String imageMode;

    private String imageModel;

    private String imageSize;

    private Integer imageCount;

    /** 在源 JSON 数组里的下标：保留源的策展顺序，用户看到的顺序和上游一致。 */
    private Integer sortIndex;

    private LocalDateTime remoteCreatedAt;

    private LocalDateTime remoteUpdatedAt;

    /** 首次入库时间。重复同步用 ON DUPLICATE KEY UPDATE，这一列不会被覆盖。 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
