"""阶段 4 全链路验证：提示词源 CRUD / 同步 / 幂等 / 搜索 / 标签 / 分页 / 失败记录。

用法：
    python scripts/stage4-e2e.py

默认打 http://127.0.0.1:8081/api/v1（和 application.yml 的端口一致）。

**不依赖外网**：同步用的「上游」是本服务自己托管的
`/api/demo/prompts-sample.json`（8 条样例提示词），所以断网也能跑。
要验证真实 registry，把 SAMPLE_URL 换成
https://raw.githubusercontent.com/yukkcat/image-prompts/main/dist/sources/awesome-gpt-image.json

脚本可重复执行：每次用固定账号 prompt_e2e 登录，开头会删掉上次遗留的自定义源。
"""

import json
import urllib.error
import urllib.request
from urllib.parse import quote

BASE = "http://127.0.0.1:8081/api/v1"
SAMPLE_URL = "http://127.0.0.1:8081/api/demo/prompts-sample.json"
ACCOUNT = "prompt_e2e"
PASSWORD = "prompt1234"

failures = []


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
        with urllib.request.urlopen(req, timeout=90) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw}


def show(label, status, obj, keys=None):
    data = obj.get("data") if isinstance(obj, dict) else None
    if keys and isinstance(data, dict):
        brief = {k: data.get(k) for k in keys}
    elif keys and isinstance(data, list):
        brief = f"list[{len(data)}]"
    else:
        brief = data if data is not None else obj.get("message")
    text = json.dumps(brief, ensure_ascii=False)
    print(f"{label:36s} HTTP {status} code={obj.get('code')} {text[:150]}")


def expect(condition, message):
    if not condition:
        failures.append(message)
        print(f"    !! 断言失败: {message}")


def ok(label, status, obj, keys=None):
    show(label, status, obj, keys)
    expect(status == 200 and obj.get("code") == 0, f"{label} 应成功，实际 HTTP {status} code={obj.get('code')}")


# ----------------------------------------------------------------------
# 准备账号
# ----------------------------------------------------------------------

def ensure_token():
    st, r = call("POST", "/auth/login", {"account": ACCOUNT, "password": PASSWORD})
    if st == 200:
        return r["data"]["accessToken"]
    st, r = call("POST", "/auth/register", {"username": ACCOUNT, "password": PASSWORD, "nickname": "提示词测试"})
    if st == 200:
        return r["data"]["accessToken"]
    raise SystemExit(f"无法登录或注册 {ACCOUNT}: {r}")


token = ensure_token()
print("已登录", ACCOUNT)

# ----------------------------------------------------------------------
# 1. 内置源播种
# ----------------------------------------------------------------------
print("\n========== 1. 内置源播种 ==========")
st, r = call("GET", "/prompt-sources", token=token)
ok("首次拉源列表", st, r)
sources = r["data"]
expect(len(sources) == 7, f"应有 7 个内置源，实际 {len(sources)}")
expect(all(item["builtIn"] for item in sources), "内置源 builtIn 应为 true")
expect(all(item["count"] == 0 for item in sources), "初次同步前 count 应为 0")
expect(sources[0]["id"] == "banana-prompt-quicker", "内置源业务键应保持 registry id")
print(f"    内置源: {[s['id'] for s in sources]}")

# 清理上次可能残留的自定义源，保证可重复执行
for item in sources:
    if not item["builtIn"]:
        call("DELETE", f"/prompt-sources/{item['id']}", token=token)

# ----------------------------------------------------------------------
# 2. 内置源只能停用，不能改也不能删
# ----------------------------------------------------------------------
print("\n========== 2. 内置源保护 ==========")
st, r = call("PATCH", "/prompt-sources/banana-prompt-quicker", {"name": "改个名"}, token=token)
show("改内置源名称", st, r)
expect(st == 409 and r.get("code") == 40941, "内置源改名应返回 409/40941")

st, r = call("DELETE", "/prompt-sources/banana-prompt-quicker", token=token)
show("删内置源", st, r)
expect(st == 409 and r.get("code") == 40941, "删内置源应返回 409/40941")

