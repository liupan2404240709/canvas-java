# 08 · 媒体访问与 Range 下载

## 一、一句话定位

`<img src>` / `<video src>` **不会带 Authorization 头**，所以文件必须有第二条"免鉴权但可控"的访问路径；而视频要能拖进度条，就必须支持 HTTP Range。这两件事在本项目里由同一个 Controller 承担。

## 二、两条访问路径

```
① 公开媒体访问    GET /api/media/a3/a3f9...          不需要 token，MediaController 提供
② 鉴权内容接口    GET /api/v1/files/{id}/content     需要 token，支持 Range 和 ?download=true
```

| | ① 公开访问 | ② 鉴权接口 |
| --- | --- | --- |
| 鉴权 | 无 | ✅ |
| 寻址方式 | 内容 sha256 | 数据库主键 fileId |
| Range | ✅ | ✅ |
| 文件名 | 无（就是 sha256） | ✅ `Content-Disposition` 带原名 |
| 适用 | `<img>` / `<video>` / CDN | 下载、私有内容、需要审计 |

两条路径共用 `RangeResponse` 和 `BoundedResource`，避免出现"一个支持 Range、另一个不支持"的不一致。

## 三、为什么放弃 `addResourceHandlers`

一开始用的是最省事的写法——把上传目录直接映射成静态资源：

```java
// 已经删掉的代码，留着当反面教材
registry.addResourceHandler("/media/**")
        .addResourceLocations(storageProperties.rootPath().toUri().toString() + "/")
        .setCachePeriod(86400);
```

零开销、原生支持 Range、看起来完美。但实测发现所有文件都返回：

```
Content-Type: application/octet-stream
X-Content-Type-Options: nosniff
```

**根因**：`ResourceHttpRequestHandler` 判断 MIME 的唯一依据是**文件扩展名**。而本项目的存储路径是内容寻址的 `{sha前两位}/{sha256}`，**刻意不带扩展名**（带了就会让同一份内容因 MIME 声明不同而落两份盘，秒传失效）。

于是 `MediaTypeFactory.getMediaType(resource)` 返回空 → 默认 `application/octet-stream`。

**为什么这会要命**：Spring Security 默认加 `X-Content-Type-Options: nosniff`，它禁止浏览器"猜"类型。`<img>` 拿到 `octet-stream` 就直接拒绝渲染——页面上全是一片破图，但 `curl -I` 看状态码是 200，很容易误判成"前端问题"。

所以改成了 Controller：MIME 从 `t_file.mime_type` 拿，顺带白得 Range 支持。

> 这是一个典型的"**存储层的最优设计和传输层的最优设计冲突**"的案例。内容寻址要求路径不含扩展名，静态映射要求路径含扩展名。冲突只能在中间加一层翻译来解决。

## 四、MediaController 的两个细节

```java
@GetMapping(ApiPath.MEDIA + "/{shard}/{name}")     // /media/{两位hex}/{64位hex}
public ResponseEntity<Resource> serve(@PathVariable String shard,
                                      @PathVariable String name,
                                      @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader)
        throws IOException {
    if (!SHARD.matcher(shard).matches() || !NAME.matcher(name).matches()) {
        throw new BizException(ResultCode.NOT_FOUND, "文件不存在");
    }
    ...
}
```

**① 用正则卡死路径格式，顺手挡掉路径穿越**

`{shard}` 和 `{name}` 是路径变量，如果不校验，`/media/aa/..%2F..%2Fapplication.yml` 这类请求就有机会被拼进 `root.resolve(storageKey)`。虽然 `LocalFileStorage.resolve()` 里还有一层 `startsWith(root)` 兜底，但**两层防御的成本几乎为零**，没理由只留一层。

**② MIME 缓存**

列表页一次显示 20 张图 = 20 次 `SELECT mime_type`。用一个 `ConcurrentHashMap` 缓存，到 5000 条就整体清空——比实现 LRU 便宜得多，清空后最多多查几次库，不影响正确性。

## 五、公开只读是否可接受

因为路径**不可枚举**：

```
/api/media/9f/9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08
```

