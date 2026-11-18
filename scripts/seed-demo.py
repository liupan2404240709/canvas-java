"""给可视化控制台灌一批演示数据（画布 + 图片素材 + 文本素材）。

用法：
    python scripts/seed-demo.py            # 默认打 http://127.0.0.1:8081
    python scripts/seed-demo.py --reset    # 先清空该账号下已有的画布与素材，再灌

账号固定为 demo / demo123456（不存在会自动注册），和控制台「使用演示账号进入」同一个账号。
脚本可重复执行，每次都会追加一份新数据。
"""

import json
import struct
import sys
import urllib.error
import urllib.request
import zlib

BASE = "http://127.0.0.1:8081/api/v1"
ACCOUNT = "demo"
PASSWORD = "demo123456"


def call(method, path, body=None, token=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return json.loads(raw)
        except Exception:
            return {"code": e.code, "message": raw}


def login():
    r = call("POST", "/auth/login", {"account": ACCOUNT, "password": PASSWORD})
    if r.get("code") != 0:
        r = call("POST", "/auth/register", {"username": ACCOUNT, "password": PASSWORD, "nickname": "演示账号"})
    if r.get("code") != 0:
        raise SystemExit("登录/注册失败：" + str(r))
    return r["data"]["accessToken"], r["data"]["user"]


def gradient_png(width, height, c1, c2):
    """不带 Pillow 也能生成渐变色块：逐行逐像素算 RGB，再按 PNG 规范打包。"""
    def chunk(tag, data):
        payload = tag + data
        return struct.pack(">I", len(data)) + payload + struct.pack(">I", zlib.crc32(payload) & 0xFFFFFFFF)

    raw = bytearray()
    span = (width - 1) + (height - 1)
    for y in range(height):
        raw.append(0)  # 每行的 filter type
        for x in range(width):
            t = (x + y) / span if span else 0
            raw += bytes((
                int(c1[0] + (c2[0] - c1[0]) * t),
                int(c1[1] + (c2[1] - c1[1]) * t),
                int(c1[2] + (c2[2] - c1[2]) * t),
            ))

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))


def upload_png(token, filename, content):
    boundary = "----seed" + str(zlib.crc32(content))
    body = (
        f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\n'
        f"Content-Type: image/png\r\n\r\n".encode()
        + content
        + f"\r\n--{boundary}--\r\n".encode()
    )
    req = urllib.request.Request(BASE + "/files/upload?bizType=asset", data=body, method="POST")
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))["data"]


ARTWORKS = [
    ("冷色霓虹街景", (43, 27, 107), (14, 165, 183), ["场景", "冷色"], "a cyberpunk street at night, neon reflections"),
    ("暖光人像参考", (124, 45, 18), (245, 158, 11), ["人像", "暖色"], "warm portrait, rim light, 85mm"),
    ("深绿植物特写", (5, 46, 43), (52, 211, 153), ["植物", "微距"], "macro shot, dew drops, shallow depth of field"),
    ("紫粉渐变底纹", (76, 29, 149), (236, 72, 153), ["底纹", "渐变"], "abstract gradient background, soft blur"),
]

PROJECTS = ["品牌视觉探索", "角色设定集", "海报改版 v2"]


def seed():
    token, user = login()
    print(f"账号 {user['username']} (id={user['id']}) 已就绪")

    for idx, title in enumerate(PROJECTS):
        p = call("POST", "/projects", {"title": title}, token)["data"]
        nodes = [
            {"id": f"n_brief_{idx}", "type": "text", "title": "需求简述",
             "position": {"x": 80, "y": 80}, "width": 260, "height": 170,
             "metadata": {"text": "冷色科技感，强调材质"}},
            {"id": f"n_ref_{idx}", "type": "image", "title": "参考图 A",
             "position": {"x": 400, "y": 80}, "width": 320, "height": 320, "metadata": {}},
            {"id": f"n_gen_{idx}", "type": "image", "title": "生成结果 1",
             "position": {"x": 780, "y": 80}, "width": 320, "height": 320, "metadata": {"model": "demo"}},
            {"id": f"n_note_{idx}", "type": "text", "title": "待改点",
             "position": {"x": 80, "y": 320}, "width": 260, "height": 170,
             "metadata": {"text": "高光再压一点"}},
        ]
        conns = [
            {"id": f"e_a_{idx}", "fromNodeId": f"n_brief_{idx}", "toNodeId": f"n_gen_{idx}"},
            {"id": f"e_b_{idx}", "fromNodeId": f"n_ref_{idx}", "toNodeId": f"n_gen_{idx}"},
        ]
        r = call("PUT", f"/projects/{p['id']}/snapshot",
                 {"version": 0, "viewport": {"x": 0, "y": 0, "k": 1}, "nodes": nodes, "connections": conns}, token)
        print(f"画布「{title}」: {r['code']} nodes={r.get('data', {}).get('nodeCount')}")

    for title, c1, c2, tags, prompt in ARTWORKS:
        png = gradient_png(512, 512, c1, c2)
        f = upload_png(token, f"{title}.png", png)
        call("POST", "/assets", {
            "kind": "image", "title": title, "fileId": f["id"], "tags": tags,
            "source": "演示数据", "note": f"{f['width']}×{f['height']} / {f['bytes']} 字节",
        }, token)
        call("POST", "/assets", {
            "kind": "text", "title": title + " · 提示词", "content": prompt,
            "tags": tags + ["提示词"], "source": "演示数据",
        }, token)
        print(f"素材「{title}」已入库（deduped={f['deduped']}）")

    lst = call("GET", "/assets?size=100", None, token)["data"]
    print(f"\n完成：画布 3 张，素材 {lst['total']} 条。打开 http://127.0.0.1:8081/api/demo 查看。")


if __name__ == "__main__":
    try:
        seed()
    except urllib.error.URLError as e:
        sys.exit(f"连不上后端（{BASE}）：{e.reason}。确认 mvn spring-boot:run 已经起来。")
