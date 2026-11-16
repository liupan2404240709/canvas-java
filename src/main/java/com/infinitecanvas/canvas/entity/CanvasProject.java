package com.infinitecanvas.canvas.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 画布项目，与 t_canvas_project 一一对应。
 * 一个用户有多个画布，节点和连线挂在画布下面，所以这里是聚合根。
 */
@Data
@TableName("t_canvas_project")
public class CanvasProject {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 归属用户。所有查询都必须带上它，否则就是越权。 */
    private Long userId;

    private String title;

    /** lines / dots / blank，前端 canvasThemes 里定义的值，后端只做长度校验不做枚举校验。 */
    private String backgroundMode;

    /** 0/1，数据库用 TINYINT，Java 用 Integer 而不是 Boolean：MySQL 的 BIT 映射到 Boolean 会踩坑。 */
    private Integer showImageInfo;

    private Double viewportX;

    private Double viewportY;

    private Double viewportK;

    /** 冗余计数，列表页直接读，不用 count(*)。保存快照时和节点一起更新。 */
    private Integer nodeCount;

    private Integer connectionCount;

    /**
     * 乐观锁版本号。@Version 让 OptimisticLockerInnerInterceptor 在 updateById 时
     * 自动补上 WHERE version = ? 并 SET version = version + 1，
     * 更新影响行数为 0 就说明别人先改了。
     */
    @Version
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
