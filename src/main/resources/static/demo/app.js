/* ============================================================
 * Infinite Canvas · 后端控制台
 * 纯原生实现，不引入构建链：直接把 /api/v1 的接口可视化出来。
 * 页面与接口同源（都由 Spring Boot 提供），所以不需要处理跨域。
 * ========================================================== */

/* ---------- 1. 环境与状态 ---------- */

// 页面地址形如 /api/demo/index.html，这里把 context-path 抽出来，
// 这样以后改 server.servlet.context-path 不用动前端代码。
const CTX = (() => {
    const m = location.pathname.match(/^(.*?)\/demo(\/|$)/);
    return m ? m[1] : "";
})();

const API = CTX + "/v1";

const state = {
    accessToken: "",
    refreshToken: "",
    user: null,
    view: "overview",
    health: null,
    projects: { list: [], total: 0 },
    assets: { list: [], total: 0 },
    tags: [],
    kind: "all",
    tag: "",
    keyword: "",
    prompts: { items: [], total: 0, tags: [], categories: [], sources: [], keyword: "", category: "all", tag: "" },
    trash: false,
    logs: [],
    logSeq: 0,
    expanded: new Set(),
    busy: false,
};

const TOKEN_KEY = "ic.demo.tokens";

/** 演示账号：后端是空库时也能一键进去，不需要先注册。 */
const DEMO_ACCOUNT = "demo";
const DEMO_PASSWORD = "demo123456";

/* ---------- 2. 小工具 ---------- */

const $ = (id) => document.getElementById(id);

function el(tag, attrs = {}, children = []) {
    const node = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
        if (k === "class") node.className = v;
        else if (k === "text") node.textContent = v;
        else if (k === "html") node.innerHTML = v;
        else if (k.startsWith("data-")) node.setAttribute(k, v);
        else if (k in node) node[k] = v;
        else node.setAttribute(k, v);
    }
    for (const c of [].concat(children)) {
        if (c == null) continue;
        node.append(c.nodeType ? c : document.createTextNode(String(c)));
    }
    return node;
}

