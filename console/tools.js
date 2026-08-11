/**
 * Capture / Assert / Mock / Throttle helpers for Realtime Debug Console.
 * Expects globals: socket, events, setStatus, esc, pretty, formatTime, showDetail, renderList
 */
(function (global) {
  const STORAGE_ASSERT = "realtime_assert_rules";
  const STORAGE_MOCK = "realtime_mock_rules";
  const STORAGE_THROTTLE = "realtime_throttle";

  let assertRules = loadJson(STORAGE_ASSERT, []);
  let mockRules = loadJson(STORAGE_MOCK, []);
  let throttle = loadJson(STORAGE_THROTTLE, { profile: "none", delayMs: 0, failPercent: 0 });
  const assertResults = []; // {ts, url, pass, message, ruleId}

  const THROTTLE_PRESETS = {
    none: { delayMs: 0, failPercent: 0 },
    "3g": { delayMs: 400, failPercent: 0 },
    slow: { delayMs: 1200, failPercent: 0 },
    lossy: { delayMs: 300, failPercent: 15 },
    timeout: { delayMs: 8000, failPercent: 0 }
  };

  function loadJson(key, fallback) {
    try {
      const raw = localStorage.getItem(key);
      return raw ? JSON.parse(raw) : fallback;
    } catch (e) {
      return fallback;
    }
  }

  function saveJson(key, value) {
    localStorage.setItem(key, JSON.stringify(value));
  }

  function sendCmd(payload) {
    if (!global.socket || global.socket.readyState !== WebSocket.OPEN) {
      global.setStatus("未连接手机，无法下发", "err");
      return false;
    }
    global.socket.send(JSON.stringify(Object.assign({ type: "cmd" }, payload)));
    return true;
  }

  function uid() {
    return "r" + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  }

  function jsonPath(obj, path) {
    if (!path) return undefined;
    let p = String(path).trim();
    if (p.startsWith("$.")) p = p.slice(2);
    if (p.startsWith("$")) p = p.slice(1);
    if (p.startsWith(".")) p = p.slice(1);
    const parts = p.split(".").filter(Boolean);
    let cur = obj;
    for (const part of parts) {
      if (cur == null) return undefined;
      const m = part.match(/^(\w+)\[(\d+)\]$/);
      if (m) {
        cur = cur[m[1]];
        if (!Array.isArray(cur)) return undefined;
        cur = cur[Number(m[2])];
      } else {
        cur = cur[part];
      }
    }
    return cur;
  }

  function parseBody(body) {
    if (body == null || body === "") return null;
    if (typeof body === "object") return body;
    try { return JSON.parse(body); } catch (e) { return null; }
  }

  function evaluateAssert(ev) {
    const hits = [];
    assertRules.filter((r) => r.enabled !== false).forEach((rule) => {
      const url = String(ev.url || "");
      const method = String(ev.method || "").toUpperCase();
      if (rule.urlContains && !url.includes(rule.urlContains)) return;
      if (rule.method && rule.method.toUpperCase() !== method) return;

      const fails = [];
      if (rule.expectStatus != null && rule.expectStatus !== "" && Number(ev.code) !== Number(rule.expectStatus)) {
        fails.push(`status ${ev.code} != ${rule.expectStatus}`);
      }
      if (rule.maxDurationMs != null && rule.maxDurationMs !== "" && Number(ev.durationMs) > Number(rule.maxDurationMs)) {
        fails.push(`duration ${ev.durationMs}ms > ${rule.maxDurationMs}ms`);
      }
      if (rule.expectJsonPath) {
        const json = parseBody(ev.responseBody);
        const got = jsonPath(json, rule.expectJsonPath);
        const expect = rule.expectEquals;
        if (expect != null && expect !== "") {
          if (String(got) !== String(expect)) fails.push(`${rule.expectJsonPath}=${JSON.stringify(got)} != ${expect}`);
        } else if (got === undefined) {
          fails.push(`missing ${rule.expectJsonPath}`);
        }
      }
      if (rule.expectBodyContains) {
        const body = String(ev.responseBody || "");
        if (!body.includes(rule.expectBodyContains)) fails.push(`body missing "${rule.expectBodyContains}"`);
      }
      const pass = fails.length === 0;
      const item = {
        ts: Date.now(),
        ruleId: rule.id,
        name: rule.name || rule.urlContains || rule.id,
        url,
        pass,
        message: pass ? "通过" : fails.join("; ")
      };
      hits.push(item);
      assertResults.unshift(item);
      while (assertResults.length > 200) assertResults.pop();
    });
    return hits;
  }

  function toCurl(ev) {
    const method = (ev.method || "GET").toUpperCase();
    let cmd = `curl -X ${method} '${String(ev.url || "").replace(/'/g, "'\\''")}'`;
    const headers = ev.requestHeaders || {};
    Object.keys(headers).forEach((k) => {
      if (String(k).startsWith("_")) return;
      cmd += ` \\\n  -H '${k}: ${String(headers[k]).replace(/'/g, "'\\''")}'`;
    });
    if (ev.requestBody && method !== "GET" && method !== "HEAD") {
      cmd += ` \\\n  --data-raw '${String(ev.requestBody).replace(/'/g, "'\\''")}'`;
    }
    return cmd;
  }

  function exportJson() {
    const blob = new Blob([JSON.stringify(global.events || [], null, 2)], { type: "application/json" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `realtime-http-${Date.now()}.json`;
    a.click();
    URL.revokeObjectURL(a.href);
  }

  function exportHar() {
    const entries = (global.events || []).filter((e) => e.type === "http").map((ev) => ({
      startedDateTime: new Date(ev.ts || Date.now()).toISOString(),
      time: Number(ev.durationMs) || 0,
      request: {
        method: ev.method || "GET",
        url: ev.url || "",
        httpVersion: "HTTP/1.1",
        headers: Object.keys(ev.requestHeaders || {}).map((k) => ({ name: k, value: String(ev.requestHeaders[k]) })),
        queryString: [],
        postData: ev.requestBody ? { mimeType: "application/json", text: String(ev.requestBody) } : undefined,
        headersSize: -1,
        bodySize: -1
      },
      response: {
        status: Number(ev.code) || 0,
        statusText: "",
        httpVersion: "HTTP/1.1",
        headers: Object.keys(ev.responseHeaders || {}).map((k) => ({ name: k, value: String(ev.responseHeaders[k]) })),
        content: { size: -1, mimeType: "application/json", text: String(ev.responseBody || "") },
        redirectURL: "",
        headersSize: -1,
        bodySize: -1
      },
      cache: {},
      timings: { send: 0, wait: Number(ev.durationMs) || 0, receive: 0 }
    }));
    const har = {
      log: {
        version: "1.2",
        creator: { name: "RealtimeDebug", version: "1.0" },
        entries
      }
    };
    const blob = new Blob([JSON.stringify(har, null, 2)], { type: "application/json" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `realtime-${Date.now()}.har`;
    a.click();
    URL.revokeObjectURL(a.href);
  }

  function replay(ev) {
    if (!ev) return;
    const ok = sendCmd({
      action: "replay",
      method: ev.method || "GET",
      url: ev.url,
      headers: ev.requestHeaders || {},
      body: ev.requestBody || "",
      contentType: (ev.requestHeaders && (ev.requestHeaders["Content-Type"] || ev.requestHeaders["content-type"])) || "application/json; charset=utf-8"
    });
    if (ok) global.setStatus("已请求手机 Replay…", "warn");
  }

  function pushMockRules() {
    saveJson(STORAGE_MOCK, mockRules);
    const rules = mockRules.map((r) => {
      const out = {
        id: r.id,
        group: r.group || "",
        enabled: r.enabled !== false,
        priority: r.priority || 0,
        urlContains: r.urlContains || "",
        method: r.method || "",
        action: r.action || "mock",
        statusCode: Number(r.statusCode) || 200,
        delayMs: Number(r.delayMs) || 0,
        times: r.times == null ? -1 : Number(r.times),
        responseBody: r.responseBody || "",
        contentType: r.contentType || "application/json; charset=utf-8",
        rewriteUrl: r.rewriteUrl || "",
        rewriteMethod: r.rewriteMethod || "",
        setHeaders: r.setHeaders || {},
        removeHeaders: r.removeHeaders || []
      };
      if (r.action === "rewrite" && r.rewriteBodyEnabled) {
        out.rewriteBody = r.rewriteBody ?? "";
      }
      return out;
    });
    sendCmd({ action: "set_mock_rules", rules });
  }

  function pushThrottle() {
    saveJson(STORAGE_THROTTLE, throttle);
    sendCmd({ action: "set_throttle", throttle });
  }

  function syncToPhone() {
    pushMockRules();
    pushThrottle();
    sendCmd({ action: "get_config" });
  }

  function renderAssertPanel() {
    const list = document.getElementById("assertRuleList");
    const results = document.getElementById("assertResultList");
    if (!list || !results) return;
    list.innerHTML = assertRules.map((r, i) => `
      <div class="tool-row">
        <label><input type="checkbox" data-assert-en="${i}" ${r.enabled !== false ? "checked" : ""}/>启用</label>
        <input data-assert-name="${i}" value="${escAttr(r.name || "")}" placeholder="名称" />
        <input data-assert-url="${i}" value="${escAttr(r.urlContains || "")}" placeholder="URL 包含" />
        <input data-assert-method="${i}" value="${escAttr(r.method || "")}" placeholder="方法" style="width:72px" />
        <input data-assert-status="${i}" value="${escAttr(r.expectStatus ?? "")}" placeholder="期望状态码" style="width:90px" />
        <input data-assert-path="${i}" value="${escAttr(r.expectJsonPath || "")}" placeholder="JSONPath 如 $.code" />
        <input data-assert-eq="${i}" value="${escAttr(r.expectEquals ?? "")}" placeholder="期望值" style="width:90px" />
        <input data-assert-ms="${i}" value="${escAttr(r.maxDurationMs ?? "")}" placeholder="最大耗时ms" style="width:100px" />
        <button type="button" data-assert-del="${i}">删</button>
      </div>`).join("") || `<div class="empty">暂无断言。点「新增断言」。</div>`;

    results.innerHTML = assertResults.slice(0, 40).map((r) =>
      `<div class="assert-line ${r.pass ? "pass" : "fail"}">${window.formatTime(r.ts)} · ${window.esc(r.name)} · ${window.esc(r.message)}<div class="meta">${window.esc(r.url)}</div></div>`
    ).join("") || `<div class="empty">连接后命中规则的请求会显示断言结果</div>`;

    list.querySelectorAll("[data-assert-del]").forEach((btn) => {
      btn.onclick = () => {
        assertRules.splice(Number(btn.getAttribute("data-assert-del")), 1);
        saveJson(STORAGE_ASSERT, assertRules);
        renderAssertPanel();
      };
    });
    list.querySelectorAll("input").forEach((input) => {
      input.onchange = input.onblur = () => collectAssertFromDom();
    });
  }

  function collectAssertFromDom() {
    const next = [];
    document.querySelectorAll("#assertRuleList .tool-row").forEach((row, i) => {
      const old = assertRules[i] || { id: uid() };
      next.push({
        id: old.id || uid(),
        enabled: row.querySelector("[data-assert-en]")?.checked !== false,
        name: row.querySelector("[data-assert-name]")?.value || "",
        urlContains: row.querySelector("[data-assert-url]")?.value || "",
        method: row.querySelector("[data-assert-method]")?.value || "",
        expectStatus: row.querySelector("[data-assert-status]")?.value || "",
        expectJsonPath: row.querySelector("[data-assert-path]")?.value || "",
        expectEquals: row.querySelector("[data-assert-eq]")?.value ?? "",
        maxDurationMs: row.querySelector("[data-assert-ms]")?.value || ""
      });
    });
    assertRules = next;
    saveJson(STORAGE_ASSERT, assertRules);
  }

  function pathHintFromUrl(url) {
    try {
      const u = new URL(url);
      const parts = u.pathname.split("/").filter(Boolean);
      return parts.slice(-2).join("/") || u.pathname || url;
    } catch (e) {
      const s = String(url || "");
      const i = s.indexOf("?");
      const path = i >= 0 ? s.slice(0, i) : s;
      const parts = path.split("/").filter(Boolean);
      return parts.slice(-2).join("/") || path;
    }
  }

  function headersToLines(headers) {
    if (!headers || typeof headers !== "object") return "";
    return Object.keys(headers)
      .filter((k) => !String(k).startsWith("_"))
      .map((k) => `${k}: ${headers[k]}`)
      .join("\n");
  }

  function linesToHeaders(text) {
    const out = {};
    String(text || "").split(/\r?\n/).forEach((line) => {
      const t = line.trim();
      if (!t) return;
      const i = t.indexOf(":");
      if (i <= 0) return;
      out[t.slice(0, i).trim()] = t.slice(i + 1).trim();
    });
    return out;
  }

  function createRuleFromEvent(ev, action) {
    if (!ev || ev.type !== "http") {
      global.setStatus("请先选择一条 HTTP 请求", "err");
      return;
    }
    collectMockFromDom();
    const hint = pathHintFromUrl(ev.url);
    const ct = (ev.responseHeaders && (ev.responseHeaders["Content-Type"] || ev.responseHeaders["content-type"]))
      || (ev.requestHeaders && (ev.requestHeaders["Content-Type"] || ev.requestHeaders["content-type"]))
      || "application/json; charset=utf-8";
    const rule = {
      id: uid(),
      group: "",
      enabled: true,
      priority: 100,
      urlContains: hint,
      method: (ev.method || "").toUpperCase(),
      action: action === "rewrite" ? "rewrite" : "mock",
      statusCode: Number(ev.code) || 200,
      delayMs: 0,
      times: -1,
      responseBody: action === "rewrite" ? "" : String(ev.responseBody || ""),
      contentType: ct,
      rewriteUrl: "",
      rewriteMethod: "",
      rewriteBody: action === "rewrite" ? String(ev.requestBody || "") : "",
      rewriteBodyEnabled: action === "rewrite" && !!(ev.requestBody || ""),
      setHeaders: action === "rewrite" ? (ev.requestHeaders || {}) : {},
      removeHeaders: []
    };
    mockRules.unshift(rule);
    saveJson(STORAGE_MOCK, mockRules);
    renderMockPanel();
    if (typeof global.setRight === "function") global.setRight("mock");
    else {
      const tab = document.querySelector('[data-right="mock"]');
      if (tab) tab.click();
    }
    global.setStatus(action === "rewrite" ? "已生成 Rewrite 规则，请确认后同步" : "已生成 Mock 规则，请确认后同步", "ok");
  }

  function exportMockRules() {
    collectMockFromDom();
    const blob = new Blob([JSON.stringify({ version: 1, rules: mockRules }, null, 2)], { type: "application/json" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `realtime-mock-rules-${Date.now()}.json`;
    a.click();
    URL.revokeObjectURL(a.href);
    global.setStatus("规则已导出", "ok");
  }

  function importMockRules(file) {
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      try {
        const data = JSON.parse(String(reader.result || "{}"));
        const list = Array.isArray(data) ? data : (data.rules || []);
        if (!Array.isArray(list)) throw new Error("invalid rules");
        collectMockFromDom();
        const imported = list.map((r) => ({
          id: r.id || uid(),
          group: r.group || "",
          enabled: r.enabled !== false,
          priority: Number(r.priority) || 0,
          urlContains: r.urlContains || r.url || "",
          method: r.method || "",
          action: r.action || "mock",
          statusCode: Number(r.statusCode) || 200,
          delayMs: Number(r.delayMs) || 0,
          times: r.times == null ? -1 : Number(r.times),
          responseBody: r.responseBody || r.body || "",
          contentType: r.contentType || "application/json; charset=utf-8",
          rewriteUrl: r.rewriteUrl || "",
          rewriteMethod: r.rewriteMethod || "",
          rewriteBody: r.rewriteBody || "",
          rewriteBodyEnabled: !!r.rewriteBodyEnabled || (r.action === "rewrite" && Object.prototype.hasOwnProperty.call(r, "rewriteBody")),
          setHeaders: r.setHeaders || r.rewriteHeaders || {},
          removeHeaders: Array.isArray(r.removeHeaders) ? r.removeHeaders : []
        }));
        mockRules = mockRules.concat(imported);
        saveJson(STORAGE_MOCK, mockRules);
        renderMockPanel();
        global.setStatus(`已导入 ${imported.length} 条规则`, "ok");
      } catch (e) {
        global.setStatus("导入失败: JSON 格式不正确", "err");
      }
    };
    reader.readAsText(file);
  }

  function renderMockPanel() {
    const list = document.getElementById("mockRuleList");
    if (!list) return;
    const groups = {};
    mockRules.forEach((r, i) => {
      const g = (r.group || "").trim() || "未分组";
      if (!groups[g]) groups[g] = [];
      groups[g].push({ r, i });
    });
    const groupNames = Object.keys(groups);
    list.innerHTML = groupNames.length ? groupNames.map((gName) => {
      const rows = groups[gName].map(({ r, i }) => {
        const isRewrite = r.action === "rewrite";
        const setHeadersText = typeof r.setHeaders === "string"
          ? r.setHeaders
          : headersToLines(r.setHeaders || {});
        return `
      <div class="tool-row" data-mock-index="${i}">
        <label><input type="checkbox" data-mock-en="${i}" ${r.enabled !== false ? "checked" : ""}/>启用</label>
        <input data-mock-group="${i}" value="${escAttr(r.group || "")}" placeholder="分组" style="width:90px" />
        <input data-mock-url="${i}" value="${escAttr(r.urlContains || "")}" placeholder="URL 包含" />
        <input data-mock-method="${i}" value="${escAttr(r.method || "")}" placeholder="方法" style="width:72px" />
        <select data-mock-action="${i}">
          <option value="mock" ${r.action === "mock" || !r.action ? "selected" : ""}>Mock</option>
          <option value="abort" ${r.action === "abort" ? "selected" : ""}>Abort</option>
          <option value="rewrite" ${isRewrite ? "selected" : ""}>Rewrite</option>
        </select>
        <input data-mock-code="${i}" value="${escAttr(r.statusCode ?? 200)}" placeholder="状态码" style="width:72px" ${isRewrite ? "disabled" : ""} />
        <input data-mock-delay="${i}" value="${escAttr(r.delayMs ?? 0)}" placeholder="延迟ms" style="width:80px" />
        <input data-mock-times="${i}" value="${escAttr(r.times ?? -1)}" placeholder="次数(-1无限)" style="width:100px" />
        <button type="button" data-mock-del="${i}">删</button>
        <textarea data-mock-body="${i}" placeholder="${isRewrite ? "Rewrite 时此项为 Mock 响应（可空）" : "响应 Body JSON"}" ${isRewrite ? "style=\"opacity:.5\"" : ""}>${window.esc ? window.esc(r.responseBody || "") : (r.responseBody || "")}</textarea>
        <div class="rewrite-fields" style="${isRewrite ? "" : "display:none"}">
          <input data-mock-rw-url="${i}" value="${escAttr(r.rewriteUrl || "")}" placeholder="改写 URL（空=保持）" />
          <input data-mock-rw-method="${i}" value="${escAttr(r.rewriteMethod || "")}" placeholder="改写方法（空=保持）" style="width:100px" />
          <textarea data-mock-rw-headers="${i}" placeholder="改写/追加请求头，每行 Key: Value">${escAttr(setHeadersText)}</textarea>
          <textarea data-mock-rw-body="${i}" placeholder="改写请求 Body">${window.esc ? window.esc(r.rewriteBody || "") : (r.rewriteBody || "")}</textarea>
          <label style="font-size:12px;color:var(--muted)"><input type="checkbox" data-mock-rw-body-on="${i}" ${r.rewriteBodyEnabled ? "checked" : ""}/>改写 Request Body（勾选后才会替换；不勾选则保持原 Body）</label>
          <input data-mock-rw-remove="${i}" value="${escAttr((r.removeHeaders || []).join(", "))}" placeholder="删除请求头，逗号分隔" />
        </div>
      </div>`;
      }).join("");
      return `<div class="mock-group"><div class="mock-group-title">${window.esc ? window.esc(gName) : gName} · ${groups[gName].length}</div>${rows}</div>`;
    }).join("") : `<div class="empty">暂无规则。可点「新增规则」，或从请求详情「生成 Mock / Rewrite」。</div>`;

    list.querySelectorAll("[data-mock-del]").forEach((btn) => {
      btn.onclick = () => {
        collectMockFromDom();
        mockRules.splice(Number(btn.getAttribute("data-mock-del")), 1);
        saveJson(STORAGE_MOCK, mockRules);
        renderMockPanel();
      };
    });
    list.querySelectorAll("[data-mock-action]").forEach((sel) => {
      sel.onchange = () => {
        collectMockFromDom();
        renderMockPanel();
      };
    });
  }

  function collectMockFromDom() {
    const next = [];
    document.querySelectorAll("#mockRuleList .tool-row").forEach((row) => {
      const i = Number(row.getAttribute("data-mock-index"));
      const old = mockRules[i] || { id: uid() };
      const action = row.querySelector("[data-mock-action]")?.value || "mock";
      const removeRaw = row.querySelector("[data-mock-rw-remove]")?.value || "";
      const rewriteBodyEnabled = row.querySelector("[data-mock-rw-body-on]")?.checked === true;
      const item = {
        id: old.id || uid(),
        group: row.querySelector("[data-mock-group]")?.value || "",
        enabled: row.querySelector("[data-mock-en]")?.checked !== false,
        priority: 100 - next.length,
        urlContains: row.querySelector("[data-mock-url]")?.value || "",
        method: row.querySelector("[data-mock-method]")?.value || "",
        action,
        statusCode: Number(row.querySelector("[data-mock-code]")?.value || 200),
        delayMs: Number(row.querySelector("[data-mock-delay]")?.value || 0),
        times: Number(row.querySelector("[data-mock-times]")?.value ?? -1),
        responseBody: row.querySelector("[data-mock-body]")?.value || "",
        contentType: old.contentType || "application/json; charset=utf-8",
        rewriteUrl: row.querySelector("[data-mock-rw-url]")?.value || "",
        rewriteMethod: row.querySelector("[data-mock-rw-method]")?.value || "",
        rewriteBodyEnabled,
        setHeaders: linesToHeaders(row.querySelector("[data-mock-rw-headers]")?.value || ""),
        removeHeaders: removeRaw.split(/[,，]/).map((s) => s.trim()).filter(Boolean)
      };
      if (rewriteBodyEnabled) {
        item.rewriteBody = row.querySelector("[data-mock-rw-body]")?.value ?? "";
      }
      next.push(item);
    });
    if (document.querySelectorAll("#mockRuleList .tool-row").length) {
      mockRules = next;
      saveJson(STORAGE_MOCK, mockRules);
    }
  }

  function renderThrottlePanel() {
    const sel = document.getElementById("throttleProfile");
    const delay = document.getElementById("throttleDelay");
    const fail = document.getElementById("throttleFail");
    if (!sel) return;
    sel.value = throttle.profile || "none";
    delay.value = throttle.delayMs ?? 0;
    fail.value = throttle.failPercent ?? 0;
  }

  function escAttr(s) {
    return String(s ?? "")
      .replace(/&/g, "&amp;")
      .replace(/"/g, "&quot;")
      .replace(/</g, "&lt;");
  }

  function onHttpEvent(ev) {
    const hits = evaluateAssert(ev);
    if (hits.some((h) => !h.pass)) {
      const fail = hits.find((h) => !h.pass);
      global.setStatus("断言失败: " + fail.message, "err");
    }
    renderAssertPanel();
  }

  function onConfig(msg) {
    if (msg.rules) {
      mockRules = msg.rules;
      saveJson(STORAGE_MOCK, mockRules);
      renderMockPanel();
    }
    if (msg.throttle) {
      throttle = msg.throttle;
      saveJson(STORAGE_THROTTLE, throttle);
      renderThrottlePanel();
    }
  }

  function bindUi() {
    document.getElementById("btnExportJson")?.addEventListener("click", exportJson);
    document.getElementById("btnExportHar")?.addEventListener("click", exportHar);
    document.getElementById("btnAddAssert")?.addEventListener("click", () => {
      collectAssertFromDom();
      assertRules.push({
        id: uid(), enabled: true, name: "", urlContains: "", method: "",
        expectStatus: "200", expectJsonPath: "", expectEquals: "", maxDurationMs: ""
      });
      saveJson(STORAGE_ASSERT, assertRules);
      renderAssertPanel();
    });
    document.getElementById("btnSaveAssert")?.addEventListener("click", () => {
      collectAssertFromDom();
      global.setStatus("断言已保存（本地）", "ok");
      renderAssertPanel();
    });
    document.getElementById("btnAddMock")?.addEventListener("click", () => {
      collectMockFromDom();
      mockRules.push({
        id: uid(), group: "", enabled: true, priority: 0, urlContains: "", method: "",
        action: "mock", statusCode: 200, delayMs: 0, times: -1,
        responseBody: "{\"code\":0,\"msg\":\"mock\"}", contentType: "application/json; charset=utf-8",
        rewriteUrl: "", rewriteMethod: "", rewriteBody: "", rewriteBodyEnabled: false,
        setHeaders: {}, removeHeaders: []
      });
      saveJson(STORAGE_MOCK, mockRules);
      renderMockPanel();
    });
    document.getElementById("btnSyncMock")?.addEventListener("click", () => {
      collectMockFromDom();
      pushMockRules();
      global.setStatus("规则已同步到手机", "ok");
    });
    document.getElementById("btnExportMock")?.addEventListener("click", exportMockRules);
    document.getElementById("btnImportMock")?.addEventListener("click", () => {
      document.getElementById("mockImportFile")?.click();
    });
    document.getElementById("mockImportFile")?.addEventListener("change", (e) => {
      const file = e.target.files && e.target.files[0];
      importMockRules(file);
      e.target.value = "";
    });
    document.getElementById("btnClearMock")?.addEventListener("click", () => {
      mockRules = [];
      saveJson(STORAGE_MOCK, mockRules);
      renderMockPanel();
      sendCmd({ action: "clear_mock_rules" });
    });
    document.getElementById("btnApplyThrottle")?.addEventListener("click", () => {
      const profile = document.getElementById("throttleProfile").value;
      const preset = THROTTLE_PRESETS[profile] || THROTTLE_PRESETS.none;
      let delayMs = Number(document.getElementById("throttleDelay").value);
      let failPercent = Number(document.getElementById("throttleFail").value);
      if (profile !== "custom") {
        delayMs = preset.delayMs;
        failPercent = preset.failPercent;
        document.getElementById("throttleDelay").value = delayMs;
        document.getElementById("throttleFail").value = failPercent;
      }
      throttle = { profile, delayMs, failPercent };
      pushThrottle();
      global.setStatus("弱网已同步: " + profile, "ok");
    });
    document.getElementById("throttleProfile")?.addEventListener("change", () => {
      const profile = document.getElementById("throttleProfile").value;
      const preset = THROTTLE_PRESETS[profile];
      if (preset) {
        document.getElementById("throttleDelay").value = preset.delayMs;
        document.getElementById("throttleFail").value = preset.failPercent;
      }
    });
    document.getElementById("btnSyncAllTools")?.addEventListener("click", () => {
      collectMockFromDom();
      syncToPhone();
      global.setStatus("规则已同步到手机", "ok");
    });

    renderAssertPanel();
    renderMockPanel();
    renderThrottlePanel();
  }

  global.RealtimeTools = {
    bindUi,
    onHttpEvent,
    onConfig,
    toCurl,
    replay,
    syncToPhone,
    evaluateAssert,
    exportJson,
    exportHar,
    createRuleFromEvent,
    exportMockRules,
    importMockRules
  };
})(window);
