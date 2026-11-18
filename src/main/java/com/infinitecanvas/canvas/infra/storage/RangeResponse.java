package com.infinitecanvas.canvas.infra.storage;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;

/**
 * 把「一份完整资源 + 一个 Range 请求头」翻译成 200 或 206 响应。
 *
 * <p>抽成工具类是因为有两个地方需要完全一样的行为：
 * 鉴权版 {@code GET /v1/files/{id}/content}（下载、支持断点续传）
 * 和公开版 {@code GET /media/{sha}}（给 {@code <img>}、{@code <video>} 用）。
 * 两处各写一遍的话，迟早会出现"一个支持 Range 一个不支持"的不一致。
 *
 * <p>几个容易踩的点：
 * <ul>
 *   <li>只处理第一个区间。浏览器发多区间是为了省请求，实际极少见，
 *       而 {@code multipart/byteranges} 的实现成本远大于收益。</li>
 *   <li>区间越界要回 416，并在 Content-Range 里写 {@code bytes *} 加上总长度，
 *       否则播放器会一直重试。</li>
 *   <li>{@code Content-Length} 必须显式设成区间长度，不能沿用整个文件的大小。</li>
 * </ul>
 */
public final class RangeResponse {

    private RangeResponse() {
    }

    public static ResponseEntity<Resource> build(Resource resource, long length, MediaType type,
                                                 String rangeHeader, HttpHeaders headers) {
        headers.setContentType(type);
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");

        List<HttpRange> ranges = parse(rangeHeader);
        if (ranges.isEmpty() || length <= 0) {
            headers.setContentLength(length);
            return ResponseEntity.ok().headers(headers).body(resource);
        }

        HttpRange range = ranges.get(0);
        long start;
        long end;
        try {
            start = range.getRangeStart(length);
            end = range.getRangeEnd(length);
        } catch (IllegalArgumentException e) {
            // 形如 bytes=abc 这种解析成功但语义非法的区间，按整份返回更宽容
            headers.setContentLength(length);
            return ResponseEntity.ok().headers(headers).body(resource);
        }

        if (start >= length) {
            headers.set(HttpHeaders.CONTENT_RANGE, "bytes */" + length);
            headers.remove(HttpHeaders.CONTENT_LENGTH);
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE).headers(headers).build();
        }

        long count = end - start + 1;
        headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + length);
        headers.setContentLength(count);
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .headers(headers)
                .body(new BoundedResource(resource, start, count));
    }

    private static List<HttpRange> parse(String header) {
        if (header == null || header.isBlank()) {
            return List.of();
        }
        try {
            return HttpRange.parseRanges(header);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }
}