st, r = call("PATCH", "/prompt-sources/banana-prompt-quicker", {"enabled": False}, token=token)
ok("停用内置源", st, r, ["id", "enabled"])
st, r = call("GET", "/prompts", token=token)
ok("停用后分栏不含它", st, r, ["categories", "total"])
expect("Banana Prompt Quicker" not in r["data"]["categories"], "停用的源不应出现在 categories")
st, r = call("PATCH", "/prompt-sources/banana-prompt-quicker", {"enabled": True}, token=token)
ok("重新启用", st, r, ["id", "enabled"])

# ----------------------------------------------------------------------
# 3. 新增自定义源 + 校验
# ----------------------------------------------------------------------
print("\n========== 3. 新增源与参数校验 ==========")
st, r = call("POST", "/prompt-sources", {"name": "本地样例库", "url": SAMPLE_URL,
                                         "homepage": "https://example.com/prompts",
                                         "refreshIntervalMinutes": 0}, token=token)
ok("新增源", st, r, ["id", "name", "enabled", "builtIn", "refreshIntervalMinutes"])
source_key = r["data"]["id"] if r.get("data") else None
expect(source_key and source_key.startswith("src_"), "自定义源业务键应以 src_ 开头")

st, r = call("POST", "/prompt-sources", {"name": "本地样例库", "url": SAMPLE_URL}, token=token)
show("同名再建一次", st, r)
expect(st == 409 and r.get("code") == 40940, "同名源应返回 409/40940")

st, r = call("POST", "/prompt-sources", {"name": "坏地址", "url": "file:///etc/passwd"}, token=token)
show("file:// 地址", st, r)
expect(st == 400 and r.get("code") == 40040, "非 http/https 地址应返回 400/40040")

st, r = call("POST", "/prompt-sources", {"name": "", "url": SAMPLE_URL}, token=token)
show("空名称", st, r)
expect(st == 400, "空名称应被 Bean Validation 拦下")

# ----------------------------------------------------------------------
# 4. 第一次同步
# ----------------------------------------------------------------------
print("\n========== 4. 首次同步 ==========")
st, r = call("POST", f"/prompt-sources/{source_key}/refresh", token=token)
ok("首次同步", st, r, ["sourceId", "sourceName", "count", "success", "skipped", "lastError"])
first = r["data"] if r.get("data") else {}
expect(first.get("success") is True, "首次同步应成功")
expect(first.get("skipped") is False, "首次同步不应被跳过")
expect(first.get("count") == 8, f"应同步 8 条，实际 {first.get('count')}")

st, r = call("GET", "/prompt-sources", token=token)
ok("源列表带上同步状态", st, r)
mine = [s for s in r["data"] if s["id"] == source_key][0]
expect(mine["count"] == 8, "源上的 count 应更新为 8")
expect(mine["lastSuccessAt"] != "", "lastSuccessAt 应已填充")
expect(mine["lastError"] == "", "成功后 lastError 应为空串")

# ----------------------------------------------------------------------
# 5. 第二次同步：内容没变，应跳过写库
# ----------------------------------------------------------------------
print("\n========== 5. 幂等同步（内容摘要命中） ==========")
st, r = call("POST", f"/prompt-sources/{source_key}/refresh", token=token)
ok("第二次同步", st, r, ["count", "success", "skipped"])
second = r["data"] if r.get("data") else {}
expect(second.get("success") is True, "第二次同步也应 success=true")
expect(second.get("skipped") is True, "内容未变化应 skipped=true")
expect(second.get("count") == 8, "条数应保持 8")

st, r = call("POST", f"/prompt-sources/{source_key}/refresh?force=true", token=token)
ok("force=true 强制重写", st, r, ["count", "skipped"])
expect((r.get("data") or {}).get("skipped") is False, "force=true 不应跳过")

# ----------------------------------------------------------------------
# 6. 列表 / 字段契约
# ----------------------------------------------------------------------
print("\n========== 6. 列表与字段契约 ==========")
st, r = call("GET", "/prompts?page=1&pageSize=20", token=token)
ok("提示词列表", st, r, ["total", "categories", "page", "pageSize"])
data = r["data"]
expect(data["total"] == 8, f"total 应为 8，实际 {data['total']}")
expect(len(data["items"]) == 8, "当页应返回 8 条")
expect("本地样例库" in data["categories"], "categories 应包含自定义源名称")
item = data["items"][0]
expect(item["category"] == "本地样例库", "category 应等于源名称")
expect(item["sourceId"] == source_key, "sourceId 应等于源业务键")
expect(item["id"] == "e2e-product-shot", "首条应是源 JSON 里的第一条（保持策展顺序）")
expect(item["coverUrl"].startswith("http://127.0.0.1:8081/api/demo/images/"), "相对封面应补成绝对地址")
expect(len(item["referenceImageUrls"]) == 2, "参考图应为 2 张")
expect(item["createdAt"] == "2025-03-11T09:20:00", f"createdAt 应解析成 ISO，实际 {item['createdAt']}")
expect(item["githubUrl"] == "https://example.com/prompts/product-shot", "githubUrl 应取该条 sourceUrl")
print("    首条:", json.dumps({k: item[k] for k in ("id", "title", "category", "tags", "coverUrl")}, ensure_ascii=False))

