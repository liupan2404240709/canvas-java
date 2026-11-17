package com.infinitecanvas.canvas.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.infinitecanvas.canvas.common.api.ResultCode;
import com.infinitecanvas.canvas.common.exception.BizException;
import com.infinitecanvas.canvas.dto.UploadBase64Request;
import com.infinitecanvas.canvas.entity.Asset;
import com.infinitecanvas.canvas.entity.FileRecord;
import com.infinitecanvas.canvas.infra.storage.FileStorage;
import com.infinitecanvas.canvas.infra.storage.ImageMetadata;
import com.infinitecanvas.canvas.infra.storage.MediaTypes;
import com.infinitecanvas.canvas.infra.storage.StorageProperties;
import com.infinitecanvas.canvas.mapper.AssetMapper;
import com.infinitecanvas.canvas.mapper.FileRecordMapper;
import com.infinitecanvas.canvas.service.FileService;
import com.infinitecanvas.canvas.vo.CleanupResultVO;
import com.infinitecanvas.canvas.vo.FileVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/**
 * 文件服务。整个类只有一条主线：**把字节变成一条 t_file 记录，且同一份内容不重复落盘**。
 *
 * <p>上传流程（两种入口最后都走 {@link #store}）：
 * <pre>
 * 校验声明 MIME → 流式写临时文件（边写边算 sha256、边卡大小上限）
 *   → 魔数校验 → 查 (user_id, sha256) 命中？
 *       命中：删临时文件，直接返回已有记录（秒传）
 *       未命中：读图片宽高 → 原子 move 到 {root}/ab/abcdef... → 插入记录
 * </pre>
 */
@Slf4j
@Service
public class FileServiceImpl implements FileService {

    /** 单次清理最多处理多少条，避免一次请求卡太久；返回的 hasMore 告诉前端还能不能再调。 */
    private static final int CLEANUP_BATCH = 500;

    /** 默认宽限期 24 小时：刚上传、还没来得及建素材的文件不能被扫走。 */
    private static final int DEFAULT_GRACE_MINUTES = 24 * 60;

    private static final int BUFFER_SIZE = 64 * 1024;

    private static final Set<String> BIZ_TYPES = Set.of("asset", "canvas", "avatar");

    private final FileRecordMapper fileRecordMapper;
    private final AssetMapper assetMapper;
    private final FileStorage fileStorage;
    private final StorageProperties storageProperties;

    public FileServiceImpl(FileRecordMapper fileRecordMapper,
                           AssetMapper assetMapper,
                           FileStorage fileStorage,
                           StorageProperties storageProperties) {
        this.fileRecordMapper = fileRecordMapper;
        this.assetMapper = assetMapper;
        this.fileStorage = fileStorage;
        this.storageProperties = storageProperties;
    }

