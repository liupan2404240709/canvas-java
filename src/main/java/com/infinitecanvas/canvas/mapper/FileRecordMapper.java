package com.infinitecanvas.canvas.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infinitecanvas.canvas.entity.FileRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FileRecordMapper extends BaseMapper<FileRecord> {

    /**
     * 查孤儿文件：本用户创建过、且没有任何素材引用它的记录。
     *
     * <p>子查询里**故意不带 {@code a.deleted = 0}**：已删除素材的文件也要算"被引用"，
     * 否则素材被删（逻辑删除、可恢复）之后一跑清理，磁盘上的字节就没了，
     * 恢复出来的素材会变成裂图。清理接口治的是"上传后压根没建成素材"的垃圾，
     * 不是"删除素材"的垃圾。
     */
    List<FileRecord> selectOrphans(@Param("userId") Long userId,
                                   @Param("before") LocalDateTime before,
                                   @Param("limit") int limit);
}