function fmtBytes(n) {
    if (n == null || Number.isNaN(n)) return "-";
    if (n < 1024) return n + " B";
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + " KB";
    if (n < 1024 * 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + " MB";
    return (n / 1024 / 1024 / 1024).toFixed(2) + " GB";
}

function fmtTime(s) {
    if (!s) return "-";
    const d = new Date(s);
    if (Number.isNaN(d.getTime())) return String(s);
    const pad = (v) => String(v).padStart(2, "0");
    return `${d.getMonth() + 1}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function fmtRelative(s) {
    if (!s) return "-";
    const d = new Date(s).getTime();
    if (Number.isNaN(d)) return "-";
    const diff = Date.now() - d;
    if (diff < 60e3) return "刚刚";
    if (diff < 3600e3) return Math.floor(diff / 60e3) + " 分钟前";
    if (diff < 86400e3) return Math.floor(diff / 3600e3) + " 小时前";
    return Math.floor(diff / 86400e3) + " 天前";
}

function toast(message, type = "info", ms = 3200) {
    const node = el("div", { class: "toast " + type, text: message });
    $("toast-host").append(node);
    setTimeout(() => {
        node.style.transition = "opacity .2s, transform .2s";
        node.style.opacity = "0";
        node.style.transform = "translateY(-4px)";
        setTimeout(() => node.remove(), 220);
    }, ms);
}

/* ---------- 3. 请求层（带流水记录 + 401 自动刷新） ---------- */

class ApiError extends Error {
    constructor(message, code, status) {
        super(message);
        this.code = code;
        this.status = status;
    }
}

let refreshing = null;

function applyTokens(tokenVO) {
    state.accessToken = tokenVO.accessToken || "";
    state.refreshToken = tokenVO.refreshToken || "";
    if (tokenVO.user) state.user = tokenVO.user;
    localStorage.setItem(TOKEN_KEY, JSON.stringify({
        accessToken: state.accessToken,
        refreshToken: state.refreshToken,
        user: state.user,
    }));
}

function clearTokens() {
    state.accessToken = "";
    state.refreshToken = "";
    state.user = null;
    localStorage.removeItem(TOKEN_KEY);
}

async function doRefresh() {
    if (!state.refreshToken) return false;
    if (refreshing) return refreshing;
    refreshing = (async () => {
        try {
            const res = await fetch(API + "/auth/refresh", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ refreshToken: state.refreshToken }),
            });
            const json = await res.json().catch(() => null);
            if (!json || json.code !== 0) return false;
            applyTokens(json.data);
            return true;
        } catch {
            return false;
        } finally {
            refreshing = null;
        }
    })();
    return refreshing;
}

function pushLog(entry) {
    state.logs.unshift(entry);
    if (state.logs.length > 120) state.logs.pop();
    const badge = $("navLogs");
    if (badge) badge.textContent = state.logs.length;
    if (state.view === "logs") renderLogs();
}

/**
 * 统一请求。刻意不用 axios：这里要精确控制"什么时候记流水、什么时候重试"，
 * 一层 fetch 包装反而更好读。
 */
async function api(path, { method = "GET", body, form, query, silent = false, retryAuth = true } = {}) {
    let url = API + path;
    if (query) {
        const qs = new URLSearchParams();
        for (const [k, v] of Object.entries(query)) {
            if (v === undefined || v === null || v === "") continue;
            // 数组参数按重复 key 展开（tag=a&tag=b），后端用 List<String> 接收
            if (Array.isArray(v)) {
                for (const item of v) if (item !== "") qs.append(k, item);
                continue;
            }
            qs.append(k, v);
        }
        const s = qs.toString();
        if (s) url += "?" + s;
    }

    const headers = {};
    if (state.accessToken) headers.Authorization = "Bearer " + state.accessToken;
    let payload;
    if (form) {
        payload = form; // 浏览器自己带 boundary，千万不要手写 Content-Type
    } else if (body !== undefined) {
        headers["Content-Type"] = "application/json";
        payload = JSON.stringify(body);
    }

    const started = performance.now();
    let res, json;
    try {
        res = await fetch(url, { method, headers, body: payload });
        const text = await res.text();
        try {
            json = text ? JSON.parse(text) : null;
        } catch {
            json = { code: -1, message: text.slice(0, 400) };
        }
    } catch (e) {
        pushLog({
            id: ++state.logSeq, method, path, status: 0, ms: Math.round(performance.now() - started),
            code: "NET", request: body, response: { error: String(e && e.message) }, at: new Date(),
        });
        const err = new ApiError("网络请求失败，后端是否已启动？");
        if (!silent) toast(err.message, "error");
        throw err;
    }

    const entry = {
        id: ++state.logSeq,
        method,
        path,
        status: res.status,
        ms: Math.round(performance.now() - started),
        code: json && json.code,
        request: body !== undefined ? body : (form ? "FormData(multipart)" : undefined),
        response: json,
        at: new Date(),
    };
    pushLog(entry);

    // access token 过期：静默刷一次再重放。上传请求不重放（body 已被消费）。
    if (res.status === 401 && retryAuth && !form) {
        const ok = await doRefresh();
        if (ok) return api(path, { method, body, query, silent, retryAuth: false });
        handleSessionExpired();
        throw new ApiError("登录状态已失效", 40113, 401);
    }
    if (res.status === 401) {
        handleSessionExpired();
        throw new ApiError("登录状态已失效", 40113, 401);
    }

    if (!res.ok || (json && json.code !== 0)) {
        const message = (json && json.message) || `HTTP ${res.status}`;
        const err = new ApiError(message, json && json.code, res.status);
        if (!silent) toast(message, "error");
        throw err;
    }
    if (json && json.traceId) updateTrace(json.traceId);
    return json ? json.data : null;
}

function handleSessionExpired() {
    clearTokens();
    $("shell").classList.add("hidden");
    $("authLayer").classList.remove("hidden");
    $("authHealth").textContent = "登录已失效，请重新登录";
}

function updateTrace(traceId) {
    const tag = $("traceTag");
    if (tag) tag.textContent = "traceId " + String(traceId).slice(0, 12);
}

/* ---------- 4. 认证 ---------- */

let authMode = "login";

function bindAuth() {
    document.querySelectorAll("[data-auth-tab]").forEach((btn) => {
        btn.addEventListener("click", () => {
            authMode = btn.dataset.authTab;
            document.querySelectorAll("[data-auth-tab]").forEach((b) => b.classList.toggle("on", b === btn));
            $("nicknameField").classList.toggle("hidden", authMode !== "register");
            $("authSubmit").textContent = authMode === "login" ? "登录" : "注册并登录";
        });
    });

    $("authForm").addEventListener("submit", async (e) => {        e.preventDefault();
        const account = $("authAccount").value.trim();
        const password = $("authPassword").value;
        if (!account || !password) {
            toast("请填写账号与密码", "error");
            return;
        }
        const btn = $("authSubmit");
        btn.disabled = true;
        btn.innerHTML = '<span class="spin"></span> 处理中';
        try {
            const data = authMode === "login"
                ? await api("/auth/login", { method: "POST", body: { account, password } })
                : await api("/auth/register", {
                    method: "POST",
                    body: {
                        username: account,
                        password,
                        nickname: $("authNickname").value.trim() || undefined,
                    },
                });
            applyTokens(data);
            enterApp();
        } catch (err) {
            // 注册撞名时给出更直接的引导
            if (authMode === "register" && err.code === 40910) {
                toast("该用户名已被占用，换一个或直接登录", "error", 4200);
            }
        } finally {
            btn.disabled = false;
            btn.textContent = authMode === "login" ? "登录" : "注册并登录";
        }
    });

    const demoBtn = $("demoBtn");
    if (demoBtn) demoBtn.addEventListener("click", demoLogin);
}

/**
 * 演示账号快捷登录：先试登录，账号不存在就注册。
 * 页面上有个「使用演示账号进入」按钮，URL 加 ?demo=1 也会走这里（方便直接分享链接看效果）。
 */
async function demoLogin() {
    const btn = $("demoBtn");
    if (btn) {
        btn.disabled = true;
        btn.innerHTML = '<span class="spin"></span> 进入中';
    }
    try {
        let data;
        try {
            data = await api("/auth/login", {
                method: "POST",
                silent: true,
                retryAuth: false,
                body: { account: DEMO_ACCOUNT, password: DEMO_PASSWORD },
            });
        } catch {
            data = await api("/auth/register", {
                method: "POST",
                silent: true,
                retryAuth: false,
                body: { username: DEMO_ACCOUNT, password: DEMO_PASSWORD, nickname: "演示账号" },
            });
        }
        applyTokens(data);
        enterApp();
    } catch (err) {
        toast("演示账号进入失败：" + err.message, "error", 4200);
    } finally {
        if (btn) {
            btn.disabled = false;
            btn.textContent = "使用演示账号进入";
        }
    }
}

async function probeHealth() {
    const pill = $("healthPill");
    const label = $("authHealth");
    try {
        const h = await api("/health", { silent: true, retryAuth: false });
        state.health = h;
        const dbUp = h.db && h.db.status === "UP";
        const redisUp = h.redis && h.redis.status === "UP";
        const ok = dbUp && redisUp;
        if (pill) {
            pill.className = "health-pill " + (ok ? "up" : "warn");
            pill.querySelector("span").textContent = `DB ${dbUp ? "UP" : "DOWN"} · Redis ${redisUp ? "UP" : "DOWN"}`;
        }
        if (label) {
            label.textContent = ok
                ? `服务正常 · ${h.app} ${h.version} · DB ${h.db.latencyMs}ms / Redis ${h.redis.latencyMs}ms`
                : `依赖异常：DB ${dbUp ? "UP" : "DOWN"}，Redis ${redisUp ? "UP" : "DOWN"}`;
        }
        renderEnv();
        return h;
    } catch {
        if (pill) {
            pill.className = "health-pill down";
            pill.querySelector("span").textContent = "后端未连接";
        }
        if (label) label.textContent = "探测失败：后端 8081 未启动，或 MySQL/Redis 容器没起来";
        return null;
    }
}

/* ---------- 5. 视图切换 ---------- */

const VIEW_META = {
    overview: ["概览", "阶段 0-4 已实现的能力，数据全部来自后端真实接口"],
    projects: ["画布项目", "快照整份替换 + version 乐观锁 + 逻辑删除回收站"],
    assets: ["素材库", "流式上传、SHA-256 秒传、标签筛选、引用计数清理"],
    prompts: ["提示词库", "第三方 JSON 拉取、内容摘要幂等同步、Redis 锁与标签缓存"],
    logs: ["接口流水", "每个请求的方法、业务码、耗时与请求/响应体"],
};

function switchView(view) {
    state.view = view;
    document.querySelectorAll(".nav-item").forEach((b) => b.classList.toggle("on", b.dataset.view === view));
    document.querySelectorAll(".view").forEach((s) => s.classList.toggle("hidden", s.id !== "view-" + view));
    const [title, sub] = VIEW_META[view];
    $("viewTitle").textContent = title;
    $("viewSub").textContent = sub;
    // 页签写进 hash：刷新不丢当前页，也能直接把链接发给别人（保留 ?demo=1 之类的查询串）
    const target = location.pathname + location.search + "#" + view;
    if (location.hash !== "#" + view) {
        history.replaceState(null, "", target);
    }
    if (view === "logs") renderLogs();
    if (view === "overview") renderOverview();
    // 提示词库第一次切过去时按需拉一次，之后走内存状态
    if (view === "prompts" && !state.prompts.sources.length) {
        refreshPrompts().catch((e) => toast("提示词加载失败：" + e.message, "error"));
    }
}

function bindNav() {
    document.querySelectorAll(".nav-item").forEach((btn) => {
        btn.addEventListener("click", () => switchView(btn.dataset.view));
    });
    document.querySelectorAll("[data-goto]").forEach((btn) => {
        btn.addEventListener("click", () => switchView(btn.dataset.goto));
    });
}

/* ---------- 6. 数据加载 ---------- */

async function loadProjects() {
    if (state.trash) {
        const list = await api("/projects/trash", { silent: true });
        state.projects = { list: list || [], total: (list || []).length };
    } else {
        const page = await api("/projects", {
            query: { keyword: state.keyword, page: 1, size: 100 },
            silent: true,
        });
        state.projects = { list: page.list || [], total: page.total || 0 };
    }
    $("navProjects").textContent = state.projects.total;
}

async function loadAssets() {
    const page = await api("/assets", {
        query: { kind: state.kind === "all" ? "" : state.kind, keyword: state.keyword, tag: state.tag, page: 1, size: 100 },
        silent: true,
    });
    state.assets = { list: page.list || [], total: page.total || 0 };
    $("navAssets").textContent = state.assets.total;
    try {
        state.tags = await api("/assets/tags", { silent: true });
    } catch {
        state.tags = [];
    }
}

async function loadAll() {
    const jobs = [loadProjects(), loadAssets(), loadPrompts()];
    const results = await Promise.allSettled(jobs);
    for (const r of results) {
        if (r.status === "rejected") {
            toast("数据加载失败：" + r.reason.message, "error");
        }
    }
    renderOverview();
    renderProjects();
    renderAssets();
    renderPrompts();
}

/* ---------- 7. 概览 ---------- */

function renderOverview() {
    const projects = state.projects.list || [];
    const assets = state.assets.list || [];
    $("statProjects").textContent = state.projects.total;
    $("statAssets").textContent = state.assets.total;
    $("statNodes").textContent = projects.reduce((s, p) => s + (p.nodeCount || 0), 0);
    $("statBytes").textContent = fmtBytes(assets.reduce((s, a) => s + ((a.data && a.data.bytes) || 0), 0));

    // 最近画布
    const rp = $("recentProjects");
    rp.innerHTML = "";
    if (!projects.length) {
        rp.append(el("div", { class: "empty", text: "还没有画布，去「画布项目」新建一张，或直接生成演示数据" }));
    } else {
        for (const p of projects.slice(0, 5)) {
            rp.append(el("div", {
                style: "display:flex;align-items:center;justify-content:space-between;gap:12px;padding:8px 0;border-bottom:1px solid var(--line)",
            }, [
                el("div", { style: "min-width:0" }, [
                    el("div", { text: p.title, style: "font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" }),
                    el("div", {
                        text: `${p.nodeCount || 0} 节点 · ${p.connectionCount || 0} 连线 · ${fmtRelative(p.updatedAt)}`,
                        style: "font-size:11px;color:var(--text-faint);font-family:var(--mono)",
                    }),
                ]),
                el("button", {
                    class: "btn ghost sm",
                    text: "打开",
                    type: "button",
                    onclick: () => openProjectDetail(p.id),
                }),
            ]));
        }
    }

    // 最近素材
    const ra = $("recentAssets");
    ra.innerHTML = "";
    if (!assets.length) {
        ra.append(el("div", { class: "empty", text: "素材库为空，去「素材库」上传几张图" }));
    } else {
        // 注意：这个容器必须 append 进 ra，否则缩略图会变成块级元素铺满整卡（实测踩过）
        const wrap = el("div", {
            style: "display:grid;grid-template-columns:repeat(auto-fill,minmax(72px,1fr));gap:8px",
        });
        for (const a of assets.slice(0, 10)) {
            const src = assetImage(a);
            const box = el("div", {
                style: "aspect-ratio:1;border-radius:9px;overflow:hidden;background:var(--bg-soft);border:1px solid var(--line);display:grid;place-items:center",
                title: a.title,
            }, src
                ? [el("img", { src, alt: a.title, loading: "lazy", style: "width:100%;height:100%;object-fit:cover;display:block" })]
                : [el("span", { text: kindLabel(a.kind), style: "font-size:10px;color:var(--text-faint);font-family:var(--mono)" })]);
            wrap.append(box);
        }
        ra.append(wrap);
    }

    renderEnv();
}

function renderEnv() {
    const box = $("envInfo");
    if (!box) return;
    const h = state.health;
    const rows = [
        ["context-path", CTX || "/"],
        ["接口前缀", API],
        ["服务", h ? `${h.app} ${h.version}` : "-"],
        ["数据库", h ? `${h.db.status} · ${h.db.latencyMs}ms` : "-"],
        ["Redis", h ? `${h.redis.status} · ${h.redis.latencyMs}ms` : "-"],
        ["检查时间", h ? fmtTime(h.checkedAt) : "-"],
        ["当前用户", state.user ? `${state.user.username}` : "-"],
        ["accessToken", state.accessToken ? state.accessToken.slice(0, 18) + "…" : "-"],
    ];
    box.innerHTML = "";
    for (const [k, v] of rows) {
        box.append(el("div", {}, [el("dt", { text: k }), el("dd", { text: String(v) })]));
    }
}

/* ---------- 8. 画布项目 ---------- */

function renderProjects() {
    const grid = $("projectGrid");
    grid.innerHTML = "";
    const list = state.projects.list || [];
    if (!list.length) {
        grid.append(el("div", {
            class: "empty",
            text: state.trash ? "回收站是空的" : "还没有画布。点「新建画布」，或「生成演示数据」一次灌入 3 张带节点的画布",
        }));
        return;
    }
    for (const p of list) {
        const card = el("div", { class: "pcard" }, [
            el("h4", { text: p.title }),
            // 注意：列表接口（ProjectSummaryVO）不返回 version —— version 只在详情里，
            // 它表示"画布内容的修订号"，列表页展示它没有意义
            el("div", { class: "meta", text: `#${p.id} · ${fmtRelative(p.updatedAt)}` }),
            el("div", { class: "counts" }, [
                el("span", {}, [el("b", { text: String(p.nodeCount || 0) }), " 节点"]),
                el("span", {}, [el("b", { text: String(p.connectionCount || 0) }), " 连线"]),
                el("span", { text: p.backgroundMode || "dots" }),
            ]),
            el("div", { class: "ops" }, state.trash
                ? [
                    el("button", { class: "btn sm", text: "恢复", type: "button", onclick: () => restoreProject(p.id) }),
                ]
                : [
                    el("button", { class: "btn sm", text: "查看", type: "button", onclick: () => openProjectDetail(p.id) }),
                    el("button", { class: "btn ghost sm", text: "重命名", type: "button", onclick: () => renameProject(p) }),
                    el("button", { class: "btn ghost sm", text: "复制", type: "button", onclick: () => copyProject(p.id) }),
                    el("button", { class: "btn ghost sm", text: "导出", type: "button", onclick: () => exportProject(p.id) }),
                    el("button", { class: "btn danger sm", text: "删除", type: "button", onclick: () => deleteProject(p.id) }),
                ]),
        ]);
        grid.append(card);
    }
}

