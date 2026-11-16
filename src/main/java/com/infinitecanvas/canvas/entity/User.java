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
 * 用户实体，与 t_user 一一对应。
 * 字段名用驼峰，靠 map-underscore-to-camel-case 映射到 snake_case 列名。
 */
@Data
@TableName("t_user")
public class User {

    /** 雪花 ID，由 MyBatis-Plus 在 insert 时回填。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String username;

    private String email;

    /** BCrypt 密文。任何出参 VO 都不能带这个字段。 */
    private String passwordHash;

    private String nickname;

    private String avatarUrl;

    /** 1=正常 0=禁用。 */
    private Integer status;

    private LocalDateTime lastLoginAt;

    private String lastLoginIp;

    /** 由 AuditMetaObjectHandler 自动填充，不需要业务代码赋值。 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 逻辑删除标记，0=未删 1=已删。查询会被自动加上 deleted = 0。 */
    @TableLogic
    private Integer deleted;

    public static final int STATUS_ENABLED = 1;

    /** 刻意不叫 isEnabled()：避免被 Bean 内省当成一个 enabled 属性，后续序列化时多出字段。 */
    public boolean active() {
        return status != null && status == STATUS_ENABLED;
    }
}
