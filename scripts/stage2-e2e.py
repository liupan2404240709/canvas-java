import json
import threading
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8082/api/v1"


def call(method, path, body=None, token=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw}


def show(label, status, obj, keys=None):
    if keys:
        data = obj.get("data") or {}
        brief = {k: data.get(k) for k in keys} if isinstance(data, dict) else data
    else:
        brief = obj.get("data", obj.get("message"))
    text = json.dumps(brief, ensure_ascii=False)
    print(f"{label:36s} HTTP {status} code={obj.get('code')} {text[:120]}")


def login(account, password):
    st, r = call("POST", "/auth/login", {"account": account, "password": password})
    if st != 200:
        raise SystemExit(f"登录失败 {account}: {r}")
    return r["data"]["accessToken"]


print("========== 准备 ==========")
token = login("canvas_demo", "canvas1234")
print("main token ok")

st, r = call("POST", "/auth/register", {"username": "canvas_other", "password": "canvas1234"})
other = login("canvas_other", "canvas1234") if st not in (200, 409) else (
    r["data"]["accessToken"] if st == 200 else login("canvas_other", "canvas1234"))
print("other user token ok")

print("\n========== 1. 创建 ==========")
st, r = call("POST", "/projects", {"title": "阶段2验证画布"}, token)
pid = r["data"]["id"]
show("创建画布", st, r, ["id", "title", "nodeCount", "connectionCount", "updatedAt"])

print("\n========== 2. 空详情 ==========")
st, r = call("GET", f"/projects/{pid}", token=token)
d = r["data"]
print(f"空详情 HTTP {st} version={d['version']} nodes={len(d['nodes'])} edges={len(d['connections'])} "
      f"viewport={d['viewport']} showImageInfo={d.get('showImageInfo')}")

print("\n========== 3. 保存 30 节点 29 连线的快照 ==========")
nodes = [
    {
        "id": f"n_{i}",
        "type": "image" if i % 2 == 0 else "text",
        "title": f"节点{i}",
        "position": {"x": i * 40, "y": (i % 5) * 60},
        "width": 320,
        "height": 240,
        "metadata": {"prompt": f"提示词{i}", "model": "gpt-image-1", "count": i + 1,
                     "status": "success" if i % 3 else "idle", "freeResize": i % 2 == 0},
    }
    for i in range(30)
]
edges = [{"id": f"e_{i}", "fromNodeId": f"n_{i}", "toNodeId": f"n_{i+1}"} for i in range(29)]
snapshot = {"version": d["version"], "viewport": {"x": -120.5, "y": 33, "k": 0.85},
            "nodes": nodes, "connections": edges}
st, r = call("PUT", f"/projects/{pid}/snapshot", snapshot, token)
show("保存快照", st, r)
snap_version = r["data"]["version"]

print("\n========== 4. 重新拉取，校验 30 节点/29 连线 ==========")
st, r = call("GET", f"/projects/{pid}", token=token)
d2 = r["data"]
print(f"HTTP {st} version={d2['version']} nodes={len(d2['nodes'])} edges={len(d2['connections'])}")
print("首个节点:", json.dumps(d2["nodes"][0], ensure_ascii=False)[:200])
print("视口:", d2["viewport"])
print("节点顺序稳定（n_0 在前）:", d2["nodes"][0]["id"] == "n_0")

print("\n========== 5. 用旧版本号再保存（应 40920） ==========")
stale = dict(snapshot)
stale["nodes"] = nodes[:1]
stale["connections"] = []
st, r = call("PUT", f"/projects/{pid}/snapshot", stale, token)
show("旧版本保存", st, r)

print("\n========== 6. 用新版本号保存（只留 1 个节点，验证整份替换） ==========")
cur = {"version": snap_version, "nodes": [nodes[0]], "connections": []}
st, r = call("PUT", f"/projects/{pid}/snapshot", cur, token)
show("新版本保存", st, r)
st, r = call("GET", f"/projects/{pid}", token=token)
print(f"替换后 nodes={len(r['data']['nodes'])} edges={len(r['data']['connections'])} "
      f"（应为 1 / 0，且视口保持不变 {r['data']['viewport']}）")

print("\n========== 7. 重命名（PATCH 不改 version） ==========")
before = r["data"]["version"]
st, r = call("PATCH", f"/projects/{pid}", {"title": "改过名的画布"}, token)
show("重命名", st, r, ["title", "updatedAt"])
st, r = call("GET", f"/projects/{pid}", token=token)
print(f"version 是否未变: {r['data']['version'] == before} ({before} -> {r['data']['version']})")

print("\n========== 8. 仅切背景/视口 ==========")
st, r = call("PATCH", f"/projects/{pid}", {"backgroundMode": "dots", "showImageInfo": True,
                                           "viewport": {"x": 10, "y": 20, "k": 1.5}}, token)
st, r = call("GET", f"/projects/{pid}", token=token)
d3 = r["data"]
print(f"backgroundMode={d3['backgroundMode']} showImageInfo={d3.get('showImageInfo')} viewport={d3['viewport']}")

print("\n========== 9. 列表 + 关键字搜索 + 分页上限 ==========")
st, r = call("GET", "/projects?page=1&size=10", token=token)
print(f"列表 HTTP {st} total={r['data']['total']} pageSize={r['data']['pageSize']} 条数={len(r['data']['list'])}")
print("首条:", json.dumps(r["data"]["list"][0], ensure_ascii=False)[:160])
st, r = call("GET", "/projects?keyword=" + urllib.parse.quote("改过名"), token=token)
print(f"搜索'改过名' total={r['data']['total']} 命中={[i['title'] for i in r['data']['list']]}")
st, r = call("GET", "/projects?keyword=" + urllib.parse.quote("不存在的关键字xyz"), token=token)
print(f"搜索未命中 total={r['data']['total']}")
st, r = call("GET", "/projects?size=500", token=token)
print(f"size=500 被压到: {r['data']['pageSize']}")

