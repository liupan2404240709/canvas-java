"""阶段 3 全链路验证：文件上传 / 秒传 / 类型校验 / Range 下载 / 素材 CRUD / 标签 / 清理。

用法：
    python scripts/stage3-e2e.py

默认打 http://127.0.0.1:8081/api/v1（和 application.yml 的端口一致）。
如果 8081 被占用而你把应用跑在别的端口，改下面的 BASE。

脚本可重复执行；末尾会清理自己创建的素材和文件。
"""

import json
import struct
import urllib.error
import urllib.request
import uuid
import zlib
from urllib.parse import quote

BASE = "http://127.0.0.1:8081/api/v1"
MEDIA_BASE = "http://127.0.0.1:8081"


# ----------------------------------------------------------------------
# HTTP 小工具
# ----------------------------------------------------------------------

def call(method, path, body=None, token=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    # 查询串里带中文时 urllib 会按 ascii 编码并抛 UnicodeEncodeError，
    # 这里统一做一次百分号编码（safe 保留 URL 结构字符）
    req = urllib.request.Request(BASE + quote(path, safe="/?&=:"), data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw}


def upload(path, filename, content, mime, token, field="file", extra=None):
    """手搓 multipart，避免依赖 requests。"""
    boundary = "----canvas" + uuid.uuid4().hex
    parts = []
    if extra:
        for key, value in extra.items():
            parts.append(
                f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode()
            )
    parts.append(
        f'--{boundary}\r\nContent-Disposition: form-data; name="{field}"; filename="{filename}"\r\n'
        f"Content-Type: {mime}\r\n\r\n".encode()
    )
    parts.append(content)
    parts.append(f"\r\n--{boundary}--\r\n".encode())
    body = b"".join(parts)

    req = urllib.request.Request(BASE + path, data=body, method="POST")
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw}


def fetch(url, headers=None):
    req = urllib.request.Request(url, method="GET")
    for key, value in (headers or {}).items():
        req.add_header(key, value)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, dict(resp.headers), resp.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


def show(label, status, obj, keys=None):
    data = obj.get("data") if isinstance(obj, dict) else None
    if keys and isinstance(data, dict):
        brief = {k: data.get(k) for k in keys}
    else:
        brief = data if data is not None else obj.get("message")
    print(f"{label:34s} HTTP {status} code={obj.get('code')} {json.dumps(brief, ensure_ascii=False)[:130]}")


def login(account, password):
    st, r = call("POST", "/auth/login", {"account": account, "password": password})
    if st != 200:
        raise SystemExit(f"登录失败 {account}: {r}")
    return r["data"]["accessToken"]


# ----------------------------------------------------------------------
# 造一张真 PNG（ImageIO 能读出宽高），不依赖本地图片文件
# ----------------------------------------------------------------------

