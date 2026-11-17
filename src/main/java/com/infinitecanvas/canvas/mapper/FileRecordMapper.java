package com.infinitecanvas.canvas.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infinitecanvas.canvas.entity.FileRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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

    /**
     * 按存储键取 MIME。
     *
     * <p>给公开的 {@code /media/**} 用：那里没有 fileId 可用（地址就是 sha256），
     * 又必须给出正确的 Content-Type，否则浏览器在 nosniff 下会拒绝渲染图片。
     *
     * <p>同一份字节可能有多条记录（不同用户各传一次），取任意一条都行，
     * 所以加 {@code LIMIT 1} 让数据库直接短路。
     */
    @Select("SELECT mime_type FROM t_file WHERE storage_key = #{storageKey} LIMIT 1")
    String selectMimeTypeByStorageKey(@Param("storageKey") String storageKey);
}
