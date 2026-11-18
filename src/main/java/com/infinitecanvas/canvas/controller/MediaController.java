package com.infinitecanvas.canvas.controller;

import com.infinitecanvas.canvas.common.api.ResultCode;
import com.infinitecanvas.canvas.common.constant.ApiPath;
import com.infinitecanvas.canvas.common.exception.BizException;
import com.infinitecanvas.canvas.infra.storage.FileStorage;
import com.infinitecanvas.canvas.infra.storage.RangeResponse;
import com.infinitecanvas.canvas.mapper.FileRecordMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 媒体文件的公开只读访问，地址就是内容寻址的 storageKey。
 *
 * <p>为什么不做成静态资源映射（{@code addResourceHandlers}）：
 * 静态映射几乎零成本，但它推断不出 Content-Type —— 文件名是 sha256、没有扩展名，
 * 而按扩展名推断是它唯一的依据。结果所有文件都以 {@code application/octet-stream} 返回，
 * 配上 Spring Security 默认的 {@code X-Content-Type-Options: nosniff}，
 * 浏览器会直接拒绝把图片当图片渲染（页面上一片破图）。
 * 所以这里改成 Controller：用 {@code t_file.mime_type} 给出正确类型，
 * 顺带自动获得 Range 支持（视频拖进度条要用）。
 *
 * <p>公开访问是否安全：路径是 sha256，不可枚举；文件本身是用户自己上传的内容，
 * 不含越权数据。真正的私有场景（比如付费素材）应该换成对象存储私有桶 + 短时效预签名 URL。
 */
@Tag(name = "媒体访问", description = "公开只读：给 img/video 标签用，不需要 token")
@RestController
public class MediaController {

    /** storageKey 的形状固定为 {@code 两位分片/64 位 hex}，用正则卡死顺带挡掉路径穿越。 */
    private static final Pattern SHARD = Pattern.compile("[0-9a-f]{2}");
    private static final Pattern NAME = Pattern.compile("[0-9a-f]{64}");

    /** mime 是写一次读多次的元数据，缓存起来避免列表页 20 张图发 20 次 SELECT。 */
    private static final int MIME_CACHE_LIMIT = 5000;

    private final FileStorage fileStorage;
    private final FileRecordMapper fileRecordMapper;
    private final Map<String, String> mimeCache = new ConcurrentHashMap<>();

    public MediaController(FileStorage fileStorage, FileRecordMapper fileRecordMapper) {
        this.fileStorage = fileStorage;
        this.fileRecordMapper = fileRecordMapper;
    }

    @Operation(summary = "读取媒体文件", description = "公开访问，支持 Range；路径是内容 sha256，不可枚举")
    @GetMapping(ApiPath.MEDIA + "/{shard}/{name}")
    public ResponseEntity<Resource> serve(@PathVariable String shard,
                                          @PathVariable String name,
                                          @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader)
            throws IOException {

        if (!SHARD.matcher(shard).matches() || !NAME.matcher(name).matches()) {
            throw new BizException(ResultCode.NOT_FOUND, "文件不存在");
        }
        String storageKey = shard + "/" + name;

        String mimeType = mimeTypeOf(storageKey);
        if (mimeType == null) {
            throw new BizException(ResultCode.NOT_FOUND, "文件不存在");
        }

        Resource resource = fileStorage.load(storageKey);
        if (!resource.exists()) {
            // 数据库有记录但磁盘没了：孤儿清理、人为删目录都会造成这种状态
            throw new BizException(ResultCode.NOT_FOUND, "文件不存在");
        }

        HttpHeaders headers = new HttpHeaders();
        // 内容寻址意味着同一地址的字节永不改变，可以让浏览器长时间缓存
        headers.set(HttpHeaders.CACHE_CONTROL, "public, max-age=86400");
        return RangeResponse.build(resource, resource.contentLength(), mediaTypeOf(mimeType), rangeHeader, headers);
    }

    /**
     * 按 storageKey 查 MIME。
     *
     * <p>同一份字节可能对应多条 t_file（不同用户各传一次，秒传时 mime 声明也可能不同），
     * 这里取任意一条即可——字节相同，渲染结果由字节决定，声明差异只在极端情况下才有意义。
     */
    private String mimeTypeOf(String storageKey) {
        String cached = mimeCache.get(storageKey);
        if (cached != null) {
            return cached;
        }
        String mime = fileRecordMapper.selectMimeTypeByStorageKey(storageKey);
        if (mime != null) {
            if (mimeCache.size() >= MIME_CACHE_LIMIT) {
                // 简单粗暴地上限保护：到量就整体清空，比实现一个 LRU 便宜得多，
                // 清空后最多多查几次库，不影响正确性
                mimeCache.clear();
            }
            mimeCache.put(storageKey, mime);
        }
        return mime;
    }

    private MediaType mediaTypeOf(String mimeType) {
        try {
            return MediaType.parseMediaType(mimeType);
        } catch (RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