def make_png(width, height, rgb):
    def chunk(tag, data):
        payload = tag + data
        return struct.pack(">I", len(data)) + payload + struct.pack(">I", zlib.crc32(payload) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    raw = b"".join(b"\x00" + bytes(rgb) * width for _ in range(height))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


print("========== 准备 ==========")
token = login("canvas_demo", "canvas1234")
print("main token ok")

st, r = call("POST", "/auth/register", {"username": "canvas_other", "password": "canvas1234"})
other = login("canvas_other", "canvas1234")
print("other user token ok")

png_a = make_png(320, 240, (200, 60, 60))
png_b = make_png(320, 240, (60, 120, 200))

print("\n========== 1. multipart 上传 ==========")
st, r = upload("/files/upload", "red.png", png_a, "image/png", token)
file_a = r["data"]
show("上传 red.png", st, r, ["id", "url", "mimeType", "bytes", "width", "height", "deduped"])

print("\n========== 2. 同一份内容再传一次（秒传）==========")
st, r = upload("/files/upload", "red-copy.png", png_a, "image/png", token)
file_a2 = r["data"]
show("秒传", st, r, ["id", "deduped", "sha256"])
print(f"{'同一 fileId':34s} {file_a['id'] == file_a2['id']}   sha256 一致 {file_a['sha256'] == file_a2['sha256']}")

print("\n========== 3. 不同内容 → 不同文件 ==========")
st, r = upload("/files/upload", "blue.png", png_b, "image/png", token)
file_b = r["data"]
show("上传 blue.png", st, r, ["id", "deduped", "bytes"])

print("\n========== 4. 类型校验 ==========")
st, r = upload("/files/upload", "fake.png", b"MZ\x90\x00this is not a png", "image/png", token)
show("exe 伪装成 png（魔数不符）", st, r)
st, r = upload("/files/upload", "note.txt", b"hello", "text/plain", token)
show("text/plain（不在白名单）", st, r)
st, r = upload("/files/upload", "empty.png", b"", "image/png", token)
show("空文件", st, r)

print("\n========== 5. base64 上传 ==========")
import base64
b64 = base64.b64encode(png_b).decode()
st, r = call("POST", "/files/base64", {"dataUrl": f"data:image/png;base64,{b64}", "filename": "blue-base64.png"}, token)
file_c = r["data"]
show("base64（内容和 blue.png 相同）", st, r, ["id", "deduped", "bytes"])
print(f"{'与 multipart 上传同一 fileId':34s} {file_c['id'] == file_b['id']}")

st, r = call("POST", "/files/base64", {"dataUrl": "data:image/png;base64,@@@notbase64@@@", "filename": "bad.png"}, token)
show("非法 base64", st, r)

print("\n========== 6. 静态映射与 Range ==========")
st, headers, body = fetch(MEDIA_BASE + file_a["url"])
print(f"{'GET 静态地址（无 token）':34s} HTTP {st} content-type={headers.get('Content-Type')} "
      f"content-length={headers.get('Content-Length')} 字节数对={len(body) == file_a['bytes']}")

st, headers, body = fetch(MEDIA_BASE + f"/api/v1/files/{file_a['id']}/content",
                          {"Authorization": "Bearer " + token, "Range": "bytes=0-9"})
print(f"{'GET content Range 0-9':34s} HTTP {st} content-range={headers.get('Content-Range')} "
      f"收到字节={len(body)} accept-ranges={headers.get('Accept-Ranges')}")

st, headers, body = fetch(MEDIA_BASE + f"/api/v1/files/{file_a['id']}/content?download=true",
                          {"Authorization": "Bearer " + token})
print(f"{'GET content download=true':34s} HTTP {st} disposition={headers.get('Content-Disposition')}")

st, headers, body = fetch(MEDIA_BASE + f"/api/v1/files/{file_a['id']}/content")
print(f"{'GET content 不带 token':34s} HTTP {st}（应为 401）")

print("\n========== 7. 创建素材 ==========")
st, r = call("POST", "/assets", {"kind": "text", "title": "赛博朋克提示词", "content": "霓虹雨夜，赛博朋克街道，45mm",
                                 "tags": ["赛博朋克", "夜景"], "source": "手动"}, token)
text_asset = r["data"]
show("文本素材", st, r, ["id", "kind", "title"])
print(f"{'  data':34s} {json.dumps(text_asset['data'], ensure_ascii=False)}")
print(f"{'  tags':34s} {text_asset['tags']}")

st, r = call("POST", "/assets", {"kind": "image", "title": "红色方块", "fileId": file_a["id"],
                                 "tags": ["纯色", "测试"], "source": "手动", "note": "e2e"}, token)
image_asset = r["data"]
show("图片素材", st, r, ["id", "kind", "title", "coverUrl"])
print(f"{'  data':34s} {json.dumps(image_asset['data'], ensure_ascii=False)}")

st, r = call("POST", "/assets", {"kind": "video", "title": "类型不匹配", "fileId": file_a["id"]}, token)
show("用 png 建 video 素材", st, r)

st, r = call("POST", "/assets", {"kind": "text", "title": "缺 content"}, token)
show("文本素材缺 content", st, r)

st, r = call("POST", "/assets", {"kind": "image", "title": "缺 fileId"}, token)
show("图片素材缺 fileId", st, r)

print("\n========== 8. 列表 / 搜索 / 标签 ==========")
st, r = call("GET", "/assets?page=1&size=10", token=token)
show("列表", st, r, ["page", "pageSize", "total"])
st, r = call("GET", "/assets?kind=image", token=token)
show("kind=image", st, r, ["total"])
st, r = call("GET", "/assets?kind=all&keyword=赛博", token=token)
show("keyword=赛博", st, r, ["total"])
st, r = call("GET", "/assets?tag=测试", token=token)
show("tag=测试", st, r, ["total"])
st, r = call("GET", "/assets?keyword=e2e", token=token)
show("keyword=e2e（命中备注）", st, r, ["total"])
st, r = call("GET", "/assets?size=500", token=token)
show("size=500 被压制", st, r, ["pageSize"])
st, r = call("GET", "/assets/tags", token=token)
print(f"{'标签聚合':34s} HTTP {st} {json.dumps(r.get('data'), ensure_ascii=False)[:160]}")

print("\n========== 9. 详情 / 更新 ==========")
st, r = call("GET", f"/assets/{text_asset['id']}", token=token)
show("详情", st, r, ["id", "title", "tags"])
st, r = call("PATCH", f"/assets/{text_asset['id']}",
             {"title": "赛博朋克提示词 v2", "tags": ["赛博朋克", "霓虹", "夜景"], "note": "改过"}, token)
show("更新标题+标签", st, r, ["id", "title", "tags", "note", "updatedAt"])
st, r = call("PATCH", f"/assets/{text_asset['id']}", {"tags": []}, token)
show("清空标签", st, r, ["id", "tags"])
st, r = call("PATCH", f"/assets/{text_asset['id']}", {"content": "只对文本生效"}, token)
show("改文本正文", st, r, ["id"])
st, r = call("PATCH", f"/assets/{image_asset['id']}", {"content": "图片不该有正文"}, token)
show("给图片素材改 content", st, r)

print("\n========== 10. 删除保护与越权 ==========")
st, r = call("DELETE", f"/files/{file_a['id']}", token=token)
show("删被素材引用的文件", st, r)
st, r = call("GET", f"/files/{file_a['id']}", token=other)
show("别的用户查我的文件", st, r)
st, r = call("GET", f"/assets/{image_asset['id']}", token=other)
show("别的用户查我的素材", st, r)
st, r = call("GET", f"/assets/{image_asset['id']}", token=other)
show("越权返回 404 而非 403", st, r)

print("\n========== 11. 孤儿清理 ==========")
st, r = upload("/files/upload", "orphan.png", make_png(8, 8, (0, 200, 0)), "image/png", token)
orphan = r["data"]
show("上传一个不建素材的文件", st, r, ["id", "deduped"])
st, r = call("POST", "/files/cleanup", token=token)
show("cleanup（默认宽限 1440 分钟）", st, r, ["scanned", "removedFiles", "freedBytes", "hasMore"])
st, r = call("GET", f"/files/{orphan['id']}", token=token)
show("宽限期内文件仍在", st, r, ["id"])
st, r = call("POST", "/files/cleanup?graceMinutes=0", token=token)
show("cleanup graceMinutes=0", st, r, ["scanned", "removedFiles", "freedBytes", "hasMore"])
st, r = call("GET", f"/files/{orphan['id']}", token=token)
show("孤儿文件已删除", st, r)
st, r = call("GET", f"/files/{file_a['id']}", token=token)
show("被引用的文件不受影响", st, r, ["id"])

print("\n========== 12. 清理测试数据 ==========")
for asset in (text_asset, image_asset):
    st, r = call("DELETE", f"/assets/{asset['id']}", token=token)
    show(f"删除素材 {asset['title'][:10]}", st, r)
st, r = call("GET", "/assets?keyword=e2e", token=token)
show("列表里不再有已删素材", st, r, ["total"])

print("\n完成。磁盘目录：data/uploads/<sha前两位>/<sha256>")
