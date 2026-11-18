# 05 · MIME 白名单与魔数校验

## 一、一句话定位

**客户端声明的 `Content-Type` 只是一个建议**。真正决定文件能不能存的是三件事：白名单、文件头、大小。

## 二、核心代码

```java
/** 白名单：只放这个项目真会遇到的格式 */
private static final Map<String, String> EXTENSION_BY_MIME = Map.ofEntries(
        Map.entry("image/jpeg", "jpg"), Map.entry("image/png", "png"),
        Map.entry("image/webp", "webp"), Map.entry("image/gif", "gif"),
        Map.entry("video/mp4", "mp4"), Map.entry("video/webm", "webm"),
        Map.entry("video/quicktime", "mov"),
        Map.entry("audio/mpeg", "mp3"), Map.entry("audio/wav", "wav"),
        Map.entry("audio/ogg", "ogg"), Map.entry("audio/mp4", "m4a"),
        Map.entry("text/plain", "txt")
);

public static String normalize(String declaredMime, String fileName) {
    String mime = declaredMime == null ? "" : declaredMime.trim().toLowerCase(Locale.ROOT);
    int semicolon = mime.indexOf(';');
    if (semicolon > 0) {
        mime = mime.substring(0, semicolon).trim();       // 去掉 "; charset=..."
    }
    mime = ALIASES.getOrDefault(mime, mime);              // image/jpg → image/jpeg
    if (mime.isEmpty()) {
        mime = MIME_BY_EXTENSION.getOrDefault(extensionFromName(fileName), "");
    }
    return mime;
}

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
```

## 三、为什么"信任 Content-Type"是错的

```bash
# 把可执行文件改名成 png 上传
curl -F "file=@evil.exe;type=image/png" /api/v1/files/upload
```

`MultipartFile.getContentType()` 返回的是**请求体里那段文本**，完全由客户端控制。只校验它等于 `image/png` 就存下来，意味着：

1. 文件落盘，`mime_type` 记成 `image/png`；
2. 静态映射以 `Content-Type: image/png` 返回它；
3. 浏览器会根据内容做嗅探（虽然现代浏览器对 `X-Content-Type-Options: nosniff` 有保护），某些场景下会执行；
4. 更现实的风险：下游有"读取图片生成缩略图"的逻辑时，被喂进非图片字节。

所以要对**字节**做判断，而不是对**声明**做判断。

## 四、为什么只校验图片，不校验视频/音频

容器格式的魔数不在固定位置：

| 格式 | 文件头 |
| --- | --- |
| PNG | `89 50 4E 47 0D 0A 1A 0A`（固定 8 字节） |
| JPEG | `FF D8 FF`（固定 3 字节） |
| GIF | `47 49 46 38`（"GIF8"） |
| **MP4** | `....ftyp` —— 前面可以有 `free` / `skip` / `wide` box，`ftyp` 偏移不固定 |
| **WebM/MKV** | `1A 45 DF A3`（EBML，这个还算固定） |
| **MP3** | `ID3` 或 `FF FB`/`FF F3`/`FF F2`，还可能是裸 MPEG 帧 |

MP4 的变体多到"手写 20 行判断"必然漏。漏了会怎样？**误拒正常文件**——用户传的合法视频被 400 拒绝，这种 bug 比"没校验"更伤体验。

正确做法：

| 方案 | 成本 | 建议 |
| --- | --- | --- |
| 只校验白名单声明 | 低 | 本项目选的：能挡住"随手改名"，挡不住有意的攻击者 |
| 手写魔数 | 中 | 图片可以做，视频一定漏 |
| Apache Tika | 高（10MB+ 依赖） | 真要做类型检测就上它 |

**"我知道这里不该手写"本身就是正确答案。** 面试里被问"你怎么校验文件类型"，能答出"图片校验魔数、音视频不自己猜、要严格就上 Tika"，比说"我都校验了"更可信。

## 五、MIME 归一化踩的三个坑

