"""阶段 5 全链路验证：渠道 CRUD / Key 加密与脱敏 / 探测 / 连通性测试 / 模型差量 / 跨渠道模型。

用法：
    python scripts/stage5-e2e.py

默认打 http://127.0.0.1:8081/api/v1（和 application.yml 的端口一致）。

**不依赖外网**：脚本自己在本机起一个 mock 上游（随机端口），模拟五种上游行为：
    /v1/models          OpenAI 兼容，3 个模型
    /v1beta/models      Gemini 格式（模型名带 models/ 前缀）
    /empty/v1/models    连得上但一个模型都没有
    /unauth/v1/models   401 鉴权失败
    /html/v1/models     200 但返回 HTML（模拟把控制台首页填成了接口地址）

这样「探测成功 / 401 / 非 JSON / 空列表 / Gemini 前缀剥离」五种分支都能在断网环境复现。

脚本可重复执行：固定账号 channel_e2e，开头会清空该账号下的所有渠道。
"""

import json
import threading
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import quote

BASE = "http://127.0.0.1:8081/api/v1"
ACCOUNT = "channel_e2e"
PASSWORD = "channel1234"
# 明文 Key 里带完整的前后各 4 位，用来验证脱敏格式，以及"响应里任何地方都不出现明文"
PLAIN_KEY = "sk-e2e-secret-key-abcdef"
EXPECTED_HINT = "sk-e****cdef"

failures = []


# ----------------------------------------------------------------------
# mock 上游
# ----------------------------------------------------------------------

