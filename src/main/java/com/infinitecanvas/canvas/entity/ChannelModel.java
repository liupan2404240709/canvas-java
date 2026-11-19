package com.infinitecanvas.canvas.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 渠道下的一个模型，对应 t_channel_model。
 *
 * <p>为什么不做成渠道表里的一个 JSON 数组：阶段 6 的生成接口要先回答
 * "这个用户有哪些 image 能力的模型可选"，那是一次带 {@code capability} 条件的查询，
 * JSON 列上做不了（除非上函数索引，而那只能挡"包含某个值"，给不出"按能力分组"）。
 * 何况模型还能单独停用、单独挂脚本，本来就有独立生命周期。
 */
@Data
@TableName("t_channel_model")
public class ChannelModel {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 冗余自渠道。按能力跨渠道查模型时，只查这张表就够，不用 join。 */
    private Long userId;

    private Long channelId;

    /** 上游原始模型名，如 {@code qwen-image-3.0}。渠道内唯一，也是对外暴露的业务键。 */
    private String name;

    /** image / video / text / audio。见 ModelCapability。 */
    private String capability;

    /** 自定义请求脚本，可为空。前端有能力编辑，后端只负责原样存取。 */
    private String script;

    private Integer enabled;

    private Integer sortIndex;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