    @Override
    public FileVO upload(Long userId, MultipartFile file, String bizType) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ResultCode.FILE_EMPTY);
        }
        try (InputStream in = file.getInputStream()) {
            return store(userId, in, file.getOriginalFilename(), file.getContentType(), bizType);
        } catch (IOException e) {
            throw new BizException(ResultCode.INTERNAL_ERROR, "读取上传数据失败");
        }
    }

    /**
     * base64 上传。前端画布大量用 dataURL（粘贴截图、drawImage 结果、AI 返回的 b64），
     * 走这个入口可以省掉一次 Blob/FormData 转换——但代价是请求体膨胀 33%，
     * 所以大文件（视频）应该在文档里明确要求走 multipart。
     */
    @Override
    public FileVO uploadBase64(Long userId, UploadBase64Request request) {
        if (request == null || !StringUtils.hasText(request.dataUrl())) {
            throw new BizException(ResultCode.FILE_EMPTY, "dataUrl 不能为空");
        }
        String raw = request.dataUrl().trim();
        String declaredMime = "";
        String payload = raw;
        if (raw.startsWith("data:")) {
            int comma = raw.indexOf(',');
            if (comma < 0) {
                throw new BizException(ResultCode.BAD_REQUEST, "dataURL 格式错误：缺少逗号分隔");
            }
            String header = raw.substring(5, comma);
            int semicolon = header.indexOf(';');
            declaredMime = semicolon > 0 ? header.substring(0, semicolon) : header;
            payload = raw.substring(comma + 1);
        }

        String mime = MediaTypes.normalize(declaredMime, request.filename());
        String category = MediaTypes.categoryOf(mime);
        if (category == null) {
            throw new BizException(ResultCode.UNSUPPORTED_FILE_TYPE,
                    "不支持的文件类型：" + (StringUtils.hasText(mime) ? mime : "未知"));
        }

        long limit = MediaTypes.limitOf(category, storageProperties);
        // 先按长度粗算，避免为一个几十 MB 的超大串真的去分配内存再发现超限
        if ((long) payload.length() / 4 * 3 > limit) {
            throw new BizException(ResultCode.FILE_TOO_LARGE, limitMessage(limit, category));
        }

        byte[] bytes;
        try {
            // 用 MimeDecoder：它会忽略换行、空格等非 base64 字符，
            // 比先 replaceAll 去掉空白再解码少一次大数组拷贝
            bytes = Base64.getMimeDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.BAD_REQUEST, "base64 解码失败");
        }
        if (bytes.length == 0) {
            throw new BizException(ResultCode.FILE_EMPTY);
        }

        String fileName = StringUtils.hasText(request.filename())
                ? request.filename()
                : "base64." + MediaTypes.extensionOf(mime);
        return store(userId, new ByteArrayInputStream(bytes), fileName, mime, request.bizType());
    }

    @Override
    public FileVO detail(Long userId, Long fileId) {
        FileRecord file = requireFile(userId, fileId);
        return FileVO.of(file, fileStorage.publicUrl(file.getStorageKey()), false);
    }

    @Override
    public StoredContent content(Long userId, Long fileId) {
        FileRecord file = requireFile(userId, fileId);
        if (!fileStorage.exists(file.getStorageKey())) {
            // 记录在、磁盘没了：多半是手工删了 data 目录，或者清理接口跑早了。
            // 这种情况必须报 404 而不是 500——对调用方来说"取不到内容"就是一个不存在的资源。
            throw new BizException(ResultCode.FILE_NOT_FOUND, "文件内容已丢失");
        }
        return new StoredContent(file, fileStorage.load(file.getStorageKey()));
    }

    /**
     * 删除文件记录。被素材引用时直接拒绝，而不是级联删素材——
     * 用户点"删文件"的预期是删掉这个文件，不是把用到它的素材一起干掉。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long fileId) {
        FileRecord file = requireFile(userId, fileId);

        Long references = assetMapper.selectCount(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getFileId, fileId));
        if (references != null && references > 0) {
            throw new BizException(ResultCode.FILE_IN_USE,
                    "文件已被 " + references + " 个素材引用，请先删除对应素材");
        }

        fileRecordMapper.deleteById(fileId);
        removeIfOrphaned(file);
    }

    @Override
    public CleanupResultVO cleanupOrphans(Long userId, int graceMinutes) {
        // 契约：graceMinutes=0 表示"不设宽限期"，用于手动强制清理；
        // 只有负数（无意义的值）才回落到默认 24 小时。
        // 早先这里写成 graceMinutes <= 0 就取默认值，结果传 0 永远清不掉东西，
        // 和文档写的"传 0 可强制"正好相反——接口参数和实现语义不一致是最难查的一类 bug。
        int safeGrace = graceMinutes < 0 ? DEFAULT_GRACE_MINUTES : graceMinutes;
        LocalDateTime before = LocalDateTime.now().minusMinutes(safeGrace);

        var orphans = fileRecordMapper.selectOrphans(userId, before, CLEANUP_BATCH);
        int removedFiles = 0;
        long freedBytes = 0;
        for (FileRecord orphan : orphans) {
            fileRecordMapper.deleteById(orphan.getId());
            if (removeIfOrphaned(orphan)) {
                removedFiles++;
                freedBytes += orphan.getBytes() == null ? 0 : orphan.getBytes();
            }
        }
        if (!orphans.isEmpty()) {
            log.info("清理孤儿文件 userId={} scanned={} removedFiles={} freedBytes={}",
                    userId, orphans.size(), removedFiles, freedBytes);
        }
        // 取满一批说明可能还有，让调用方能继续翻；没取满就一定清干净了
        return new CleanupResultVO(orphans.size(), removedFiles, freedBytes, orphans.size() == CLEANUP_BATCH);
    }

    @Override
    public FileRecord requireFile(Long userId, Long fileId) {
        FileRecord file = fileRecordMapper.selectById(fileId);
        if (file == null || !Objects.equals(file.getUserId(), userId)) {
            throw new BizException(ResultCode.FILE_NOT_FOUND);
        }
        return file;
    }

    // ------------------------------------------------------------------
    // 上传主线
    // ------------------------------------------------------------------

    private FileVO store(Long userId, InputStream in, String originalName, String declaredMime, String bizType) {
        String mime = MediaTypes.normalize(declaredMime, originalName);
        String category = MediaTypes.categoryOf(mime);
        if (category == null) {
            throw new BizException(ResultCode.UNSUPPORTED_FILE_TYPE,
                    "不支持的文件类型：" + (StringUtils.hasText(mime) ? mime : "未知"));
        }
        long limit = MediaTypes.limitOf(category, storageProperties);

        Path temp = null;
        try {
            temp = fileStorage.createTemp();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long bytes = copyToTemp(in, temp, digest, limit, category);
            String sha256 = HexFormat.of().formatHex(digest.digest());

            // 魔数校验放在算完摘要之后：此时临时文件已有完整内容，校验失败直接删掉即可
            if (!MediaTypes.matchesMagic(temp, mime)) {
                throw new BizException(ResultCode.UNSUPPORTED_FILE_TYPE,
                        "文件内容与声明的类型不符（声明的 " + mime + "）");
            }

            FileRecord existing = findBySha(userId, sha256);
            if (existing != null) {
                // 秒传：连磁盘都不用写，临时文件在 finally 里被删掉
                log.debug("命中秒传 userId={} sha256={}", userId, sha256);
                return FileVO.of(existing, fileStorage.publicUrl(existing.getStorageKey()), true);
            }

            String storageKey = fileStorage.keyOf(sha256);
            int[] size = MediaTypes.isImage(mime) ? ImageMetadata.readSize(temp) : null;
            fileStorage.commit(temp, storageKey);
            // 搬走了，finally 不要再删这个路径
            temp = null;

            FileRecord record = new FileRecord();
            record.setUserId(userId);
            record.setSha256(sha256);
            record.setStorageKey(storageKey);
            record.setOriginalName(truncate(originalName, 255));
            record.setMimeType(mime);
            record.setExtension(MediaTypes.extensionOf(mime));
            record.setBytes(bytes);
            record.setWidth(size == null ? null : size[0]);
            record.setHeight(size == null ? null : size[1]);
            record.setBizType(normalizeBizType(bizType));

            try {
                fileRecordMapper.insert(record);
            } catch (DuplicateKeyException e) {
                // 两个请求同时上传同一份内容，唯一索引 (user_id, sha256) 挡下了后插的那条。
                // 这里不是错误，退回"命中已有记录"的语义即可——内容寻址的好处就是幂等。
                FileRecord winner = findBySha(userId, sha256);
                if (winner == null) {
                    throw e;
                }
                return FileVO.of(winner, fileStorage.publicUrl(winner.getStorageKey()), true);
            }
            return FileVO.of(record, fileStorage.publicUrl(storageKey), false);
        } catch (IOException e) {
            log.error("文件落盘失败 userId={} name={}", userId, originalName, e);
            throw new BizException(ResultCode.INTERNAL_ERROR, "文件存储失败");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        } finally {
            deleteTempQuietly(temp);
        }
    }

    /**
     * 边拷边算摘要、边卡上限。
     *
     * <p>为什么不先 {@code file.getBytes()} 拿到全部内容再处理：
     * 那样一个 100MB 的视频就是 100MB 堆内存，10 个并发直接 OOM。
     * 流式处理的内存占用是常数（一个 64KB 缓冲），代价是要多写几行循环。
     *
     * <p>上限在这里再卡一次，是因为 multipart 的 {@code max-file-size} 是被容器层拦的，
     * 它只知道"总大小"，不知道"这个 MIME 该给多少"；而且它抛的是
     * {@code MaxUploadSizeExceededException}，文案对用户不友好。
     */
    private long copyToTemp(InputStream in, Path target, MessageDigest digest, long limit, String category)
            throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        try (OutputStream out = Files.newOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    throw new BizException(ResultCode.FILE_TOO_LARGE, limitMessage(limit, category));
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        }
        if (total == 0) {
            throw new BizException(ResultCode.FILE_EMPTY);
        }
        return total;
    }

    private FileRecord findBySha(Long userId, String sha256) {
        return fileRecordMapper.selectOne(new LambdaQueryWrapper<FileRecord>()
                .eq(FileRecord::getUserId, userId)
                .eq(FileRecord::getSha256, sha256));
    }

    /**
     * 删掉记录之后，如果全库没有任何记录再指向这份内容，才删磁盘文件。
     * 这就是"内容去重"必须付的代价：**删除变成了引用计数问题**，
     * 少了这一步，别人还在用的图片会被悄无声息地删掉（典型的裂图 bug）。
     */
    private boolean removeIfOrphaned(FileRecord file) {
        Long remaining = fileRecordMapper.selectCount(new LambdaQueryWrapper<FileRecord>()
                .eq(FileRecord::getSha256, file.getSha256()));
        if (remaining != null && remaining > 0) {
            return false;
        }
        fileStorage.delete(file.getStorageKey());
        return true;
    }

    private void deleteTempQuietly(Path temp) {
        if (temp == null) {
            return;
        }
        try {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            log.warn("临时文件清理失败 path={}", temp, e);
        }
    }

    private String normalizeBizType(String bizType) {
        return StringUtils.hasText(bizType) && BIZ_TYPES.contains(bizType) ? bizType : "asset";
    }

    private String limitMessage(long limit, String category) {
        return "文件超过上限 " + (limit / 1024 / 1024) + "MB（" + category + "）";
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