class MockHandler(BaseHTTPRequestHandler):
    def do_GET(self):  # noqa: N802
        path = self.path.split("?")[0]
        if path == "/v1/models":
            self._json(200, {"object": "list", "data": [
                {"id": "mock-image-v1", "object": "model"},
                {"id": "mock-video-v1", "object": "model"},
                {"id": "mock-chat-v1", "object": "model"},
            ]})
        elif path == "/v1beta/models":
            # Gemini 的真实响应：name 带 models/ 前缀，且没有 id 字段
            self._json(200, {"models": [
                {"name": "models/gemini-2.0-flash"},
                {"name": "models/imagen-3.0-generate-001"},
            ]})
        elif path == "/empty/v1/models":
            self._json(200, {"object": "list", "data": []})
        elif path == "/unauth/v1/models":
            self._json(401, {"error": {"message": "Incorrect API key provided"}})
        elif path == "/html/v1/models":
            body = b"<!doctype html><html><body>console</body></html>"
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self._json(404, {"error": {"message": "not found"}})

    def _json(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        pass


def start_mock():
    server = ThreadingHTTPServer(("127.0.0.1", 0), MockHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, f"http://127.0.0.1:{server.server_address[1]}"


MOCK, MOCK_BASE = start_mock()


# ----------------------------------------------------------------------
# HTTP 小工具
# ----------------------------------------------------------------------

def call(method, path, body=None, token=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + quote(path, safe="/?&=:"), data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, (json.loads(raw) if raw else {}), raw
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw), raw
        except Exception:
            return e.code, {"raw": raw}, raw


def show(label, status, obj, keys=None):
    data = obj.get("data") if isinstance(obj, dict) else None
    if keys and isinstance(data, dict):
        brief = {k: data.get(k) for k in keys}
    elif keys and isinstance(data, list):
        brief = f"list[{len(data)}]"
    else:
        brief = data if data is not None else obj.get("message")
    text = json.dumps(brief, ensure_ascii=False)
    print(f"{label:34s} HTTP {status} code={obj.get('code')} {text[:140]}")


def expect(condition, message):
    if not condition:
        failures.append(message)
        print(f"    !! 断言失败: {message}")


def ok(label, status, obj, keys=None):
    show(label, status, obj, keys)
    expect(status == 200 and obj.get("code") == 0,
           f"{label} 应成功，实际 HTTP {status} code={obj.get('code')}")


def ensure_token():
    st, r, _ = call("POST", "/auth/login", {"account": ACCOUNT, "password": PASSWORD})
    if st == 200 and r.get("code") == 0:
        return r["data"]["accessToken"]
    st, r, _ = call("POST", "/auth/register", {"username": ACCOUNT, "password": PASSWORD})
    if st == 200 and r.get("code") == 0:
        return r["data"]["accessToken"]
    st, r, _ = call("POST", "/auth/login", {"account": ACCOUNT, "password": PASSWORD})
    if st == 200 and r.get("code") == 0:
        return r["data"]["accessToken"]
    raise SystemExit(f"无法取得 token: HTTP {st} {r}")


token = ensure_token()
print(f"mock 上游: {MOCK_BASE}")
print(f"账号 {ACCOUNT} 已就绪")

# 清空历史数据，保证可重复执行
st, r, _ = call("GET", "/channels", token=token)
for item in (r.get("data") or []):
    call("DELETE", f"/channels/{item['id']}", token=token)

MODELS = [
    {"name": "mock-image-v1"},
    {"name": "mock-video-v1"},
    {"name": "mock-chat-v1", "capability": "text"},
]

# ----------------------------------------------------------------------
# 1. 厂商预设
# ----------------------------------------------------------------------
print("\n========== 1. 国产厂商预设 ==========")
st, r, _ = call("GET", "/channels/presets", token=token)
ok("预设列表", st, r)
presets = r["data"]
keys = [p["key"] for p in presets]
print("    厂商:", ", ".join(f"{p['name']}" for p in presets))
expect(len(presets) >= 6, f"预设应有至少 6 家，实际 {len(presets)}")
expect("bailian" in keys and "ark" in keys, "应包含百炼与火山方舟")
expect(all("apiKey" not in p for p in presets), "预设里不允许出现 apiKey 字段")
bailian = next(p for p in presets if p["key"] == "bailian")
expect(bailian["baseUrl"].endswith("/compatible-mode/v1"), "百炼 baseUrl 应以 /compatible-mode/v1 结尾")
expect(any(m["capability"] == "image" for m in bailian["models"]), "百炼预设应含生图模型")
ark = next(p for p in presets if p["key"] == "ark")
expect(ark["baseUrl"].endswith("/api/v3"), "方舟 baseUrl 应以 /api/v3 结尾")
deepseek = next(p for p in presets if p["key"] == "deepseek")
expect(not deepseek["baseUrl"].endswith("/v1"), "DeepSeek 预设故意不带 /v1，由拼接逻辑补")

# ----------------------------------------------------------------------
# 2. 参数校验
# ----------------------------------------------------------------------
print("\n========== 2. 参数校验 ==========")
st, r, _ = call("POST", "/channels", {"baseUrl": MOCK_BASE, "apiKey": ""}, token=token)
show("缺 apiKey", st, r)
expect(st == 400 and r.get("code") == 40051, "缺 apiKey 应返回 400/40051")

st, r, _ = call("POST", "/channels", {"baseUrl": "ftp://x.com/v1", "apiKey": PLAIN_KEY}, token=token)
show("非 http 地址", st, r)
expect(st == 400 and r.get("code") == 40050, "ftp 地址应返回 400/40050")

st, r, _ = call("POST", "/channels", {"baseUrl": "not-a-url", "apiKey": PLAIN_KEY}, token=token)
show("裸字符串地址", st, r)
expect(st == 400 and r.get("code") == 40050, "裸字符串应返回 400/40050")

st, r, _ = call("POST", "/channels",
                {"baseUrl": MOCK_BASE, "apiKey": PLAIN_KEY, "apiFormat": "grpc"}, token=token)
show("非法 apiFormat", st, r)
expect(st == 400, "非法 apiFormat 应返回 400")

st, r, _ = call("POST", "/channels",
                {"baseUrl": MOCK_BASE, "apiKey": PLAIN_KEY,
                 "models": [{"name": "x", "capability": "picture"}]}, token=token)
show("非法 capability", st, r)
expect(st == 400 and r.get("code") == 40052, "非法 capability 应返回 400/40052")

# ----------------------------------------------------------------------
# 3. 新建渠道
# ----------------------------------------------------------------------
print("\n========== 3. 新建渠道 ==========")
st, r, raw = call("POST", "/channels", {
    "baseUrl": MOCK_BASE + "/v1/",   # 故意带末尾斜杠，验证归一化
    "apiKey": PLAIN_KEY,
    "models": MODELS,
}, token=token)
ok("新建（名字留空，自动取主机名）", st, r,
   ["id", "name", "baseUrl", "apiKeyHint", "models"])
expect(PLAIN_KEY not in raw, "响应里绝不能出现明文 API Key")
channel = r["data"]
channel_key = channel["id"]
expect(channel["baseUrl"].endswith("/v1"), "末尾斜杠应被去掉")
expect(channel["name"] == "127.0.0.1", f"名字应自动取主机名，实际 {channel['name']}")
expect(channel["apiKeyHint"] == EXPECTED_HINT, f"脱敏应为 {EXPECTED_HINT}，实际 {channel['apiKeyHint']}")
expect(len(channel["models"]) == 3, "应落库 3 个模型")
by_name = {m["name"]: m for m in channel["models"]}
expect(by_name["mock-image-v1"]["capability"] == "image", "mock-image-v1 应猜成 image")
expect(by_name["mock-video-v1"]["capability"] == "video", "mock-video-v1 应猜成 video")
expect(by_name["mock-chat-v1"]["capability"] == "text", "mock-chat-v1 应猜成 text")
expect([m["sortIndex"] for m in channel["models"]] == [0, 1, 2], "sortIndex 应按传入顺序 0/1/2")
# 从未测试过的渠道，lastTestOk 是 null —— Jackson 配了 non_null，字段会整个不出现
expect(channel.get("lastTestOk") is None, "新建渠道 lastTestOk 应为 null（从未测试）")

# 同名模型去重
st, r2, _ = call("POST", "/channels", {
    "name": "去重测试", "baseUrl": MOCK_BASE + "/v1", "apiKey": PLAIN_KEY,
    "models": [{"name": "dup"}, {"name": "dup"}, {"name": "  "}],
}, token=token)
ok("重复模型名去重", st, r2, ["name", "models"])
dup_key = r2["data"]["id"]
expect(len(r2["data"]["models"]) == 1, f"3 条重复/空名应只留 1 条，实际 {len(r2['data']['models'])}")
call("DELETE", f"/channels/{dup_key}", token=token)

# ----------------------------------------------------------------------
# 4. 跨渠道模型
# ----------------------------------------------------------------------
print("\n========== 4. 跨渠道可用模型 ==========")
st, r, _ = call("GET", "/models", token=token)
ok("全部模型", st, r)
expect(len(r["data"]) == 3, f"应有 3 个模型，实际 {len(r['data'])}")
expect(all(m["id"] == f"{channel_key}::{m['name']}" for m in r["data"]),
       "id 必须是 channelKey::modelName")
expect(all(m["channelId"] == channel_key for m in r["data"]), "channelId 应为渠道业务键")

st, r, _ = call("GET", "/models?capability=image", token=token)
ok("按能力过滤 image", st, r)
expect(len(r["data"]) == 1 and r["data"][0]["name"] == "mock-image-v1", "image 只剩 1 条")

st, r, _ = call("GET", "/models?capability=video", token=token)
ok("按能力过滤 video", st, r)
expect(len(r["data"]) == 1, "video 只剩 1 条")

st, r, _ = call("GET", "/models?capability=audio", token=token)
ok("按能力过滤 audio（无）", st, r)
expect(len(r["data"]) == 0, "没有 audio 模型")

st, r, _ = call("GET", "/models?capability=picture", token=token)
show("非法能力过滤", st, r)
expect(st == 400 and r.get("code") == 40052, "非法能力应返回 400/40052")

# ----------------------------------------------------------------------
# 5. 探测（四种上游行为）
# ----------------------------------------------------------------------
print("\n========== 5. 探测（不保存）==========")
st, r, _ = call("POST", "/channels/probe",
                {"baseUrl": MOCK_BASE, "apiKey": PLAIN_KEY}, token=token)
ok("探测正常地址", st, r, ["ok", "message", "latencyMs", "httpStatus"])
probe = r["data"]
expect(probe["ok"] is True, "应连通")
expect(len(probe["models"]) == 3, f"应发现 3 个模型，实际 {len(probe['models'])}")
expect(all(m["exists"] is False for m in probe["models"]), "草稿渠道不应有 exists=true")
expect(probe["latencyMs"] > 0, "耗时应大于 0")

st, r, _ = call("POST", "/channels/probe",
                {"baseUrl": MOCK_BASE + "/v1", "apiKey": PLAIN_KEY}, token=token)
ok("探测（地址已带 /v1）", st, r, ["ok", "models"])

st, r, _ = call("POST", "/channels/probe",
                {"baseUrl": MOCK_BASE + "/unauth", "apiKey": PLAIN_KEY}, token=token)
show("探测 401", st, r, ["ok", "message", "httpStatus"])
probe = r["data"]
expect(probe["ok"] is False and probe["httpStatus"] == 401, "401 应识别为失败")
expect("鉴权" in probe["message"], f"401 的提示应提到鉴权，实际：{probe['message']}")

st, r, _ = call("POST", "/channels/probe",
                {"baseUrl": MOCK_BASE + "/html", "apiKey": PLAIN_KEY}, token=token)
show("探测返回 HTML", st, r, ["ok", "message"])
probe = r["data"]
expect(probe["ok"] is False, "HTML 响应不算连通")
expect("JSON" in probe["message"], f"提示应说明不是 JSON，实际：{probe['message']}")

st, r, _ = call("POST", "/channels/probe",
                {"baseUrl": MOCK_BASE + "/empty", "apiKey": PLAIN_KEY}, token=token)
show("探测空列表", st, r, ["ok", "message"])
probe = r["data"]
expect(probe["ok"] is True and len(probe["models"]) == 0, "空列表应算连通但 0 个模型")

st, r, _ = call("POST", "/channels/probe",
                {"baseUrl": MOCK_BASE, "apiKey": PLAIN_KEY, "apiFormat": "gemini"}, token=token)
ok("探测 Gemini（地址不带版本段，应补 /v1beta/models）", st, r, ["ok", "models"])
names = [m["name"] for m in r["data"]["models"]]
expect("gemini-2.0-flash" in names, f"Gemini 的 models/ 前缀应被剥掉，实际 {names}")
expect(all(not n.startswith("models/") for n in names), "模型名不应保留 models/ 前缀")

st, r, _ = call("POST", "/channels/probe", {"baseUrl": MOCK_BASE}, token=token)
show("探测缺 Key 且无 channelKey", st, r)
expect(st == 400 and r.get("code") == 40051, "既没 Key 也没 channelKey 应返回 40051")

st, r, _ = call("POST", "/channels/probe",
                {"channelKey": channel_key, "baseUrl": MOCK_BASE}, token=token)
ok("探测用已存 Key（body 不带 apiKey）", st, r, ["ok", "models"])
expect(all(m["exists"] is True for m in r["data"]["models"]), "已有模型应标记 exists=true")

# ----------------------------------------------------------------------
# 6. 测试已保存渠道并落库
# ----------------------------------------------------------------------
print("\n========== 6. 测试已保存渠道 ==========")
st, r, _ = call("POST", f"/channels/{channel_key}/test", token=token)
ok("测试渠道", st, r, ["ok", "message", "latencyMs"])
expect(r["data"]["ok"] is True, "mock 地址应测试通过")

st, r, _ = call("GET", f"/channels/{channel_key}", token=token)
ok("测试结果已落库", st, r, ["lastTestOk", "lastTestAt", "lastTestError"])
expect(r["data"]["lastTestOk"] is True, "lastTestOk 应为 true")
expect(r["data"]["lastTestAt"] != "", "lastTestAt 应有值")

st, r, _ = call("POST", "/channels", {
    "name": "坏地址", "baseUrl": MOCK_BASE + "/unauth", "apiKey": "sk-bad-key-0000", "models": [],
}, token=token)
bad_key = r["data"]["id"]
st, r, _ = call("POST", f"/channels/{bad_key}/test", token=token)
show("测试失败渠道", st, r, ["ok", "message"])
expect(r["data"]["ok"] is False, "401 渠道应测试失败")
st, r, _ = call("GET", f"/channels/{bad_key}", token=token)
ok("失败原因已落库", st, r, ["lastTestOk", "lastTestError"])
expect(r["data"]["lastTestOk"] is False, "lastTestOk 应为 false")
expect("鉴权" in r["data"]["lastTestError"], "lastTestError 应记录鉴权失败")

# 修好地址后再测一次，验证 lastTestError 会被清空（MyBatis-Plus 默认忽略 null 的坑）
call("PUT", f"/channels/{bad_key}", {"baseUrl": MOCK_BASE, "apiKey": PLAIN_KEY}, token=token)
st, r, _ = call("POST", f"/channels/{bad_key}/test", token=token)
ok("修好地址后重测", st, r, ["ok"])
st, r, _ = call("GET", f"/channels/{bad_key}", token=token)
ok("成功后 lastTestError 被清空", st, r, ["lastTestOk", "lastTestError"])
expect(r["data"]["lastTestError"] == "", f"成功后失败原因应清空，实际 {r['data']['lastTestError']}")
call("DELETE", f"/channels/{bad_key}", token=token)

# ----------------------------------------------------------------------
# 7. 保存配置（差量语义）
# ----------------------------------------------------------------------
print("\n========== 7. 保存配置 ==========")
st, r, raw = call("PUT", f"/channels/{channel_key}", {"name": "改过的名字"}, token=token)
ok("只改名字，Key 留空", st, r, ["name", "apiKeyHint"])
expect(r["data"]["name"] == "改过的名字", "名字应更新")
expect(r["data"]["apiKeyHint"] == EXPECTED_HINT, "apiKey 留空时 Key 不应变化")
expect(len(r["data"]["models"]) == 3, "models 传 null 时不动模型")
expect(PLAIN_KEY not in raw, "响应里仍不能出现明文")

st, r, _ = call("PUT", f"/channels/{channel_key}",
                {"models": MODELS + [{"name": "mock-audio-v1"}]}, token=token)
ok("模型加一个", st, r, ["models"])
expect(len(r["data"]["models"]) == 4, "应为 4 个模型")
audio = next(m for m in r["data"]["models"] if m["name"] == "mock-audio-v1")
expect(audio["capability"] == "audio", "mock-audio-v1 应猜成 audio")

st, r, _ = call("PUT", f"/channels/{channel_key}", {"models": MODELS}, token=token)
ok("模型减一个", st, r, ["models"])
expect(len(r["data"]["models"]) == 3, "应回到 3 个模型")

st, r, _ = call("PUT", f"/channels/{channel_key}", {"models": []}, token=token)
ok("空数组清空模型", st, r, ["models"])
expect(len(r["data"]["models"]) == 0, "空数组应清空所有模型")
st, r, _ = call("GET", "/models", token=token)
expect(len(r["data"]) == 0, "清空后 /models 应为空")

st, r, _ = call("PUT", f"/channels/{channel_key}", {"models": MODELS}, token=token)
ok("恢复模型", st, r, ["models"])
expect(len(r["data"]["models"]) == 3, "应恢复 3 个模型")

# 换 Key 后应清掉测试状态
st, r, _ = call("PUT", f"/channels/{channel_key}", {"apiKey": "sk-new-key-12345678"}, token=token)
ok("换 Key", st, r, ["apiKeyHint", "lastTestOk"])
expect(r["data"]["apiKeyHint"] == "sk-n****5678", f"新 Key 脱敏应为 sk-n****5678，实际 {r['data']['apiKeyHint']}")
expect(r["data"].get("lastTestOk") is None, "换 Key 后测试状态应被清空")

# ----------------------------------------------------------------------
# 8. 启停
# ----------------------------------------------------------------------
print("\n========== 8. 启停 ==========")
st, r, _ = call("PATCH", f"/channels/{channel_key}/enabled?value=false", token=token)
ok("停用渠道", st, r, ["enabled"])
expect(r["data"]["enabled"] is False, "enabled 应为 false")
st, r, _ = call("GET", "/models", token=token)
ok("停用后无可用模型", st, r)
expect(len(r["data"]) == 0, "停用渠道下的模型不应出现在 /models")

st, r, _ = call("PATCH", f"/channels/{channel_key}/enabled?value=true", token=token)
ok("启用渠道", st, r, ["enabled"])
st, r, _ = call("GET", "/models", token=token)
expect(len(r["data"]) == 3, "启用后应恢复 3 个")

st, r, _ = call("PUT", f"/channels/{channel_key}", {"models": [
    {"name": "mock-image-v1"},
    {"name": "mock-video-v1", "enabled": False},
    {"name": "mock-chat-v1"},
]}, token=token)
ok("单独停用一个模型", st, r, ["models"])
st, r, _ = call("GET", "/models", token=token)
ok("停用模型不出现在 /models", st, r)
expect(len(r["data"]) == 2, f"应剩 2 个，实际 {len(r['data'])}")
call("PUT", f"/channels/{channel_key}", {"models": MODELS}, token=token)

# ----------------------------------------------------------------------
# 9. 越权
# ----------------------------------------------------------------------
print("\n========== 9. 越权 ==========")
st, r, _ = call("POST", "/auth/register", {"username": "channel_e2e_other", "password": "channel1234"})
if st == 200 and r.get("code") == 0:
    other = r["data"]["accessToken"]
else:
    st, r, _ = call("POST", "/auth/login", {"account": "channel_e2e_other", "password": "channel1234"})
    other = r["data"]["accessToken"]

st, r, _ = call("GET", f"/channels/{channel_key}", token=other)
show("别人看我的渠道", st, r)
expect(st == 404 and r.get("code") == 40450, "越权读应返回 404/40450")
st, r, _ = call("PUT", f"/channels/{channel_key}", {"name": "劫持"}, token=other)
show("别人改我的渠道", st, r)
expect(st == 404, "越权改应返回 404")
st, r, _ = call("DELETE", f"/channels/{channel_key}", token=other)
show("别人删我的渠道", st, r)
expect(st == 404, "越权删应返回 404")
st, r, _ = call("POST", f"/channels/{channel_key}/test", token=other)
show("别人测我的渠道", st, r)
expect(st == 404, "越权测应返回 404")
st, r, _ = call("GET", "/models", token=other)
ok("新账号看不到别人的模型", st, r)
expect(len(r["data"]) == 0, "新账号模型数应为 0")
st, r, _ = call("GET", "/channels", token=other)
expect(len(r["data"]) == 0, "新账号渠道数应为 0")

# ----------------------------------------------------------------------
# 10. 删除
# ----------------------------------------------------------------------
print("\n========== 10. 删除 ==========")
st, r, _ = call("GET", "/channels", token=token)
before = len(r["data"])
st, r, _ = call("DELETE", f"/channels/{channel_key}", token=token)
ok("删除渠道", st, r)
st, r, _ = call("GET", f"/channels/{channel_key}", token=token)
show("删除后再查", st, r)
expect(st == 404, "删除后应 404")
st, r, _ = call("GET", "/channels", token=token)
ok("列表已减少", st, r)
expect(len(r["data"]) == before - 1, "列表应少一条")
st, r, _ = call("GET", "/models", token=token)
expect(len(r["data"]) == 0, "删除渠道后其模型也应消失")

# ----------------------------------------------------------------------
# 结果
# ----------------------------------------------------------------------
MOCK.shutdown()
print("\n========== 结果 ==========")
if failures:
    print(f"{len(failures)} 项断言失败：")
    for item in failures:
        print("  -", item)
    raise SystemExit(1)
print("全部断言通过")
