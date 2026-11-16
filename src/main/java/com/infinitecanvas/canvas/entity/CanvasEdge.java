package com.infinitecanvas.canvas.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 画布连线。只存两个端点，不存贝塞尔控制点之类的渲染信息——
 * 那些由前端按节点位置实时计算，存下来一旦节点移动就会不一致。
 */
@Data
@TableName("t_canvas_connection")
public class CanvasEdge {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long projectId;

    private String edgeKey;

    private String fromNodeKey;

    private String toNodeKey;
}
