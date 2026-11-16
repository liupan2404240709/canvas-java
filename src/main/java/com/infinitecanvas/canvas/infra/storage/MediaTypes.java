package com.infinitecanvas.canvas.infra.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * MIME 相关的判断都收在这里：归一化、白名单、扩展名、魔数校验。
 *
 * <p>核心立场：**客户端声明的 Content-Type 只是一个建议，不是事实**。
 * 真正决定"这文件能不能存"的是三件事——
 * 1. 声明的 MIME 在不在白名单里；
 * 2. 文件头魔数和声明的 MIME 对不对得上；
 * 3. 大小在不在该类型的上限内。
 */
public final class MediaTypes {

    public static final String CATEGORY_IMAGE = "image";
    public static final String CATEGORY_VIDEO = "video";
    public static final String CATEGORY_AUDIO = "audio";

    /** MIME 白名单。只放这个项目真会遇到的格式，不追求"支持所有"。 */
    private static final Map<String, String> EXTENSION_BY_MIME = Map.ofEntries(
            Map.entry("image/jpeg", "jpg"),
            Map.entry("image/png", "png"),
            Map.entry("image/webp", "webp"),
            Map.entry("image/gif", "gif"),
            Map.entry("video/mp4", "mp4"),
            Map.entry("video/webm", "webm"),
            Map.entry("video/quicktime", "mov"),
            Map.entry("audio/mpeg", "mp3"),
            Map.entry("audio/wav", "wav"),
            Map.entry("audio/ogg", "ogg"),
            Map.entry("audio/mp4", "m4a"),
            Map.entry("text/plain", "txt")
    );

    private static final Map<String, String> MIME_BY_EXTENSION = Map.ofEntries(
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("png", "image/png"),
            Map.entry("webp", "image/webp"),
            Map.entry("gif", "image/gif"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("webm", "video/webm"),
            Map.entry("mov", "video/quicktime"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("wav", "audio/wav"),
            Map.entry("ogg", "audio/ogg"),
            Map.entry("m4a", "audio/mp4"),
            Map.entry("txt", "text/plain")
    );

    /** 有些客户端会送这些"非标准但很常见"的写法，归一化到标准 MIME 再判白名单。 */
    private static final Map<String, String> ALIASES = Map.of(
            "image/jpg", "image/jpeg",
            "image/pjpeg", "image/jpeg",
            "audio/mp3", "audio/mpeg",
            "audio/x-wav", "audio/wav",
            "audio/wave", "audio/wav",
            "image/x-png", "image/png"
    );

    private MediaTypes() {
    }

    /**
     * 归一化客户端声明的 MIME。声明为空时退化成"按扩展名猜"，
     * 这样 {@code curl -F file=@a.png} 不带 Content-Type 也能正常上传。
     */
    public static String normalize(String declaredMime, String fileName) {
        String mime = declaredMime == null ? "" : declaredMime.trim().toLowerCase(Locale.ROOT);
        int semicolon = mime.indexOf(';');
        if (semicolon > 0) {
            // "image/png; charset=binary" 这种，去掉参数部分
            mime = mime.substring(0, semicolon).trim();
        }
        mime = ALIASES.getOrDefault(mime, mime);
        if (mime.isEmpty()) {
            mime = MIME_BY_EXTENSION.getOrDefault(extensionOf(fileName), "");
        }
        return mime;
    }

    /** 归到 image / video / audio 三类，返回 null 表示不支持。 */
    public static String categoryOf(String mime) {
        if (!EXTENSION_BY_MIME.containsKey(mime)) {
            return null;
        }
        return switch (mime.substring(0, mime.indexOf('/'))) {
            case CATEGORY_IMAGE -> CATEGORY_IMAGE;
            case CATEGORY_VIDEO -> CATEGORY_VIDEO;
            case CATEGORY_AUDIO -> CATEGORY_AUDIO;
            default -> null;
        };
    }

    public static boolean isSupported(String mime) {
        return EXTENSION_BY_MIME.containsKey(mime);
    }

    public static String extensionOf(String mime) {
        return EXTENSION_BY_MIME.get(mime);
    }

    public static boolean isImage(String mime) {
        return CATEGORY_IMAGE.equals(categoryOf(mime));
    }

    /**
     * 文件头魔数校验，只覆盖图片。
     *
     * <p>为什么不覆盖视频/音频：mp4 的 ftyp box、webm 的 EBML、mp3 的 ID3 都不在固定的前 4 个字节，
     * 手写判断必然有漏（比如 mp4 允许 ftyp 前有 free/skip box）。
     * 这种情况应该上 Apache Tika 这类专业库，而不是自己猜——**知道自己不该手写什么，也是设计的一部分**。
     *
     * <p>图片反而必须校验：把一个 .exe 改名成 .png 传给后端，如果只信 Content-Type，
     * 它就会被当成图片存下来，之后任何按"图片"处理的环节都可能被利用。
     */
    public static boolean matchesMagic(Path file, String mime) {
        byte[] head = readHead(file, 12);
        return switch (mime) {
            case "image/jpeg" -> head.length >= 3
                    && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF;
            case "image/png" -> head.length >= 8
                    && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G';
            case "image/gif" -> head.length >= 4
                    && head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8';
            case "image/webp" -> head.length >= 12
                    && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
            default -> true;
        };
    }

    /** 允许多少字节，按类型分别配置。返回 -1 表示类型本身不支持（调用方已先行拦截）。 */
    public static long limitOf(String category, StorageProperties properties) {
        return switch (category) {
            case CATEGORY_IMAGE -> properties.getMaxImageSize().toBytes();
            case CATEGORY_VIDEO -> properties.getMaxVideoSize().toBytes();
            case CATEGORY_AUDIO -> properties.getMaxAudioSize().toBytes();
            default -> -1;
        };
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static byte[] readHead(Path file, int length) {
        byte[] buffer = new byte[length];
        try (InputStream in = Files.newInputStream(file)) {
            int read = in.readNBytes(buffer, 0, length);
            if (read == length) {
                return buffer;
            }
            byte[] actual = new byte[Math.max(read, 0)];
            System.arraycopy(buffer, 0, actual, 0, actual.length);
            return actual;
        } catch (IOException e) {
            return new byte[0];
        }
    }
}