要猜出一个路径，必须**已经拥有那份文件的内容**（sha256 是内容的函数）。这带来两个性质：

1. 攻击者拿到 URL 之前无法穷举（256 位空间）；
2. 一旦拿到 URL 就等同于拿到文件——**URL 本身就是凭据**。

在"用户自己的图片"这个场景下可以接受（类似 unlisted 链接），但要清楚它**不满足**：不能撤销（除非删文件）、可被转发、没有过期时间。

生产环境更稳的做法：

| 方案 | 说明 |
| --- | --- |
| 对象存储私有桶 + 预签名 URL | 有效期 5 分钟，泄露后自动失效，签名可绑 IP/UA |
| Nginx `X-Accel-Redirect` | 应用鉴权后把内部路径交给 Nginx 吐文件，不经过应用内存 |
| CDN 鉴权（Token / Referer / 时间戳） | 适合大流量 |

本项目留在"公开 + 内容寻址"，但必须知道自己简化了什么。

## 六、Range：视频拖进度条依赖什么

浏览器请求视频时：

```
GET /api/v1/files/123/content
Range: bytes=1048576-
```

期望：

```
HTTP/1.1 206 Partial Content
Content-Range: bytes 1048576-5242879/5242880
Content-Length: 4194304
Accept-Ranges: bytes
<从第 1048576 字节开始的 4194304 个字节>
```

如果服务端无视 `Range` 直接返回 200 + 整个文件：

- 浏览器要把整个视频下载完才开始播；
- 拖动进度条 → 浏览器发新的 Range 请求 → 又被无视 → 卡住或从头加载。

**"视频不能拖进度条"这个经典 bug，99% 是服务端不支持 Range。**

## 七、踩坑实录：`ResponseEntity<?>` + `ResourceRegion` 为什么 500

这是本阶段最值得记的一个坑，因为它看起来完全符合直觉。

**错误写法**（第一版就是这么写的）：

```java
@GetMapping("/{id}/content")
public ResponseEntity<?> content(...) {          // ← 泛型是 ?
    if (noRange) {
        return ResponseEntity.ok().body(resource);              // Resource
    }
    ResourceRegion region = ranges.get(0).toResourceRegion(resource);
    return ResponseEntity.status(PARTIAL_CONTENT).body(region); // ResourceRegion
}
```

思路是"同一个方法返回两种 body 类型，`<?>` 让 Spring 运行时判断"。**实际上会直接 500**：

```
org.springframework.http.converter.HttpMessageNotWritableException:
No converter for [class org.springframework.core.io.support.ResourceRegion]
with preset Content-Type 'image/png'
```

**根因**：`AbstractMessageConverterMethodProcessor#writeWithMessageConverters` 需要先确定 **body 的静态类型**：

```java
Type targetType = ... // 来自方法签名的返回泛型
```

声明成 `ResponseEntity<?>` 时，`targetType` 退化成 `Object`；而 `ResourceRegionHttpMessageConverter` 的 `canWrite(targetType, valueType, mediaType)` 会检查 `targetType` 是不是 `ResourceRegion`，`Object` 不匹配 → 判定写不了 → 没有任何转换器能处理 → 500。

也就是说：**Spring 不是按运行时类型选转换器的，它需要静态类型参与判断**。`<?>` 在"返回值类型推断"这件事上是主动放弃了信息。

**正确写法**：让两条分支返回**同一种**静态类型 `Resource`，用包装类表达"区间"：

```java
@GetMapping("/{id}/content")
public ResponseEntity<Resource> content(...) {
    return RangeResponse.build(resource, length, mediaType, rangeHeader, headers);
}
```

`RangeResponse.build` 内部：

```java
if (ranges.isEmpty()) {
    headers.setContentLength(length);
    return ResponseEntity.ok().headers(headers).body(resource);        // 整份
}
long start = range.getRangeStart(length);
long end = range.getRangeEnd(length);
headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + length);
headers.setContentLength(end - start + 1);
return ResponseEntity.status(PARTIAL_CONTENT)
        .headers(headers)
        .body(new BoundedResource(resource, start, end - start + 1));  // 区间
```