print("\n========== 10. 复制 ==========")
st, r = call("POST", f"/projects/{pid}/copy", {}, token)
copy_id = r["data"]["id"]
show("复制画布", st, r, ["id", "title", "nodeCount", "connectionCount"])
st, r = call("GET", f"/projects/{copy_id}", token=token)
print(f"副本详情 nodes={len(r['data']['nodes'])} edges={len(r['data']['connections'])} "
      f"version={r['data']['version']} title={r['data']['title']}")

print("\n========== 11. 导出 ==========")
st, r = call("GET", f"/projects/{pid}/export", token=token)
exported = r["data"]
print(f"导出 HTTP {st} app={exported['app']} formatVersion={exported['formatVersion']} "
      f"title={exported['title']} nodes={len(exported['nodes'])}")

print("\n========== 12. 导入（用导出内容，改标题） ==========")
exported["title"] = "从文件导入的画布"
st, r = call("POST", "/projects/import", exported, token)
imported_id = r["data"]["id"]
print(f"导入 HTTP {st} id={imported_id} nodes={len(r['data']['nodes'])} title={r['data']['title']} "
      f"backgroundMode={r['data']['backgroundMode']}")

print("\n========== 13. 导入版本过高（应 400） ==========")
st, r = call("POST", "/projects/import", {"formatVersion": 99, "title": "未来版本", "nodes": []}, token)
show("formatVersion=99", st, r)

print("\n========== 14. 图结构校验（版本号必须是最新的，否则先撞 409） ==========")
st, r = call("GET", f"/projects/{pid}", token=token)
ver_now = r["data"]["version"]
st, r = call("PUT", f"/projects/{pid}/snapshot",
             {"version": ver_now, "nodes": nodes[:2],
              "connections": [{"id": "e_x", "fromNodeId": "n_0", "toNodeId": "n_99"}]},
             token)
show("连线指向不存在节点", st, r)
st, r = call("PUT", f"/projects/{pid}/snapshot",
             {"version": ver_now, "nodes": [nodes[0], nodes[0]], "connections": []}, token)
show("节点 id 重复", st, r)
st, r = call("PUT", f"/projects/{pid}/snapshot",
             {"version": ver_now, "nodes": [{"id": "n_1", "type": "text", "width": 10, "height": 10}]}, token)
show("节点缺 position", st, r)
st, r = call("PUT", f"/projects/{pid}/snapshot",
             {"version": ver_now, "nodes": [{"id": "n_1", "type": "text", "position": {"x": 0, "y": 0},
                                             "width": -5, "height": 10}]}, token)
show("节点宽度为负", st, r)

print("\n========== 15. 非法 id 格式（应 400 而不是 500） ==========")
st, r = call("GET", "/projects/abc", token=token)
show("GET /projects/abc", st, r)

print("\n========== 16. 越权：别人的画布（应 40410） ==========")
st, r = call("GET", f"/projects/{pid}", token=other)
show("他人访问详情", st, r)
st, r = call("DELETE", f"/projects/{pid}", token=other)
show("他人删除", st, r)
st, r = call("PUT", f"/projects/{pid}/snapshot", {"version": 0, "nodes": []}, other)
show("他人保存快照", st, r)

print("\n========== 17. 并发保存（同一版本，应一成一败） ==========")
st, r = call("GET", f"/projects/{pid}", token=token)
ver = r["data"]["version"]
results = []


def racer(idx):
    body = {"version": ver, "nodes": [dict(nodes[0], title=f"并发{idx}")], "connections": []}
    st2, r2 = call("PUT", f"/projects/{pid}/snapshot", body, token)
    results.append((idx, st2, r2.get("code")))


threads = [threading.Thread(target=racer, args=(i,)) for i in (1, 2)]
[t.start() for t in threads]
[t.join() for t in threads]
print("并发结果:", sorted(results))
print("成功数:", sum(1 for _, st2, _ in results if st2 == 200),
      "冲突数:", sum(1 for _, _, c in results if c == 40920))

print("\n========== 18. 删除 -> 回收站 -> 恢复 ==========")
st, r = call("DELETE", f"/projects/{pid}", token=token)
show("删除", st, r)
st, r = call("GET", f"/projects/{pid}", token=token)
show("删除后查详情", st, r)
st, r = call("GET", "/projects?keyword=" + urllib.parse.quote("改过名"), token=token)
print(f"列表里是否还有: {r['data']['total']}")
st, r = call("GET", "/projects/trash", token=token)
print(f"回收站 HTTP {st} 条数={len(r['data'])} 含本画布={any(i['id'] == pid for i in r['data'])}")
st, r = call("POST", f"/projects/{pid}/restore", None, token)
show("恢复", st, r)
st, r = call("GET", f"/projects/{pid}", token=token)
print(f"恢复后详情 HTTP {st} nodes={len(r['data']['nodes'])}（节点应还在）")

print("\n========== 19. 重复恢复（应 40410） ==========")
st, r = call("POST", f"/projects/{pid}/restore", None, token)
show("再次恢复", st, r)

print("\n========== 20. 清理测试数据 ==========")
for x in (copy_id, imported_id, pid):
    st, r = call("DELETE", f"/projects/{x}", token=token)
    print(f"删除 {x} -> {st}")
