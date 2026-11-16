package com.infinitecanvas.canvas.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 画布节点。附属表，生命周期跟随项目：保存快照时整批删掉再整批插入。
 *
 * 刻意没有 created_at / updated_at 字段映射：批量插入时让数据库默认值兜底，
 * 少两次反射填充；节点本来就没有"最后修改时间"这种业务含义。
 */
@Data
@TableName("t_canvas_node")
public class CanvasNode {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long projectId;

    /** 前端生成的节点 id，项目内唯一，数据库也建了 (project_id, node_key) 唯一索引兜底。 */
    private String nodeKey;

    private String type;

    private String title;

    private Double posX;

    private Double posY;

    private Double width;

    private Double height;

    /** JSON 文本，原样存取。用 String 而不是 JsonNode：避开 TypeHandler 配置，出错时日志里能直接看到原文。 */
    private String metadata;
}