两条分支都是 `Resource`，`ResourceHttpMessageConverter` 都能写，长度取 `contentLength()`，行为完全可预测。代价是要自己实现"从某偏移读 N 个字节"。

**面试价值**：这类问题问的是"你知不知道框架的类型推断边界在哪"。答"我用了 `ResponseEntity<?>` 然后报 500，改成 `BoundedResource` 就好了"比背十遍 `ResourceRegion` 用法更有说服力。

## 八、`BoundedResource` 的三个关键点

```java
public class BoundedResource extends AbstractResource {
    private final Resource delegate;
    private final long position;
    private final long count;

    @Override
    public InputStream getInputStream() throws IOException {
        InputStream in = delegate.getInputStream();
        in.skipNBytes(position);          // ← 关键 1
        return new LimitedInputStream(in, count);   // ← 关键 2
    }

    @Override
    public long contentLength() {
        return count;                     // ← 关键 3
    }
}
```

**关键 1：必须用 `skipNBytes` 而不是 `skip`**

`InputStream.skip(n)` 契约允许"跳过少于 n 个字节"，返回实际跳过的数量，下次调用还能继续跳。用它做定位而不检查返回值，视频拖到后半段就会读到错误偏移的内容（现象是花屏、解码失败，极难定位）。

`skipNBytes`（JDK 12+）跳不满就抛 `EOFException`，属于"早点炸"。

**关键 2：限长读，防止越界**

```java
@Override
public int read(byte[] buffer, int offset, int length) throws IOException {
    if (remaining <= 0) return -1;
    int read = super.read(buffer, offset, (int) Math.min(length, remaining));
    ...
}
```

不限制的话，容器按 8KB 缓冲读，一次就可能越过区间末尾，把后面的字节一起吐出去——`Content-Length` 与实际字节数不一致，客户端会直接判定连接损坏。

**关键 3：`contentLength()` 返回区间长度**

`ResourceHttpMessageConverter` 用它设置 `Content-Length`。返回整个文件大小会导致客户端按错误长度解析，表现为"下载到 90% 卡住"。

## 九、`HttpRange` 干了什么

```java
HttpRange.parseRanges("bytes=0-9")     // → [(start=0, end=9)]
HttpRange.parseRanges("bytes=1024-")   // → [(start=1024, end=null)]   开区间
HttpRange.parseRanges("bytes=-500")    // → 最后 500 字节
```

`getRangeStart(length)` / `getRangeEnd(length)` 会把开区间、后缀式换算成绝对偏移：

```
bytes=0-9      → start=0,    end=9
bytes=1024-    → start=1024, end=length-1
bytes=-500     → start=length-500, end=length-1
```

没有这一步，`Content-Range` 里 `-` 后面该写什么全靠手算，极易差一。

### 两个刻意的简化

**① 只处理第一个区间**

`bytes=0-9,20-29` 需要返回 `multipart/byteranges`，每个区间一个 part。浏览器正常播放/下载时几乎不用多区间（那是断点续传的老技巧）。实现成本 vs 收益，这里选不做。

**② 区间完全越界才回 416**

`bytes=999999-` 超出文件长度时回 `416` + `Content-Range: bytes */length`，否则播放器会一直重试。但 `bytes=abc` 这种解析失败的值按"没有 Range"处理，回整个文件更宽容——不会因为一个笔误导致功能全挂。

## 十、`Content-Disposition` 里的中文

```java
ContentDisposition.builder(download ? "attachment" : "inline")
        .filename(name, StandardCharsets.UTF_8)
        .build();
```

输出：

```
Content-Disposition: inline; filename="red.png"; filename*=UTF-8''%E7%BA%A2%E8%89%B2.png
```

- `filename=` 是 ASCII 回退（非 ASCII 会被替换成 `?`）；
- `filename*=` 是 RFC 5987 的 UTF-8 编码，现代浏览器优先用它。

不指定 `StandardCharsets.UTF_8` 时，`filename*` 不会生成，中文文件名下载后就是乱码。

## 十一、为什么不用 `out.write()`

