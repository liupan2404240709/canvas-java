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
 * 提示词源，对应 t_prompt_source。聚合根：它自己是一条完整业务数据，
 * 拉回来的提示词是它的附属数据（只能通过"同步"写入，没有单条增删接口）。
 *
 * <p>同步状态（count / sha256 / etag / lastXxx）直接放在源上，不另建任务表：
 * 每个源只需要"最后一次结果"，历史同步记录没有业务用途。真要审计应该写日志文件，
 * 而不是往一张只增不减的表里堆。
 */
@Data
@TableName("t_prompt_source")
public class PromptSource {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    /** 业务键，对外就是 sourceId。内置源用 registry 里的 id，自定义源形如 src_xxxxxxxx。 */
    private String sourceKey;

    /** 同时是提示词的 category（前端按 category 分栏，用的就是这个 name）。 */
    private String name;

    private String url;

    private String homepage;

    /** 0=停用：不参与定时同步，也不出现在查询结果里。 */
    private Integer enabled;

    /** 1=内置源：不可删除、不可改 url，只能停用。 */
    private Integer builtIn;

    /** 刷新间隔（分钟）。0 表示不参与定时同步，只能手动刷。 */
    private Integer refreshIntervalMinutes;

    private Integer promptCount;

    /** 上次成功同步的内容摘要。内容没变就跳过写库，这是"第二次刷新走缓存"的实现基础。 */
    private String contentSha256;

    private String etag;

    private String lastModified;

    private LocalDateTime lastFetchedAt;

    private LocalDateTime lastSuccessAt;

    private String lastError;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
