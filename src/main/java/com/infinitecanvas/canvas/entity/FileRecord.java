package com.infinitecanvas.canvas.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件记录，对应 t_file。
 *
 * <p>注意它不是"文件本身"：同一份内容（sha256 相同）在磁盘上只有一份，
 * 但每个上传过它的用户都有一条自己的记录。这样"我删掉我的文件"不会影响别人，
 * 磁盘文件只在最后一条记录消失时才真正删除。
 *
 * <p>刻意没有 {@code deleted} 字段（也就没有 {@code @TableLogic}）：
 * 文件是技术资源，不需要回收站。素材删除是可恢复的（逻辑删除），
 * 文件删除是不可恢复的——因为恢复素材时磁盘上必须还有那份字节，
 * 这个责任交给"清理接口只动真正的孤儿"来保证，而不是靠在文件表里留垃圾。
 */
@Data
@TableName("t_file")
public class FileRecord {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    /** 内容摘要，64 位小写十六进制。秒传、去重、内容寻址的存储 key 都基于它。 */
    private String sha256;

    /** 磁盘上的相对路径，形如 {@code a3/a3f9...}。不含域名，不含协议。 */
    private String storageKey;

    private String originalName;

    private String mimeType;

    private String extension;

    private Long bytes;

    /** 图片宽高，上传时读文件头拿到。非图片为 null。 */
    private Integer width;

    private Integer height;

    /** 业务来源，清理时按它区分"素材文件"和"头像"之类的特殊文件。 */
    private String bizType;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
