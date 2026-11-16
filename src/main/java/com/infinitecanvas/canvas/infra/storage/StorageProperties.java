package com.infinitecanvas.canvas.infra.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 存储配置。抽成配置类而不是散落的 {@code @Value}，好处是 IDE 有补全、
 * 单位可以写成 {@code 20MB} 而不是 {@code 20971520}。
 */
@Data
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    /** 存储根目录。相对路径按"启动时的工作目录"解析，生产要换成绝对路径挂卷。 */
    private String root = "./data/uploads";

    /**
     * 静态资源映射前缀，**不含 context-path**。
     * Servlet 容器里它是 {@code /media/**}，对外真实地址是 {@code /api/media/**}。
     */
    private String publicPrefix = "/media";

    /** 图片上限。前端截图、生成结果基本都是几 MB，20MB 足够，也挡住了"传错文件"的情况。 */
    private DataSize maxImageSize = DataSize.ofMegabytes(20);

    /** 视频上限。故意比 multipart 的 max-file-size 小，让应用层先给出可读的错误信息。 */
    private DataSize maxVideoSize = DataSize.ofMegabytes(100);

    private DataSize maxAudioSize = DataSize.ofMegabytes(20);

    /** 绝对化并 normalize，后面所有路径拼接都以它为基准，防止 {@code ..} 逃逸。 */
    public Path rootPath() {
        return Paths.get(root).toAbsolutePath().normalize();
    }
}