async function createProject() {
    const title = await promptModal({
        title: "新建画布",
        desc: "创建后可立即保存一份快照，写入节点与连线。",
        label: "画布标题",
        value: "未命名画布 " + new Date().toLocaleDateString("zh-CN"),
        confirmText: "创建",
    });
    if (title == null) return;
    const clean = String(title).trim();
    if (!clean) {
        toast("标题不能为空", "error");
        return;
    }
    try {
        const p = await api("/projects", { method: "POST", body: { title: clean } });
        toast("已创建：" + p.title, "ok");
        await refreshProjects();
    } catch { /* 已 toast */ }
}

async function renameProject(p) {
    const title = await promptModal({
        title: "重命名画布",
        desc: "重命名不递增 version —— version 只管画布内容。",
        label: "新标题",
        value: p.title,
        confirmText: "保存",
    });
    if (title == null) return;
    try {
        await api("/projects/" + p.id, { method: "PATCH", body: { title: String(title).trim() } });
        toast("已重命名", "ok");
        await refreshProjects();
    } catch { /* ignore */ }
}

async function copyProject(id) {
    try {
        const p = await api(`/projects/${id}/copy`, { method: "POST", body: {} });
        toast("已复制：" + p.title, "ok");
        await refreshProjects();
    } catch { /* ignore */ }
}

async function deleteProject(id) {
    const ok = await confirmModal({
        title: "删除画布",
        desc: "逻辑删除，节点与连线会保留，可以从回收站恢复。",
        confirmText: "移到回收站",
        danger: true,
    });
    if (!ok) return;
    try {
        await api("/projects/" + id, { method: "DELETE" });
        toast("已移入回收站", "ok");
        await refreshProjects();
        await loadAssets();
    } catch { /* ignore */ }
}

async function restoreProject(id) {
    try {
        await api(`/projects/${id}/restore`, { method: "POST" });
        toast("已恢复", "ok");
        await refreshProjects();
    } catch { /* ignore */ }
}