# 缺 sourceUrl 的条目，githubUrl 应回落成源主页
no_url = [x for x in data["items"] if x["id"] == "e2e-cyberpunk-street"][0]
expect(no_url["githubUrl"] == "https://example.com/prompts", "缺 sourceUrl 时应回落成源 homepage")

# ----------------------------------------------------------------------
# 7. 搜索 / 标签 / 分栏
# ----------------------------------------------------------------------
print("\n========== 7. 筛选 ==========")
st, r = call("GET", "/prompts?keyword=霓虹", token=token)
ok("关键字『霓虹』", st, r, ["total"])
expect(r["data"]["total"] == 1, f"关键字应命中 1 条，实际 {r['data']['total']}")

st, r = call("GET", "/prompts?keyword=watercolor", token=token)
ok("关键字命中正文（英文）", st, r, ["total"])
expect(r["data"]["total"] == 1, "应命中水彩那条的正文")

st, r = call("GET", "/prompts?tag=人像", token=token)
ok("单标签", st, r, ["total", "tags"])
expect(r["data"]["total"] == 1, "『人像』应命中 1 条")

st, r = call("GET", "/prompts?tag=人像&tag=美食", token=token)
ok("双标签（或关系）", st, r, ["total"])
expect(r["data"]["total"] == 2, f"两个标签应是「或」，共 2 条，实际 {r['data']['total']}")

st, r = call("GET", "/prompts?category=本地样例库", token=token)
ok("按分栏过滤", st, r, ["total", "categories"])
expect(r["data"]["total"] == 8, "该分栏下应有 8 条")

st, r = call("GET", "/prompts?category=不存在的源", token=token)
ok("分栏不存在", st, r, ["total", "categories"])
expect(r["data"]["total"] == 0, "不存在的分栏应返回空列表而不是报错")
expect(len(r["data"]["items"]) == 0, "items 应为空")

# 标签云：勾了标签后其他标签仍要在（否则没法多选）
st, r = call("GET", "/prompts?tag=人像", token=token)
tags = r["data"]["tags"]
expect("美食" in tags, "标签云不应受已选标签影响（应包含『美食』）")
print("    标签云:", tags)

# ----------------------------------------------------------------------
# 8. 分页稳定性
# ----------------------------------------------------------------------
print("\n========== 8. 分页 ==========")
st, r1 = call("GET", "/prompts?page=1&pageSize=3", token=token)
st, r2 = call("GET", "/prompts?page=2&pageSize=3", token=token)
st, r3 = call("GET", "/prompts?page=3&pageSize=3", token=token)
ids1 = [x["id"] for x in r1["data"]["items"]]
ids2 = [x["id"] for x in r2["data"]["items"]]
ids3 = [x["id"] for x in r3["data"]["items"]]
print("    p1:", ids1)
print("    p2:", ids2)
print("    p3:", ids3)
expect(len(ids1) == 3 and len(ids2) == 3 and len(ids3) == 2, "三页条数应为 3/3/2")
expect(len(set(ids1 + ids2 + ids3)) == 8, "三页合并应恰好覆盖 8 条且不重复")
expect(r3["data"]["page"] == 3, "page 应原样返回")

st, r = call("GET", "/prompts?page=0&pageSize=1000", token=token)
ok("越界参数被压制", st, r, ["page", "pageSize"])
expect(r["data"]["page"] == 1, "page=0 应被压成 1")
expect(r["data"]["pageSize"] == 100, "pageSize=1000 应被压成 100")

# ----------------------------------------------------------------------
# 9. 同步失败也要被记录，而不是抛 500
# ----------------------------------------------------------------------
print("\n========== 9. 同步失败记录 ==========")
st, r = call("POST", "/prompt-sources", {"name": "坏源", "url": "http://127.0.0.1:8081/api/nope.json"}, token=token)
ok("新增一个 404 源", st, r, ["id"])
bad_key = r["data"]["id"]

