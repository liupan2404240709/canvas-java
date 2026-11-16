package com.infinitecanvas.canvas.service;

import com.infinitecanvas.canvas.dto.UploadBase64Request;
import com.infinitecanvas.canvas.entity.FileRecord;
import com.infinitecanvas.canvas.vo.CleanupResultVO;
import com.infinitecanvas.canvas.vo.FileVO;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface FileService {

    FileVO upload(Long userId, MultipartFile file, String bizType);

    FileVO uploadBase64(Long userId, UploadBase64Request request);

    FileVO detail(Long userId, Long fileId);

    /** 下载用：既要文件元信息（MIME、文件名），也要字节流。 */
    StoredContent content(Long userId, Long fileId);

    void delete(Long userId, Long fileId);

    /** 清理"上传了但没建成素材"的孤儿文件。graceMinutes 之内创建的一律跳过。 */
    CleanupResultVO cleanupOrphans(Long userId, int graceMinutes);

    /** 给素材模块用：取文件并校验归属，不存在抛 404。 */
    FileRecord requireFile(Long userId, Long fileId);

    record StoredContent(FileRecord file, Resource resource) {
    }
}