async function exportProject(id) {
    try {
        const data = await api(`/projects/${id}/export`);
        const blob = new Blob([JSON.stringify(data, null, 2)], { type: "application/json" });
        const url = URL.createObjectURL(blob);
        const a = el("a", { href: url, download: (data.title || "canvas") + ".json" });
        document.body.append(a);
        a.click();
        a.remove();
        setTimeout(() => URL.revokeObjectURL(url), 2000);
        toast("已导出 JSON", "ok");
    } catch { /* ignore */ }
}

async function openProjectDetail(id) {
    const modal = openModal();
    modal.panel.innerHTML = "";
    modal.panel.append(
        el("h3", { text: "读取画布快照…" }),
        el("p", { class: "desc", text: "GET /v1/projects/" + id }),
    );
    try {
        const d = await api("/projects/" + id, { silent: true });
        modal.panel.innerHTML = "";
        const nodes = d.nodes || [];
        const conns = d.connections || [];

        // 用 canvas 画一个只读缩略图：把节点按坐标等比铺开
        const cv = el("canvas", { width: 760, height: 300, style: "width:100%;border-radius:10px;background:#0b0e15;border:1px solid var(--line)" });
        modal.panel.append(
            el("h3", { text: d.title }),
            el("p", { class: "desc", text: `#${d.id} · version ${d.version} · ${nodes.length} 节点 · ${conns.length} 连线` }),
            cv,
            el("div", { style: "display:flex;gap:8px;margin-top:12px;flex-wrap:wrap" }, [
                el("span", { class: "chip", text: "背景 " + (d.backgroundMode || "dots") }),
                el("span", { class: "chip", text: `视口 k=${d.viewport ? d.viewport.k : "-"}` }),
                el("span", { class: "chip", text: "更新 " + fmtTime(d.updatedAt) }),
            ]),
        );
        drawSnapshot(cv, nodes, conns);
    } catch (err) {
        modal.panel.append(el("p", { class: "desc", text: "读取失败：" + err.message }));
    }
}

function drawSnapshot(cv, nodes, conns) {
    const ctx = cv.getContext("2d");
    const W = cv.width, H = cv.height;
    ctx.clearRect(0, 0, W, H);

    if (!nodes.length) {
        ctx.fillStyle = "#5a6275";
        ctx.font = "13px monospace";
        ctx.fillText("这张画布还没有节点", 24, 40);
        return;
    }

    // 坐标在 position 里（CanvasNodeVO.position.{x,y}），不是平铺的 n.x —— 这里踩过一次，
    // 直接读 n.x 会得到 undefined，整个画布算成 NaN，什么都不显示
    const nx = (n) => (n.position && typeof n.position.x === "number" ? n.position.x : 0);
    const ny = (n) => (n.position && typeof n.position.y === "number" ? n.position.y : 0);
    const nw = (n) => (typeof n.width === "number" ? n.width : 200);
    const nh = (n) => (typeof n.height === "number" ? n.height : 200);

    // 求包围盒，等比缩放到画布里（留 28px 边距）
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    for (const n of nodes) {
        minX = Math.min(minX, nx(n));
        minY = Math.min(minY, ny(n));
        maxX = Math.max(maxX, nx(n) + nw(n));
        maxY = Math.max(maxY, ny(n) + nh(n));
    }
    const pad = 28;
    const scale = Math.min((W - pad * 2) / Math.max(maxX - minX, 1), (H - pad * 2) / Math.max(maxY - minY, 1), 1);
    const ox = pad - minX * scale + (W - pad * 2 - (maxX - minX) * scale) / 2;
    const oy = pad - minY * scale + (H - pad * 2 - (maxY - minY) * scale) / 2;
    const px = (x) => ox + x * scale;
    const py = (y) => oy + y * scale;

    const index = new Map();
    for (const n of nodes) index.set(n.id, n);

    // 连线
    ctx.strokeStyle = "rgba(109,94,252,.55)";
    ctx.lineWidth = 1.5;
    for (const c of conns) {
        const a = index.get(c.fromNodeId);
        const b = index.get(c.toNodeId);
        if (!a || !b) continue;
        const x1 = px(nx(a) + nw(a)), y1 = py(ny(a) + nh(a) / 2);
        const x2 = px(nx(b)), y2 = py(ny(b) + nh(b) / 2);
        const mx = (x1 + x2) / 2;
        ctx.beginPath();
        ctx.moveTo(x1, y1);
        ctx.bezierCurveTo(mx, y1, mx, y2, x2, y2);
        ctx.stroke();
    }

    // 节点
    for (const n of nodes) {
        const x = px(nx(n)), y = py(ny(n));
        const w = nw(n) * scale, h = nh(n) * scale;
        const isImage = String(n.type || "").indexOf("image") >= 0;
        ctx.fillStyle = isImage ? "rgba(34,211,238,.13)" : "rgba(109,94,252,.16)";
        ctx.strokeStyle = isImage ? "rgba(34,211,238,.6)" : "rgba(139,125,255,.65)";
        ctx.lineWidth = 1;
        roundRect(ctx, x, y, w, h, 6);
        ctx.fill();
        ctx.stroke();
        ctx.fillStyle = "rgba(232,236,246,.86)";
        ctx.font = "10px monospace";
        const label = n.title || n.type || n.id;
        ctx.fillText(label.length > 22 ? label.slice(0, 21) + "…" : label, x + 7, y + 16);
    }
}

function roundRect(ctx, x, y, w, h, r) {
    const rr = Math.min(r, w / 2, h / 2);
    ctx.beginPath();
    ctx.moveTo(x + rr, y);
    ctx.arcTo(x + w, y, x + w, y + h, rr);
    ctx.arcTo(x + w, y + h, x, y + h, rr);
    ctx.arcTo(x, y + h, x, y, rr);
    ctx.arcTo(x, y, x + w, y, rr);
    ctx.closePath();
}

async function refreshProjects() {
    await loadProjects();
    renderProjects();
    renderOverview();
}

/* ---------- 9. 素材库 ---------- */

function kindLabel(kind) {
    return { image: "图片", video: "视频", audio: "音频", text: "文本" }[kind] || kind || "未知";
}

function assetImage(a) {
    if (a.kind !== "image") return "";
    const raw = (a.data && (a.data.dataUrl || a.data.url)) || a.coverUrl || "";
    return normalizeMediaUrl(raw);
}

// 后端返回的 url 带 /api 前缀；这里按实际 context-path 重写，换端口/前缀都不会挂
function normalizeMediaUrl(url) {
    if (!url) return "";
    if (url.startsWith("data:") || url.startsWith("http")) return url;
    return url.replace(/^\/api(?=\/)/, CTX);
}

