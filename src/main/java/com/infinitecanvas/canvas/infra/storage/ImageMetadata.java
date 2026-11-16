package com.infinitecanvas.canvas.infra.storage;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;

/**
 * 只读图片文件头拿宽高。
 *
 * <p>为什么不用 {@code ImageIO.read(file)} 再 {@code getWidth()}：
 * 那会把整张图解码成 {@code BufferedImage} 放进堆内存。一张 8000x8000 的 PNG
 * 解码后是 8000*8000*4 ≈ 256MB，上传接口并发几个就 OOM 了——而且我们只要两个 int。
 *
 * <p>{@link ImageReader#getWidth(int)} 只解析文件头（IHDR / SOF 段），
 * 内存占用是常数级，耗时通常在 1ms 以内。
 */
@Slf4j
public final class ImageMetadata {

    private ImageMetadata() {
    }

    /** 返回 {width, height}；解析失败（含不支持 WebP）返回 null。 */
    public static int[] readSize(Path file) {
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            if (input == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                // JDK 自带 ImageIO 不认 WebP，这里会走到；属于已知取舍，不是 bug
                log.debug("没有可用的 ImageReader，跳过宽高解析 file={}", file.getFileName());
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalStateException e) {
            // 图片损坏时上层不拦（文件本身还是可以存和下载的），只是没有宽高
            log.warn("读取图片宽高失败 file={}", file.getFileName(), e);
            return null;
        }
    }
}