```java
// 千万别这么写
byte[] bytes = Files.readAllBytes(path);
response.getOutputStream().write(bytes);
```

三个问题：

1. **内存**：100MB 视频 = 100MB 堆，并发几个就 OOM；
2. **不支持 Range**：只能从头吐到尾，视频不能 seek；
3. **不支持 `If-Modified-Since`**：缓存协商失效，每次全量传。

`ResponseEntity<Resource>` 由转换器负责流式拷贝（内部固定缓冲），内存常数。

## 十二、面试考察点

**Q1：HTTP Range 的实现原理？**

客户端发 `Range: bytes=start-end`，服务端回 `206` + `Content-Range: bytes start-end/total` + 该区间字节。必须同时声明 `Accept-Ranges: bytes`（告诉客户端"我支持"），否则客户端不会发 Range。

**Q2：断点续传怎么实现？**

1. 客户端记下已下载的字节数 N；
2. 重连时发 `Range: bytes=N-`；
3. 服务端回 206 + 剩余部分；
4. 客户端 append。

关键点：**服务端必须能按任意字节偏移定位**，所以文件存储必须是可随机读的（磁盘文件、对象存储都支持），不能是"边生成边吐"的流。

**Q3：为什么图片不需要 Range，视频需要？**

图片小（几十 KB 到几 MB），一次传完更简单；视频大（几十 MB 起）且用户会跳着看，必须能只取某一段。另外 `<video>` 会先发一个小 Range 探测（通常 `bytes=0-`）读元数据（moov box），这就是"不支持 Range 的视频在 Safari 上直接放不出来"的原因。

**Q4：`<img>` 为什么带不了 Authorization？**

因为 `<img>` 是浏览器发起的资源请求，不是 XHR/fetch，没有"自定义请求头"的概念。可选方案：
1. token 放 query（会进访问日志、Referer，不推荐）；
2. 用 Cookie（`<img>` 会带同站 Cookie，但要防 CSRF）；
3. 转成 blob URL（`fetch` + `URL.createObjectURL`，前端多做一步，内存占用高）；
4. 公开/签名 URL（本项目 + 生产常用）。

**Q5：`ResponseEntity<?>` 有什么坑？**

返回泛型决定 Spring 能否正确选择 `HttpMessageConverter`。`<?>` 会让目标类型退化成 `Object`，一些"只接受特定类型"的转换器（如 `ResourceRegionHttpMessageConverter`）会判定自己写不了，最终 500 `HttpMessageNotWritableException`。需要多态 body 时，优先"统一成父类型 + 包装类"，而不是放宽泛型。

## 十三、常见坑

| 坑 | 现象 | 原因 |
| --- | --- | --- |
| 静态映射 | 图片 200 但浏览器不显示 | 无扩展名 → `octet-stream` + `nosniff` |
| `ResponseEntity<?>` + `ResourceRegion` | 206 分支 500，`No converter` | 静态类型退化成 `Object` |
| 用 `skip` 定位 | 视频后半段花屏/解码失败 | `skip` 允许少跳 |
| `Content-Length` 用了整个文件大小 | 下载卡在 90% | 区间响应的长度必须是 `count` |
| 不支持 Range | 视频不能拖进度条，Safari 直接不播 | 用了 `out.write` |
| 忘了 `Accept-Ranges` | 客户端不发 Range | 服务端没声明支持 |
| `Content-Range` 少写 `total` | 播放器算不出剩余 | 格式必须是 `bytes start-end/total` |
| 中文文件名乱码 | 下载名是 `???` | `filename*` 没生成 |
| handler 路径带 `/api` | 404 或 401 | context-path 不参与匹配 |

## 十四、自检清单

- [ ] 能写出 200 / 206 / 416 三种响应的完整头
- [ ] 能解释"视频不能拖进度条"的根因
- [ ] 能说清为什么静态映射会让图片变成破图
- [ ] 知道 `ResponseEntity<?>` 为什么会选不到转换器
- [ ] 能说清 `BoundedResource` 里三个关键点分别防的是什么
- [ ] 能说清公开媒体访问的安全边界，以及生产该怎么改