st, r = call("POST", f"/prompt-sources/{bad_key}/refresh", token=token)
show("同步 404 源", st, r, ["success", "lastError", "count"])
expect(st == 200, "同步失败不应抛 HTTP 错误（这是任务结果不是接口失败）")
expect((r.get("data") or {}).get("success") is False, "success 应为 false")
expect("404" in ((r.get("data") or {}).get("lastError") or ""), "lastError 应包含 HTTP 404")

st, r = call("GET", "/prompt-sources", token=token)
bad = [s for s in r["data"] if s["id"] == bad_key][0]
expect(bad["lastError"] != "", "失败原因应持久化到源上")
expect(bad["count"] == 0, "失败源的 count 应保持 0")
call("DELETE", f"/prompt-sources/{bad_key}", token=token)

# 非 JSON 数组也要给出可读错误
st, r = call("POST", "/prompt-sources", {"name": "非数组源", "url": "http://127.0.0.1:8081/api/v1/health"}, token=token)
not_array_key = r["data"]["id"]
st, r = call("POST", f"/prompt-sources/{not_array_key}/refresh", token=token)
show("同步非数组源", st, r, ["success", "lastError"])
expect("JSON 数组" in ((r.get("data") or {}).get("lastError") or ""), "lastError 应说明不是 JSON 数组")
call("DELETE", f"/prompt-sources/{not_array_key}", token=token)

# ----------------------------------------------------------------------
# 10. 同步全部
# ----------------------------------------------------------------------
print("\n========== 10. 同步全部 ==========")
st, r = call("POST", "/prompt-sources/refresh?force=true", token=token)
ok("同步全部（force）", st, r, ["total", "successCount", "failureCount", "skippedCount"])
summary = r["data"]
expect(len(summary["results"]) >= 8, "结果应覆盖所有启用源（7 内置 + 1 自定义）")
print(f"    success={summary['successCount']} failure={summary['failureCount']} "
      f"（内置源依赖外网，失败是正常的，只要能拿到 lastError 就算流程正确）")
builtin_failed = [x for x in summary["results"] if not x["success"] and x["sourceId"] in
                  ("banana-prompt-quicker", "awesome-gpt-image")]
for item_result in builtin_failed[:1]:
    print(f"    内置源失败示例: {item_result['sourceId']} -> {item_result['lastError'][:80]}")

# ----------------------------------------------------------------------
# 11. 归属隔离
# ----------------------------------------------------------------------
print("\n========== 11. 越权 ==========")
st, r = call("POST", "/auth/register", {"username": "prompt_e2e_other", "password": "prompt1234"})
other = r["data"]["accessToken"] if st == 200 else None
if other is None:
    st, r = call("POST", "/auth/login", {"account": "prompt_e2e_other", "password": "prompt1234"})
    other = r["data"]["accessToken"]
st, r = call("POST", f"/prompt-sources/{source_key}/refresh", token=other)
show("别人的源同步", st, r)
expect(st == 404 and r.get("code") == 40440, "越权访问别人的源应返回 404/40440")
st, r = call("DELETE", f"/prompt-sources/{source_key}", token=other)
show("别人的源删除", st, r)
expect(st == 404, "越权删除应返回 404")
st, r = call("GET", "/prompts", token=other)
ok("新用户看不到别人的提示词", st, r, ["total"])
expect(r["data"]["total"] == 0, "新用户 total 应为 0")

# ----------------------------------------------------------------------
# 12. 删除源连带删提示词
# ----------------------------------------------------------------------
print("\n========== 12. 删除源 ==========")
st, r = call("DELETE", f"/prompt-sources/{source_key}", token=token)
ok("删除自定义源", st, r)
st, r = call("GET", "/prompts", token=token)
ok("提示词随源删除", st, r, ["total"])
expect(r["data"]["total"] == 0, "源删除后提示词应为 0 条")
st, r = call("GET", "/prompt-sources", token=token)
expect(all(s["builtIn"] for s in r["data"]), "列表里只剩内置源")

print("\n========== 结果 ==========")
if failures:
    print(f"{len(failures)} 项断言失败：")
    for item_text in failures:
        print("  -", item_text)
    raise SystemExit(1)
print("全部断言通过")