function renderAssets() {
    // 标签 chips
    const tagBox = $("tagChips");
    tagBox.innerHTML = "";
    if (state.tags.length) {
        const all = el("button", {
            class: "chip" + (state.tag ? "" : " on"),
            text: "全部标签",
            type: "button",
            onclick: () => { state.tag = ""; reloadAssets(); },
        });
        tagBox.append(all);
        for (const t of state.tags.slice(0, 16)) {
            tagBox.append(el("button", {
                class: "chip" + (state.tag === t.tag ? " on" : ""),
                type: "button",
                onclick: () => {
                    state.tag = state.tag === t.tag ? "" : t.tag;
                    reloadAssets();
                },
            }, [t.tag, el("span", { class: "n", text: String(t.count) })]));
        }
    }

    const grid = $("assetGrid");
    grid.innerHTML = "";
    const list = state.assets.list || [];
    if (!list.length) {
        grid.append(el("div", {
            class: "empty",
            text: state.keyword || state.tag || state.kind !== "all"
                ? "当前筛选条件下没有素材"
                : "素材库还是空的，上传几张图片，或点「生成演示数据」",
        }));
        return;
    }

    for (const a of list) {
        const thumb = el("div", { class: "thumb" });
        const src = assetImage(a);
        if (src) {
            thumb.append(el("img", { src, alt: a.title, loading: "lazy" }));
        } else if (a.kind === "text") {
            thumb.append(el("div", { class: "text-thumb", text: (a.data && a.data.content) || a.note || "（空文本）" }));
        } else {
            thumb.append(el("span", { class: "ph", text: kindLabel(a.kind).toUpperCase() }));
        }
        thumb.append(el("span", { class: "kind", text: kindLabel(a.kind) }));

        const card = el("div", { class: "acard" }, [
            thumb,
            el("div", { class: "body" }, [
                el("h5", { text: a.title, title: a.title }),
                el("div", {
                    class: "sub",
                    text: a.kind === "text"
                        ? ((a.data && a.data.content) || "").length + " 字"
                        : `${a.data && a.data.width ? a.data.width + "×" + a.data.height + " · " : ""}${fmtBytes(a.data && a.data.bytes)}`,
                }),
                el("div", { class: "tags" }, (a.tags || []).slice(0, 3).map((t) => el("span", { text: t }))),
            ]),
            el("button", {
                class: "del",
                type: "button",
                title: "删除素材",
                text: "×",
                onclick: (e) => { e.stopPropagation(); deleteAsset(a); },
            }),
        ]);
        card.addEventListener("click", () => openAssetDetail(a));
        grid.append(card);
    }
}

async function reloadAssets() {
    try {
        await loadAssets();
        renderAssets();
        renderOverview();
    } catch { /* ignore */ }
}

async function deleteAsset(a) {
    const ok = await confirmModal({
        title: "删除素材",
        desc: `「${a.title}」将被逻辑删除。磁盘文件不在这里删，交给孤儿清理。`,
        confirmText: "删除",
        danger: true,
    });
    if (!ok) return;
    try {
        await api("/assets/" + a.id, { method: "DELETE" });
        toast("已删除素材", "ok");
        await reloadAssets();
    } catch { /* ignore */ }
}

function openAssetDetail(a) {
    const modal = openModal();
    const src = assetImage(a);
    modal.panel.innerHTML = "";
    modal.panel.append(
        el("h3", { text: a.title }),
        el("p", { class: "desc", text: `#${a.id} · ${kindLabel(a.kind)} · ${fmtTime(a.createdAt)}` }),
    );
    if (src) {
        modal.panel.append(el("div", {
            style: "border-radius:10px;overflow:hidden;border:1px solid var(--line);max-height:300px;background:var(--bg-soft)",
        }, [el("img", { src, alt: a.title, style: "width:100%;display:block" })]));
    } else if (a.kind === "text") {
        modal.panel.append(el("pre", {
            style: "max-height:260px;overflow:auto;padding:12px;background:var(--bg-soft);border:1px solid var(--line);border-radius:9px;font-size:12px;white-space:pre-wrap",
            text: (a.data && a.data.content) || "",
        }));
    }
    modal.panel.append(el("pre", {
        style: "max-height:200px;overflow:auto;padding:10px;background:#070910;border:1px solid var(--line);border-radius:8px;font-family:var(--mono);font-size:11px;color:#a9b2c7;white-space:pre-wrap",
        text: JSON.stringify(a, null, 2),
    }));

    const input = el("input", {
        class: "input",
        style: "max-width:none;width:100%;margin-top:12px",
        value: (a.tags || []).join(","),
        placeholder: "标签，逗号分隔",
    });
    modal.panel.append(
        el("p", { class: "desc", style: "margin:10px 0 0", text: "编辑标签（逗号分隔）" }),
        input,
        el("div", { class: "row" }, [
            el("button", { class: "btn ghost", text: "关闭", type: "button", onclick: modal.close }),
            el("button", {
                class: "btn primary", text: "保存标签", type: "button",
                onclick: async () => {
                    const tags = input.value.split(/[,，]/).map((s) => s.trim()).filter(Boolean);
                    try {
                        await api("/assets/" + a.id, { method: "PATCH", body: { tags } });
                        toast("标签已更新", "ok");
                        modal.close();
                        await reloadAssets();
                    } catch { /* ignore */ }
                },
            }),
        ]),
    );
}

async function uploadFiles(files) {
    const list = Array.from(files || []);
    if (!list.length) return;
    let done = 0;
    for (const f of list) {
        try {
            const fd = new FormData();
            fd.append("file", f);
            const file = await api("/files/upload?bizType=asset", { method: "POST", form: fd, retryAuth: false, silent: true });
            await api("/assets", {
                method: "POST",
                silent: true,
                body: {
                    kind: kindOfMime(file.mimeType),
                    title: f.name.replace(/\.[^.]+$/, "").slice(0, 200),
                    fileId: file.id,
                    tags: ["上传"],
                    source: "控制台",
                },
            });
            done++;
            toast(`${f.name} 已入库${file.deduped ? "（秒传命中）" : ""}`, "ok");
        } catch (err) {
            toast(`${f.name} 失败：${err.message}`, "error", 4200);
        }
    }
    if (done) await reloadAssets();
}

function kindOfMime(mime) {
    if (!mime) return "image";
    if (mime.startsWith("image/")) return "image";
    if (mime.startsWith("video/")) return "video";
    if (mime.startsWith("audio/")) return "audio";
    return "image";
}

function bindAssets() {
    const input = $("fileInput");
    const zone = $("dropzone");

    $("pickBtn").addEventListener("click", () => input.click());
    zone.addEventListener("click", (e) => {
        if (e.target.closest("button")) return;
        input.click();
    });
    input.addEventListener("change", () => {
        uploadFiles(input.files);
        input.value = "";
    });

    ["dragenter", "dragover"].forEach((ev) =>
        zone.addEventListener(ev, (e) => {
            e.preventDefault();
            zone.classList.add("drag");
        }));
    ["dragleave", "drop"].forEach((ev) =>
        zone.addEventListener(ev, (e) => {
            e.preventDefault();
            zone.classList.remove("drag");
        }));
    zone.addEventListener("drop", (e) => {
        if (e.dataTransfer && e.dataTransfer.files) uploadFiles(e.dataTransfer.files);
    });

    document.querySelectorAll("#kindChips .chip").forEach((chip) => {
        chip.addEventListener("click", () => {
            state.kind = chip.dataset.kind;
            document.querySelectorAll("#kindChips .chip").forEach((c) => c.classList.toggle("on", c === chip));
            reloadAssets();
        });
    });

    let timer = null;
    $("assetSearch").addEventListener("input", (e) => {
        clearTimeout(timer);
        timer = setTimeout(() => {
            state.keyword = e.target.value.trim();
            reloadAssets();
        }, 320);
    });

    $("newTextBtn").addEventListener("click", createTextAsset);
}

async function createTextAsset() {
    const modal = openModal();
    modal.panel.innerHTML = "";
    const title = el("input", { class: "input", style: "max-width:none;width:100%", value: "提示词笔记" });
    const content = el("textarea", { placeholder: "粘贴一段提示词或灵感文本…" });
    modal.panel.append(
        el("h3", { text: "新建文本素材" }),
        el("p", { class: "desc", text: "文本素材不落磁盘文件，内容直接进 t_asset.content。" }),
        el("span", { style: "font-size:12px;color:var(--text-dim)", text: "标题" }),
        title,
        el("span", { style: "display:block;margin:12px 0 6px;font-size:12px;color:var(--text-dim)", text: "正文" }),
        content,
        el("div", { class: "row" }, [
            el("button", { class: "btn ghost", text: "取消", type: "button", onclick: modal.close }),
            el("button", {
                class: "btn primary", text: "创建", type: "button",
                onclick: async () => {
                    if (!title.value.trim()) return toast("标题不能为空", "error");
                    try {
                        await api("/assets", {
                            method: "POST",
                            body: { kind: "text", title: title.value.trim(), content: content.value, tags: ["手写"], source: "控制台" },
                        });
                        modal.close();
                        toast("文本素材已创建", "ok");
                        await reloadAssets();
                    } catch { /* ignore */ }
                },
            }),
        ]),
    );
}

