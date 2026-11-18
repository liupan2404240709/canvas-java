package com.infinitecanvas.canvas.infra.storage;

import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 把一整份 {@link Resource} 裁剪成"从 position 开始的 count 个字节"。
 *
 * <p>为什么需要它：Spring MVC 想返回 206/Content-Range，标准做法是 {@code ResponseEntity<ResourceRegion>}，
 * 但这要求 Handler 方法把泛型写死成 {@code ResourceRegion}；一旦为了兼容"无 Range 时返回整份文件"
 * 而写成 {@code ResponseEntity<?>}，Spring 就拿不到 body 的静态类型，
 * {@code ResourceRegionHttpMessageConverter} 会判定自己写不了，最终报
 * {@code No converter for [ResourceRegion] with preset Content-Type 'image/png'}。
 *
 * <p>换成"始终返回 {@link Resource}，Range 时返回一个裁剪过的 Resource"就绕开了类型推断问题：
 * {@code ResourceHttpMessageConverter} 对任意 Resource 都能流式写出，长度取 {@link #contentLength()}。
 * 代价是要自己管 skip 与限长，好处是 200 和 206 两条分支共用一套转换器，行为可预测。
 *
 * <p>注意 {@code skipNBytes}：InputStream 的 {@code skip} 允许返回比请求更少的字节数，
 * 必须在循环里跳到目标位置，否则视频拖到后半段会读到错误的字节。
 */
public class BoundedResource extends AbstractResource {

    private final Resource delegate;
    private final long position;
    private final long count;

    public BoundedResource(Resource delegate, long position, long count) {
        this.delegate = delegate;
        this.position = position;
        this.count = count;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        InputStream in = delegate.getInputStream();
        try {
            // JDK 12+：跳不满就抛 EOFException，不会悄悄返回一个"看起来成功"的短流
            in.skipNBytes(position);
        } catch (IOException e) {
            in.close();
            throw e;
        }
        return new LimitedInputStream(in, count);
    }

    @Override
    public long contentLength() {
        return count;
    }

    @Override
    public String getFilename() {
        return delegate.getFilename();
    }

    @Override
    public String getDescription() {
        return delegate.getDescription() + " (bytes " + position + "-" + (position + count - 1) + ")";
    }

    /** 读到 count 个字节就返回 -1，让容器认为"文件到此结束"，不会把后面的内容漏出去。 */
    private static final class LimitedInputStream extends FilterInputStream {

        private long remaining;

        private LimitedInputStream(InputStream in, long remaining) {
            super(in);
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = super.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            // 关键：不能把 length 原样传下去，否则一次 read 就可能越过区间末尾
            int read = super.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(Math.min(n, remaining));
            remaining -= skipped;
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(super.available(), remaining);
        }
    }
}
