package com.infinitecanvas.canvas.infra.storage;

import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 文件存储抽象。**故意只放和"字节放哪儿"有关的操作**，
 * 不掺业务概念（用户、秒传、素材），这样以后换成 MinIO / OSS / S3，
 * 只需要再写一个实现类，Service 一行不用改。
 *
 * <p>为什么值得多这一层：本地磁盘和对象存储在能力上差很多——
 * 磁盘能 {@code Files.move} 做原子落盘，对象存储只能 PUT；
 * 磁盘能直接映射成静态资源，对象存储要预签名 URL。
 * 把这些差异关在实现类里，是这层抽象唯一的价值。
 */
public interface FileStorage {

    /**
     * 内容寻址的存储键：{@code a3/a3f9c1...}。
     *
     * <p>用 sha256 而不是 UUID + 日期目录，有三个直接好处：
     * 1. 同一份内容永远落在同一个位置，跨用户自动去重；
     * 2. 幂等，重复提交不会产生两份字节；
     * 3. 天然带"完整性校验"——文件被改坏后 key 就对不上了。
     *
     * <p>前两位做目录分片，避免单个目录堆几十万个文件（ext4/NTFS 都会明显变慢）。
     */
    String keyOf(String sha256);

    /**
     * 申请一个临时文件。**必须和最终目录在同一个文件系统上**，
     * 否则 {@link #commit} 里的原子 move 会退化成"复制 + 删除"，大文件会慢一倍还可能留半截文件。
     */
    Path createTemp() throws IOException;

    /** 把临时文件原子地搬到正式位置。目标已存在时丢弃临时文件（同一份内容被别人抢先落了盘）。 */
    void commit(Path tempFile, String storageKey) throws IOException;

    boolean exists(String storageKey);

    /** 以 Resource 形式打开，交给 Spring 的消息转换器去流式输出。 */
    Resource load(String storageKey);

    /** 删除磁盘文件。不存在时静默返回，调用方不需要判断。 */
    void delete(String storageKey);

    /**
     * 对外可访问的地址。放在接口里是因为"怎么访问"本身就是存储实现的差异：
     * 本地是 {@code /api/media/xxx}，对象存储是带签名的 URL。
     */
    String publicUrl(String storageKey);
}