/* ---------- 10. 提示词库 ---------- */

// 本地样例源：由本服务自己托管，断网也能演示"新增源 → 同步 → 搜索"整条链路
function samplePromptUrl() {
    return location.origin + CTX + "/demo/prompts-sample.json";
}

async function loadPrompts() {
    const p = state.prompts;
    // 源列表和提示词可以并行拉：两者互不依赖
    const [sources, page] = await Promise.all([
        api("/prompt-sources", { silent: true }),
        api("/prompts", {
            query: {
                keyword: p.keyword,
                category: p.category === "all" ? "" : p.category,
                tag: p.tag,
                page: 1,
                pageSize: 60,
            },
            silent: true,
        }),
    ]);
    p.sources = sources || [];
    p.items = (page && page.items) || [];
    p.total = (page && page.total) || 0;
    p.tags = (page && page.tags) || [];
    p.categories = (page && page.categories) || [];
    $("navPrompts").textContent = p.total;
}

async function refreshPrompts() {
    await loadPrompts();
    renderPrompts();
}

function renderPrompts() {
    $("promptCount").textContent = state.prompts.total + " 条";
    renderPromptSources();
    renderPromptChips();
    renderPromptGrid();
}

function renderPromptSources() {
    const box = $("promptSources");
    box.innerHTML = "";
    const sources = state.prompts.sources;
    if (!sources.length) {
        box.append(el("div", { class: "empty", text: "还没有源。点上方「建本地样例源」可直接体验。" }));
        return;
    }
    for (const source of sources) {
        const head = el("h5", {}, [el("span", { text: source.name })]);
        if (source.builtIn) head.append(el("span", { class: "tag-mini", text: "内置" }));

        const meta = el("div", {
            class: "src-meta",
            text: `${source.count} 条 · ${source.lastSuccessAt ? fmtRelative(source.lastSuccessAt) + "同步" : "未同步"}`,
        });

        const card = el("div", { class: "src-card" + (source.enabled ? "" : " off") }, [head, meta]);
        if (source.lastError) card.append(el("div", { class: "src-err", text: source.lastError }));

        const ops = el("div", { class: "src-ops" });
        ops.append(el("button", { class: "btn ghost", type: "button", text: "同步", onclick: () => syncSource(source) }));
        ops.append(el("button", {
            class: "btn ghost", type: "button", text: source.enabled ? "停用" : "启用",
            onclick: () => toggleSource(source),
        }));
        if (!source.builtIn) {
            ops.append(el("button", { class: "btn ghost", type: "button", text: "删除", onclick: () => removeSource(source) }));
        }
        card.append(ops);
        box.append(card);
    }
}

function renderPromptChips() {
    const p = state.prompts;

    const cats = $("promptCats");
    cats.innerHTML = "";
    for (const category of ["all", ...p.categories]) {
        cats.append(el("button", {
            class: "chip" + (p.category === category ? " on" : ""),
            type: "button",
            text: category === "all" ? "全部" : category,
            onclick: async () => {
                p.category = category;
                await refreshPrompts();
            },
        }));
    }

    const tags = $("promptTags");
    tags.innerHTML = "";
    for (const tag of p.tags) {
        tags.append(el("button", {
            class: "chip" + (p.tag === tag ? " on" : ""),
            type: "button",
            text: tag,
            onclick: async () => {
                // 单选切换：再点一次取消。后端支持多选（tag=a&tag=b），控制台先做单选用来看效果
                p.tag = p.tag === tag ? "" : tag;
                await refreshPrompts();
            },
        }));
    }
}

function renderPromptGrid() {
    const box = $("promptGrid");
    box.innerHTML = "";
    const items = state.prompts.items;
    if (!items.length) {
        box.append(el("div", { class: "empty", text: "没有匹配的提示词。先同步一个源，或换个关键字。" }));
        return;
    }
    for (const item of items) {
        const cover = item.coverUrl
            ? el("img", { class: "cover", src: normalizeMediaUrl(item.coverUrl), alt: item.title, loading: "lazy" })
            : el("div", { class: "cover ph", text: item.category });

        const tags = el("div", { class: "tags" }, (item.tags || []).slice(0, 4).map((tag) => el("span", { text: tag })));

        const body = el("div", { class: "body" }, [
            el("h5", { text: item.title }),
            el("p", { class: "desc", text: item.description || item.prompt }),
            tags,
            el("div", { class: "foot" }, [
                el("span", { text: item.updatedAt ? fmtRelative(item.updatedAt) : item.sourceId }),
                el("button", { class: "btn ghost", type: "button", text: "复制", onclick: () => copyPrompt(item) }),
            ]),
        ]);
        box.append(el("div", { class: "prompt-card" }, [cover, body]));
    }
}

async function syncSource(source) {
    toast("正在同步「" + source.name + "」…");
    try {
        const result = await api(`/prompt-sources/${encodeURIComponent(source.id)}/refresh`, { method: "POST" });
        if (result.success) {
            toast(result.skipped ? `内容未变化，跳过写库（${result.count} 条）` : `同步完成，${result.count} 条`, "ok");
        } else {
            toast("同步失败：" + result.lastError, "error");
        }
        await refreshPrompts();
    } catch (e) {
        toast("同步失败：" + e.message, "error");
    }
}

async function syncAllSources() {
    const button = $("syncAllPrompts");
    button.disabled = true;
    button.textContent = "同步中…";
    try {
        const summary = await api("/prompt-sources/refresh?force=true", { method: "POST" });
        const okText = `成功 ${summary.successCount}，失败 ${summary.failureCount}，跳过 ${summary.skippedCount}，共 ${summary.total} 条`;
        toast(okText, summary.failureCount ? "info" : "ok", 5000);
        await refreshPrompts();
    } catch (e) {
        toast("同步失败：" + e.message, "error");
    } finally {
        button.disabled = false;
        button.textContent = "同步全部源";
    }
}

async function toggleSource(source) {
    try {
        await api(`/prompt-sources/${encodeURIComponent(source.id)}`, { method: "PATCH", body: { enabled: !source.enabled } });
        await refreshPrompts();
    } catch (e) {
        toast("操作失败：" + e.message, "error");
    }
}

async function removeSource(source) {
    const ok = await confirmModal({
        title: "删除源",
        desc: `「${source.name}」及它下面的 ${source.count} 条提示词会被一起删除，确定吗？`,
        confirmText: "删除",
        danger: true,
    });
    if (!ok) return;
    try {
        await api(`/prompt-sources/${encodeURIComponent(source.id)}`, { method: "DELETE" });
        toast("已删除", "ok");
        await refreshPrompts();
    } catch (e) {
        toast("删除失败：" + e.message, "error");
    }
}

