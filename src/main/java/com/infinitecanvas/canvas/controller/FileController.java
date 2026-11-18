package com.infinitecanvas.canvas.controller;

import com.infinitecanvas.canvas.common.api.Result;
import com.infinitecanvas.canvas.common.constant.ApiPath;
import com.infinitecanvas.canvas.dto.UploadBase64Request;
import com.infinitecanvas.canvas.entity.FileRecord;
import com.infinitecanvas.canvas.infra.storage.RangeResponse;
import com.infinitecanvas.canvas.security.SecurityUtils;
import com.infinitecanvas.canvas.service.FileService;
import com.infinitecanvas.canvas.vo.CleanupResultVO;
import com.infinitecanvas.canvas.vo.FileVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Tag(name = "文件", description = "上传（multipart / base64）、秒传、下载、删除、孤儿清理")
@RestController
@RequestMapping(ApiPath.FILES)
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @Operation(summary = "上传文件", description = "multipart 表单上传，同一份内容重复上传会命中秒传（deduped=true）")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<FileVO> upload(@RequestPart("file") MultipartFile file,
                                 @Parameter(description = "业务来源：asset / canvas / avatar，默认 asset")
                                 @RequestParam(required = false) String bizType) {
        return Result.ok(fileService.upload(SecurityUtils.currentUserId(), file, bizType));
    }

    @Operation(summary = "base64 上传", description = "接受完整 dataURL 或裸 base64，给前端画布粘贴/截图场景用")
    @PostMapping("/base64")
    public Result<FileVO> uploadBase64(@RequestBody UploadBase64Request request) {
        return Result.ok(fileService.uploadBase64(SecurityUtils.currentUserId(), request));
    }

    @Operation(summary = "文件信息")
    @GetMapping("/{id}")
    public Result<FileVO> detail(@PathVariable Long id) {
        return Result.ok(fileService.detail(SecurityUtils.currentUserId(), id));
    }

    /**
     * 读取文件内容。
     *
     * <p>返回类型刻意写成 {@code ResponseEntity<Resource>}：200 时 body 是整份文件，
     * 206 时 body 是一个按区间裁剪过的 {@link Resource}（见 {@code BoundedResource}）。
     * 不要把 Range 分支写成 `ResourceRegion` 而把方法声明成 `ResponseEntity<?>`，
     * 那样 Spring 拿不到静态类型，会报 "No converter for ResourceRegion"。
     *
     * <p>为什么要支持 Range：&lt;video&gt; 拖动进度条、断点续传、大图渐进加载都依赖它。
     * 手写 {@code out.write(bytes)} 做不到——那种写法只能从头往后吐，客户端 seek 就废了。
     */
    @Operation(summary = "读取文件内容", description = "支持 Range 请求，可直接给 img / video 用")
    @GetMapping("/{id}/content")
    public ResponseEntity<Resource> content(
            @PathVariable Long id,
            @Parameter(description = "区间，如 bytes=0-1023")
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
            @Parameter(description = "true 时按附件下载（带 Content-Disposition: attachment）")
            @RequestParam(defaultValue = "false") boolean download) throws IOException {

        FileService.StoredContent stored = fileService.content(SecurityUtils.currentUserId(), id);
        FileRecord file = stored.file();
        Resource resource = stored.resource();
        long length = file.getBytes() == null ? 0 : file.getBytes();

        HttpHeaders headers = new HttpHeaders();
        // 内容寻址意味着同一 storageKey 的字节永远不会变，可以放心长缓存
        headers.set(HttpHeaders.CACHE_CONTROL, "private, max-age=86400");
        headers.setContentDisposition(contentDisposition(file, download));

        return RangeResponse.build(resource, length, mediaTypeOf(file), rangeHeader, headers);
    }

    @Operation(summary = "删除文件", description = "被素材引用时返回 40930")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        fileService.delete(SecurityUtils.currentUserId(), id);
        return Result.ok();
    }

    @Operation(summary = "清理孤儿文件",
            description = "只删「创建超过宽限期、且从未被任何素材引用」的文件；单次最多 500 条，hasMore=true 可再调一次")
    @PostMapping("/cleanup")
    public Result<CleanupResultVO> cleanup(
            @Parameter(description = "宽限期（分钟），默认 1440。刚上传还没建素材的文件不能被扫走")
            @RequestParam(defaultValue = "1440") int graceMinutes) {
        return Result.ok(fileService.cleanupOrphans(SecurityUtils.currentUserId(), graceMinutes));
    }

    private MediaType mediaTypeOf(FileRecord file) {
        try {
            return MediaType.parseMediaType(file.getMimeType());
        } catch (RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private ContentDisposition contentDisposition(FileRecord file, boolean download) {
        String name = StringUtils.hasText(file.getOriginalName())
                ? file.getOriginalName()
                : "file." + (StringUtils.hasText(file.getExtension()) ? file.getExtension() : "bin");
        return ContentDisposition.builder(download ? "attachment" : "inline")
                .filename(name, StandardCharsets.UTF_8)
                .build();
    }
}
