package com.infinitecanvas.canvas.infra.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 本地磁盘实现。目录结构：
 *
 * <pre>
 * {root}/.tmp/                       上传中的临时文件（和正式目录同一文件系统）
 * {root}/a3/a3f9c1e2...              正式文件，前两位分片，扩展名不进路径
 * </pre>
 *
 * <p>文件名不带扩展名是有意的：扩展名由 t_file.mime_type 决定，
 * 若把它写进路径，同一份内容被声明成 png 和 jpeg 就会落两份盘，秒传失效。
 */
@Slf4j
@Component
public class LocalFileStorage implements FileStorage {

    private static final String TEMP_DIR = ".tmp";

    private final StorageProperties properties;
    private final String contextPath;
    private final Path root;

    public LocalFileStorage(StorageProperties properties,
                            @Value("${server.servlet.context-path:}") String contextPath) {
        this.properties = properties;
        this.contextPath = contextPath == null ? "" : contextPath;
        this.root = properties.rootPath();
        try {
            Files.createDirectories(root.resolve(TEMP_DIR));
        } catch (IOException e) {
            // 启动期建不了目录就别往下跑了，否则第一次上传才会炸，排查成本更高
            throw new IllegalStateException("无法创建存储目录：" + root, e);
        }
        log.info("文件存储根目录：{}，对外访问前缀：{}{}", root, this.contextPath, properties.getPublicPrefix());
    }

    @Override
    public String keyOf(String sha256) {
        return sha256.substring(0, 2) + "/" + sha256;
    }

    @Override
    public Path createTemp() throws IOException {
        return Files.createTempFile(root.resolve(TEMP_DIR), "upload-", ".part");
    }

    @Override
    public void commit(Path tempFile, String storageKey) throws IOException {
        Path target = resolve(storageKey);
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) {
            // 同一份内容已经被别的请求落了盘，直接用现成的
            Files.deleteIfExists(tempFile);
            return;
        }
        try {
            Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            // 两个请求同时上传同一份内容，谁先谁赢，输的一方把临时文件删掉
            Files.deleteIfExists(tempFile);
        }
    }

    @Override
    public boolean exists(String storageKey) {
        return Files.isRegularFile(resolve(storageKey));
    }

    @Override
    public Resource load(String storageKey) {
        return new FileSystemResource(resolve(storageKey));
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException e) {
            // 删不掉只影响磁盘占用，不该让"删素材"这个业务动作失败
            log.warn("删除磁盘文件失败 storageKey={}", storageKey, e);
        }
    }

    @Override
    public String publicUrl(String storageKey) {
        return contextPath + properties.getPublicPrefix() + "/" + storageKey;
    }

    /**
     * 拼绝对路径并做越界校验。
     *
     * <p>storageKey 理论上只来自 sha256，但它是从数据库里读出来再拼路径的，
     * 一旦哪天有人手工往表里塞了 {@code ../../etc/passwd}，没有这层校验就是任意文件读取。
     * 这类防御的成本只有一行 {@code startsWith}，没有理由不做。
     */
    private Path resolve(String storageKey) {
        if (!StringUtils.hasText(storageKey)) {
            throw new IllegalArgumentException("storageKey 不能为空");
        }
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法存储路径：" + storageKey);
        }
        return target;
    }
}