/** 新增源：三个字段放在一个弹窗里，比连开三个 promptModal 好用。 */
function createSource() {
    const modal = openModal();
    const name = el("input", { class: "input", style: "width:100%;max-width:none", placeholder: "例如：我的提示词" });
    const url = el("input", { class: "input", style: "width:100%;max-width:none", placeholder: "https://example.com/prompts.json" });
    const homepage = el("input", { class: "input", style: "width:100%;max-width:none", placeholder: "可空，用于「原始链接」兜底" });
    const label = (text, node) => el("div", { style: "margin-bottom:12px" }, [
        el("span", { style: "display:block;margin-bottom:6px;font-size:12px;color:var(--text-dim)", text }),
        node,
    ]);

    modal.panel.append(
        el("h3", { text: "新增提示词源" }),
        el("p", { class: "desc", text: "地址必须返回 JSON 数组，每项至少要有 title 和 prompt" }),
        label("名称", name),
        label("JSON 地址", url),
        label("主页", homepage),
        el("div", { class: "row" }, [
            el("button", { class: "btn ghost", type: "button", text: "取消", onclick: () => modal.close() }),
            el("button", {
                class: "btn primary", type: "button", text: "保存并同步",
                onclick: async () => {
                    try {
                        const created = await api("/prompt-sources", {
                            method: "POST",
                            body: { name: name.value.trim(), url: url.value.trim(), homepage: homepage.value.trim() || null },
                        });
                        modal.close();
                        await syncSource(created);
                    } catch (e) {
                        toast("新增失败：" + e.message, "error");
                    }
                },
            }),
        ]),
    );
    setTimeout(() => name.focus(), 30);
}

/** 一键建样本源：已存在就直接同步，不重复创建。 */
async function createSampleSource() {
    const exists = state.prompts.sources.find((s) => s.url === samplePromptUrl());
    if (exists) {
        await syncSource(exists);
        return;
    }
    try {
        const created = await api("/prompt-sources", {
            method: "POST",
            body: { name: "本地样例库", url: samplePromptUrl(), homepage: location.origin, refreshIntervalMinutes: 0 },
        });
        await syncSource(created);
    } catch (e) {
        toast("创建样例源失败：" + e.message, "error");
    }
}

function copyPrompt(item) {
    navigator.clipboard.writeText(item.prompt).then(
        () => toast("提示词已复制", "ok"),
        () => toast("复制失败，请手动选中", "error"),
    );
}

/* ---------- 11. 演示数据 ---------- */

async function seedDemo() {
    const btn = $("seedBtn");
    if (btn.disabled) return;
    if (state.projects.total > 0) {
        const ok = await confirmModal({
            title: "再生成一份演示数据",
            desc: "已有画布不会被删除，新数据会追加。继续吗？",
            confirmText: "继续",
        });
        if (!ok) return;
    }

    btn.disabled = true;
    btn.innerHTML = '<span class="spin"></span> 生成中';

    const arts = [
        { title: "冷色霓虹街景", c1: "#2b1b6b", c2: "#0ea5b7", tags: ["场景", "冷色"] },
        { title: "暖光人像参考", c1: "#7c2d12", c2: "#f59e0b", tags: ["人像", "暖色"] },
        { title: "深绿植物特写", c1: "#052e2b", c2: "#34d399", tags: ["植物", "微距"] },
        { title: "紫粉渐变底纹", c1: "#4c1d95", c2: "#ec4899", tags: ["底纹", "渐变"] },
    ];

    try {
        // 1) 画布：3 张，每张 4 节点 2 连线
        const projectTitles = ["品牌视觉探索", "角色设定集", "海报改版 v2"];
        for (const [idx, title] of projectTitles.entries()) {
            const p = await api("/projects", { method: "POST", body: { title }, silent: true });
            const nodes = [
                { id: "n_brief_" + idx, type: "text", title: "需求简述", position: { x: 80, y: 80 }, width: 260, height: 170, metadata: { text: "冷色科技感，强调材质" } },
                { id: "n_ref_" + idx, type: "image", title: "参考图 A", position: { x: 400, y: 80 }, width: 320, height: 320, metadata: {} },
                { id: "n_gen_" + idx, type: "image", title: "生成结果 1", position: { x: 780, y: 80 }, width: 320, height: 320, metadata: { model: "demo" } },
                { id: "n_note_" + idx, type: "text", title: "待改点", position: { x: 80, y: 320 }, width: 260, height: 170, metadata: { text: "高光再压一点" } },
            ];
            const connections = [
                { id: "e_a_" + idx, fromNodeId: "n_brief_" + idx, toNodeId: "n_gen_" + idx },
                { id: "e_b_" + idx, fromNodeId: "n_ref_" + idx, toNodeId: "n_gen_" + idx },
            ];
            await api(`/projects/${p.id}/snapshot`, {
                method: "PUT",
                silent: true,
                body: { version: 0, viewport: { x: 0, y: 0, k: 1 }, nodes, connections },
            });
        }

        // 2) 图片素材：canvas 画图 → base64 上传 → 建素材
        for (const spec of arts) {
            const dataUrl = makeArtwork(spec);
            const file = await api("/files/base64", {
                method: "POST",
                silent: true,
                body: { dataUrl, filename: spec.title + ".png", bizType: "asset" },
            });
            await api("/assets", {
                method: "POST",
                silent: true,
                body: {
                    kind: "image",
                    title: spec.title,
                    fileId: file.id,
                    tags: spec.tags,
                    source: "演示数据",
                    note: "控制台生成：" + file.width + "×" + file.height + " / " + fmtBytes(file.bytes),
                },
            });
        }

        // 3) 文本素材
        const texts = [
            { title: "赛博朋克提示词", content: "a cyberpunk street at night, neon signs reflecting on wet asphalt, cinematic lighting, 35mm, ultra detailed", tags: ["提示词", "风格"] },
            { title: "改图要求", content: "整体降饱和 10%，高光不过曝，肤色保持自然，背景虚化再柔一点。", tags: ["修改意见"] },
        ];
        for (const t of texts) {
            await api("/assets", {
                method: "POST", silent: true,
                body: { kind: "text", title: t.title, content: t.content, tags: t.tags, source: "演示数据" },
            });
        }

        toast("演示数据已生成：3 张画布 + 4 张图片 + 2 条文本", "ok", 4200);
        await loadAll();
    } catch (err) {
        toast("生成中断：" + err.message, "error", 4600);
    } finally {
        btn.disabled = false;
        btn.textContent = "生成演示数据";
    }
}

function makeArtwork({ title, c1, c2 }) {
    const size = 512;
    const cv = document.createElement("canvas");
    cv.width = size;
    cv.height = size;
    const ctx = cv.getContext("2d");

    const g = ctx.createLinearGradient(0, 0, size, size);
    g.addColorStop(0, c1);
    g.addColorStop(1, c2);
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, size, size);

    // 光斑
    for (let i = 0; i < 4; i++) {
        const r = 70 + i * 34;
        const cx = 90 + i * 105;
        const cy = 120 + (i % 2) * 190;
        const rg = ctx.createRadialGradient(cx, cy, 0, cx, cy, r);
        rg.addColorStop(0, "rgba(255,255,255,.22)");
        rg.addColorStop(1, "rgba(255,255,255,0)");
        ctx.fillStyle = rg;
        ctx.beginPath();
        ctx.arc(cx, cy, r, 0, Math.PI * 2);
        ctx.fill();
    }

    // 网格
    ctx.strokeStyle = "rgba(255,255,255,.07)";
    ctx.lineWidth = 1;
    for (let x = 0; x <= size; x += 64) {
        ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, size); ctx.stroke();
    }
    for (let y = 0; y <= size; y += 64) {
        ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(size, y); ctx.stroke();
    }

    // 文字
    ctx.fillStyle = "rgba(255,255,255,.95)";
    ctx.font = "600 30px 'Segoe UI', 'PingFang SC', sans-serif";
    ctx.fillText(title, 34, size - 54);
    ctx.fillStyle = "rgba(255,255,255,.62)";
    ctx.font = "13px monospace";
    ctx.fillText("infinite canvas / stage-03 seed", 34, size - 30);

    return cv.toDataURL("image/png");
}

