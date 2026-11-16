package com.infinitecanvas.canvas.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;

import java.time.LocalDateTime;

/**
 * createdAt / updatedAt 自动填充。实体字段上需要标注 @TableField(fill = ...) 才会生效，
 * 阶段 2 建第一批实体时就能直接受益。
 */
public class AuditMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        // 这里刻意不用 strictUpdateFill：它只在字段为 null 时才填充。
        // 而 updateById(entity) 的 entity 通常是从数据库读出来的，updatedAt 已经有旧值，
        // 结果就是 SQL 里显式写回旧时间，MySQL 的 ON UPDATE CURRENT_TIMESTAMP 也不会触发
        // （列被显式赋值时它不生效），updated_at 永远停在第一次保存的时间。
        if (metaObject.hasSetter("updatedAt")) {
            setFieldValByName("updatedAt", LocalDateTime.now(), metaObject);
        }
    }
}