**① `image/jpg` 不是标准值**

标准是 `image/jpeg`。但很多客户端（尤其是老版本 Android、部分 Python 库）会送 `image/jpg`。不归一化，它就不在白名单里，用户传一张正常 JPEG 被拒。

同理：`audio/mp3` → `audio/mpeg`、`audio/x-wav` → `audio/wav`。

**② 带参数的 MIME**

```
image/png; charset=binary
```
有些库（PHP 的 finfo、部分 Java 上传组件）会带参数。不切掉 `;` 后面，白名单匹配失败。

**③ 空 MIME**

`curl -F "file=@a.png"` 不带 `type` 时，`getContentType()` 可能是 `application/octet-stream` 甚至 `null`。这时退化成"按扩展名猜"，否则命令行用户永远传不上去。

## 六、`readHead` 里那个 `readNBytes`

```java
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
```

为什么不用 `in.read(buffer)`：`InputStream.read` **不保证读满**，可能只返回 3 个字节，剩下的还是 0。`readNBytes` 会循环读到够长或到流末尾。

为什么不足长度要截断成 `actual`：如果文件只有 2 字节，却给它一个 12 字节的数组（后 10 个是 0），`image/gif` 的判断 `head[3] == '8'` 就会拿 0 去比——虽然结果还是 false，但**语义错了**。长度判断必须基于真实读到的字节数。

## 七、面试考察点

**Q1：怎么防止用户上传恶意文件？**

按层次答：
1. **白名单**，不是黑名单（黑名单永远列不完）；
2. **魔数校验**（能做的格式）；
3. **不落可执行目录**（`data/uploads` 不在 classpath，也不是 webroot）；
4. **静态响应加 `X-Content-Type-Options: nosniff`**（本项目还没加，阶段 8 补）；
5. **大小限制**防打满磁盘；
6. **文件名不参与路径**（内容寻址天然免疫 `../../` 和特殊字符）。

**Q2：`head[0] & 0xFF` 这个 `& 0xFF` 是干什么的？**

Java 的 `byte` 是有符号的（-128~127）。PNG 的首字节是 `0x89` = 137，赋给 `byte` 会变成 -119。直接和 `0x89`（int 137）比较永远 false。
`& 0xFF` 把它提升成无符号的 int 0~255 再比较。

这个坑在"文件类型判断"里 100% 会遇到，不写 `& 0xFF` 的现象是"PNG 总是校验失败"。

**Q3：为什么把魔数校验放在算完 sha256 之后，而不是之前？**

因为校验需要"临时文件已经有完整内容"。而且此时如果校验失败，直接在 `finally` 里删掉临时文件即可，不用再管输入流。

反过来说：**如果能在读流的过程中校验**（读前 12 字节就判断），可以更早失败、不写临时文件。但那需要把 `InputStream` 的头部字节缓存下来再喂给后续的 `copyToTemp`，代码复杂度上升；本项目优先"少一条路径"。

## 八、常见坑

| 坑 | 现象 | 原因 |
| --- | --- | --- |
| 忘了 `& 0xFF` | PNG 永远校验不过 | byte 是有符号的 |
| 用 `read()` 读 12 字节 | 偶尔校验失败 | `read` 不保证读满 |
| 白名单里写 `image/jpg` | 正常 JPEG 被拒 | 标准值是 `image/jpeg` |
| 不切 `;` 参数 | `image/png; charset=binary` 匹配失败 | 没做归一化 |
| 对视频也手写魔数 | 正常 mp4 被拒 | 容器格式变体太多 |
| 只校验扩展名 | 改名即绕过 | 扩展名是最容易伪造的 |

## 九、自检清单

- [ ] 能说出为什么不能只信 `Content-Type`
- [ ] 能背出 PNG / JPEG / GIF / WebP 的魔数
- [ ] 知道 `& 0xFF` 解决什么问题
- [ ] 能解释为什么音视频不自己手写魔数，以及严格校验该用什么