/* ---------- 12. 接口流水 ---------- */

function renderLogs() {
    const box = $("logTable");
    if (!box) return;
    $("logCount").textContent = state.logs.length + " 条请求";
    if (!state.logs.length) {
        box.innerHTML = "";
        box.append(el("div", { class: "empty", style: "border:0", text: "还没有请求。操作几下，这里会记录每个接口的耗时与响应" }));
        return;
    }
    box.innerHTML = "";
    for (const log of state.logs) {
        const okClass = log.status >= 200 && log.status < 300 && log.code === 0 ? "ok" : "bad";
        const row = el("div", { class: "log-row" }, [
            el("span", { class: "m " + log.method, text: log.method }),
            el("span", { class: "p", text: log.path, title: log.path }),
            el("span", { class: "s " + okClass, text: log.status || "ERR" }),
            el("span", { class: "c", text: log.code === undefined ? "-" : String(log.code) }),
            el("span", { class: "t", text: log.ms + "ms" }),
        ]);
        row.addEventListener("click", () => {
            if (state.expanded.has(log.id)) state.expanded.delete(log.id);
            else state.expanded.add(log.id);
            renderLogs();
        });
        box.append(row);

        if (state.expanded.has(log.id)) {
            const detail = el("div", { class: "log-detail" });
            if (log.request !== undefined) {
                detail.append(
                    el("div", { class: "lbl", text: "request" }),
                    el("pre", { text: typeof log.request === "string" ? log.request : JSON.stringify(log.request, null, 2) }),
                );
            }
            detail.append(
                el("div", { class: "lbl", text: "response" }),
                el("pre", { text: JSON.stringify(log.response, null, 2) }),
            );
            box.append(detail);
        }
    }
}

/* ---------- 13. 弹窗 ---------- */

function openModal() {
    const mask = el("div", { class: "modal-mask" });
    const panel = el("div", { class: "modal" });
    mask.append(panel);
    const close = () => mask.remove();
    mask.addEventListener("click", (e) => {
        if (e.target === mask) close();
    });
    $("modal-host").append(mask);
    return { mask, panel, close };
}

function confirmModal({ title, desc, confirmText = "确认", danger = false }) {
    return new Promise((resolve) => {
        const modal = openModal();
        modal.panel.innerHTML = "";
        modal.panel.append(
            el("h3", { text: title }),
            el("p", { class: "desc", text: desc || "" }),
            el("div", { class: "row" }, [
                el("button", { class: "btn ghost", text: "取消", type: "button", onclick: () => { modal.close(); resolve(false); } }),
                el("button", {
                    class: "btn " + (danger ? "danger" : "primary"),
                    text: confirmText,
                    type: "button",
                    onclick: () => { modal.close(); resolve(true); },
                }),
            ]),
        );
    });
}

function promptModal({ title, desc, label, value = "", confirmText = "确定" }) {
    return new Promise((resolve) => {
        const modal = openModal();
        modal.panel.innerHTML = "";
        const input = el("input", { class: "input", style: "max-width:none;width:100%", value });
        modal.panel.append(
            el("h3", { text: title }),
            el("p", { class: "desc", text: desc || "" }),
            el("span", { style: "font-size:12px;color:var(--text-dim)", text: label || "" }),
            input,
            el("div", { class: "row" }, [
                el("button", { class: "btn ghost", text: "取消", type: "button", onclick: () => { modal.close(); resolve(null); } }),
                el("button", { class: "btn primary", text: confirmText, type: "button", onclick: () => { const v = input.value; modal.close(); resolve(v); } }),
            ]),
        );
        setTimeout(() => { input.focus(); input.select(); }, 30);
        input.addEventListener("keydown", (e) => {
            if (e.key === "Enter") {
                const v = input.value;
                modal.close();
                resolve(v);
            }
        });
    });
}

/* ---------- 14. 启动 ---------- */

function enterApp() {
    $("authLayer").classList.add("hidden");
    $("shell").classList.remove("hidden");

    const name = (state.user && (state.user.nickname || state.user.username)) || "user";
    $("userName").textContent = name;
    $("userAvatar").textContent = name.slice(0, 1).toUpperCase();
    $("swaggerLink").href = CTX + "/swagger-ui/index.html";

    const initialView = (location.hash || "").replace("#", "");
    switchView(VIEW_META[initialView] ? initialView : "overview");
    probeHealth();
    loadAll();

    // 支持 /api/demo?project=<id> 直接打开某张画布的详情，方便分享/排查
    const openId = new URLSearchParams(location.search).get("project");
    if (openId) openProjectDetail(openId);
}

function bindGlobal() {
    $("logoutBtn").addEventListener("click", async () => {
        try {
            await api("/auth/logout", { method: "POST", body: { refreshToken: state.refreshToken }, silent: true });
        } catch { /* 本地照样清 */ }
        clearTokens();
        $("shell").classList.add("hidden");
        $("authLayer").classList.remove("hidden");
        toast("已退出登录", "ok");
    });

    $("newProjectBtn").addEventListener("click", createProject);
    $("seedBtn").addEventListener("click", seedDemo);

    $("syncAllPrompts").addEventListener("click", syncAllSources);
    $("newPromptSource").addEventListener("click", createSource);
    $("syncSamplePrompt").addEventListener("click", createSampleSource);

    let promptSearchTimer = null;
    $("promptSearch").addEventListener("input", (e) => {
        clearTimeout(promptSearchTimer);
        promptSearchTimer = setTimeout(async () => {
            state.prompts.keyword = e.target.value.trim();
            await refreshPrompts();
        }, 320);
    });

    let searchTimer = null;
    $("projectSearch").addEventListener("input", (e) => {
        clearTimeout(searchTimer);
        searchTimer = setTimeout(async () => {
            state.keyword = e.target.value.trim();
            if (!state.trash) await refreshProjects();
        }, 320);
    });

    $("trashToggle").addEventListener("change", async (e) => {
        state.trash = e.target.checked;
        $("newProjectBtn").disabled = state.trash;
        await refreshProjects();
    });

    $("clearLogs").addEventListener("click", () => {
        state.logs = [];
        state.expanded.clear();
        $("navLogs").textContent = "0";
        renderLogs();
    });

    document.addEventListener("keydown", (e) => {
        if (e.key === "Escape") {
            const mask = document.querySelector(".modal-mask");
            if (mask) mask.remove();
        }
    });
}

function boot() {
    bindAuth();
    bindNav();
    bindGlobal();
    bindAssets();

    // 恢复上次 token
    try {
        const saved = JSON.parse(localStorage.getItem(TOKEN_KEY) || "null");
        if (saved && saved.accessToken) {
            state.accessToken = saved.accessToken;
            state.refreshToken = saved.refreshToken || "";
            state.user = saved.user || null;
        }
    } catch { /* ignore */ }

    probeHealth();

    const wantDemo = new URLSearchParams(location.search).get("demo") === "1";

    if (state.accessToken) {
        // 先用 /users/me 验一下 token 还有效，再决定进主界面还是回登录
        api("/users/me", { silent: true })
            .then((user) => {
                state.user = user;
                enterApp();
            })
            .catch(() => {
                clearTokens();
                $("authLayer").classList.remove("hidden");
            });
    } else if (wantDemo) {
        demoLogin();
    }

    setInterval(() => {
        if (!$("shell").classList.contains("hidden")) probeHealth();
    }, 30000);
}

document.addEventListener("DOMContentLoaded", boot);
