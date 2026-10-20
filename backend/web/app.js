/* NAS Monitor Web — 纯前端，无外部依赖。数据全部来自 nas-monitor-api 的 /api/*。 */
(function () {
  "use strict";

  var TOKEN_KEY = "nas_monitor_token";
  var THEME_KEY = "nas_monitor_theme";
  var ACCENT_KEY = "nas_monitor_accent";
  var REFRESH_KEY = "nas_monitor_refresh";
  var GATEWAY_TAB_KEY = "nas_monitor_gateway_tab";
  var DISPLAY_FONT_KEY = "nas_monitor_display_font";
  var DISPLAY_DENSITY_KEY = "nas_monitor_display_density";
  var CARD_NUMBERS_KEY = "nas_monitor_card_numbers";
  var OVERVIEW_ORDER_KEY = "nas_monitor_overview_order";

  var ACCENTS = [
    ["蓝", "#5B7CFA"], ["紫", "#8D51F9"], ["靛", "#3F51B5"],
    ["青", "#00BCD4"], ["天蓝", "#16B1FF"], ["青绿", "#009688"]
  ];

  var HOST_LABEL = { nas: "NAS", aliyun: "阿里云", tencent: "腾讯云" };

  function syncServers(servers) {
    if (!Array.isArray(servers)) return;
    var labels = {};
    servers.forEach(function (server) { labels[server.id] = server.name; });
    Object.keys(HOST_LABEL).forEach(function (id) {
      if (labels[id]) return;
      Object.keys(cache).forEach(function (key) {
        if (key.endsWith("_" + id) || key.indexOf("_" + id + "_") >= 0) { delete cache[key]; delete cacheAt[key]; }
      });
      localStorage.removeItem("nas_containers_page_" + id);
      [id, "trends/" + id, "temperature/" + id, "containers/" + id, "services/" + id, "security/" + id, "tools/" + id].forEach(function (route) {
        localStorage.removeItem(cardOrderKey(route));
      });
      if (cache.overview) {
        delete cache.overview[id];
        cache.overview.servers = (cache.overview.servers || []).filter(function (server) { return server.id !== id; });
        cache.overview.alerts = (cache.overview.alerts || []).filter(function (alert) { return alert.host !== id; });
      }
      var route = currentRoute().split("/");
      if (route[0] === id || route[1] === id) { ui.logName = null; ui.logText = null; location.hash = "overview"; }
    });
    HOST_LABEL = labels;
  }

  var cache = {};
  var cacheAt = {};
  var timers = {};
  var refreshing = false;
  var refreshQueued = false;
  var refreshRoute = "";
  // 后台刷新分级：网关探针约 650KB，不能随 10 秒轮询重复下载。
  var BACKGROUND_TTL = {
    overview: 15000, airport: 60000,
    gwOverview: 60000, gwPerformance: 120000, gwUsage: 120000, gwBalance: 15000, gwProbe: 60000,
    gwAffinity: 60000, gwPolicy: 30000, gwTokens: 60000, gwLogs: 60000,
    mihomoOverview: 30000, mihomoAirports: 30000,
    security: 30000, detail: 30000, router: 15000,
    trends: 60000, temperature: 60000, containers: 30000, cstats: 30000
  };

  /* ───────────── 基础工具 ───────────── */

  function token() { return localStorage.getItem(TOKEN_KEY) || ""; }
  function setToken(t) { localStorage.setItem(TOKEN_KEY, t); }
  function theme() { return localStorage.getItem(THEME_KEY) || "light"; }
  function accent() { return localStorage.getItem(ACCENT_KEY) || "#5B7CFA"; }
  function refreshSec() { var v = parseInt(localStorage.getItem(REFRESH_KEY) || "30", 10); return isNaN(v) ? 30 : v; }
  function displayFont() {
    var value = localStorage.getItem(DISPLAY_FONT_KEY) || "standard";
    return ["standard", "large", "extra_large"].indexOf(value) >= 0 ? value : "standard";
  }
  function displayDensity() {
    var value = localStorage.getItem(DISPLAY_DENSITY_KEY) || "visual";
    return ["visual", "compact"].indexOf(value) >= 0 ? value : "visual";
  }
  function cardNumbers() { return localStorage.getItem(CARD_NUMBERS_KEY) !== "0"; }
  function overviewOrder() {
    try {
      var value = JSON.parse(localStorage.getItem(OVERVIEW_ORDER_KEY) || "[]");
      return Array.isArray(value) ? value.filter(function (id) { return typeof id === "string"; }) : [];
    } catch (error) {
      return [];
    }
  }

  var DEFAULT_OVERVIEW_ORDER = ["nas", "aliyun", "tencent", "router", "airport", "alerts"];

  function orderedOverviewIds() {
    var order = overviewOrder();
    var merged = order.filter(function (id) { return DEFAULT_OVERVIEW_ORDER.indexOf(id) >= 0; });
    DEFAULT_OVERVIEW_ORDER.forEach(function (id) {
      if (merged.indexOf(id) < 0) merged.push(id);
    });
    return merged;
  }

  function applyTheme() {
    document.documentElement.setAttribute("data-theme", theme());
    document.documentElement.setAttribute("data-font-size", displayFont());
    document.documentElement.setAttribute("data-density", displayDensity());
    document.documentElement.classList.toggle("hide-card-numbers", !cardNumbers());
    document.documentElement.style.setProperty("--accent", accent());
    document.documentElement.style.setProperty("--accent-soft", hexA(accent(), .14));
  }

  function hexA(hex, a) {
    var h = hex.replace("#", "");
    var r = parseInt(h.substring(0, 2), 16), g = parseInt(h.substring(2, 4), 16), b = parseInt(h.substring(4, 6), 16);
    return "rgba(" + r + "," + g + "," + b + "," + a + ")";
  }

  function api(path) {
    return fetch("/api" + path, {
      headers: { "Authorization": "Bearer " + token() },
      cache: "no-store"
    }).then(function (r) {
      if (r.status === 401) { var e = new Error("访问口令无效"); e.code = 401; throw e; }
      return r.text().then(function (t) {
        var j = null;
        try { j = JSON.parse(t); } catch (e) {}
        if (!r.ok) throw new Error((j && (j.error || j.message)) || ("HTTP " + r.status));
        // 容器日志等端点返回纯文本：解析失败时回原文，避免渲染成 null
        return j === null ? t : j;
      });
    });
  }

  function apiPost(path, body) {
    return fetch("/api" + path, {
      method: "POST",
      headers: { "Authorization": "Bearer " + token(), "Content-Type": "application/json" },
      body: JSON.stringify(body || {})
    }).then(function (r) {
      if (r.status === 401) { var e = new Error("访问口令无效"); e.code = 401; throw e; }
      return r.text().then(function (t) {
        var j = null;
        try { j = JSON.parse(t); } catch (e) {}
        if (!r.ok) throw new Error((j && (j.error || j.message)) || ("HTTP " + r.status));
        return j;
      });
    });
  }

  function esc(s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) {
      return ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c];
    });
  }
  function n(v, d) { return (v === null || v === undefined || isNaN(v)) ? (d === undefined ? "--" : d) : v; }
  function pct(v, d) { return (v === null || v === undefined || isNaN(v)) ? (d || "--") : (Math.round(v * 10) / 10) + "%"; }
  function gb(v) { return (v === null || v === undefined) ? "--" : (Math.round(v * 10) / 10); }

  function rate(bps) {
    bps = Number(bps) || 0;
    if (bps >= 1024 * 1024) return (bps / 1024 / 1024).toFixed(1) + " MB/s";
    if (bps >= 1024) return (bps / 1024).toFixed(1) + " KB/s";
    return bps.toFixed(0) + " B/s";
  }
  function rateShort(bps) {
    bps = Number(bps) || 0;
    if (bps >= 1024 * 1024) return (bps / 1024 / 1024).toFixed(1) + " MB";
    if (bps >= 1024) return (bps / 1024).toFixed(0) + " KB";
    return bps.toFixed(0) + " B";
  }
  function uptimeShort(raw) {
    raw = String(raw || "");
    var m = raw.match(/(\d+)\s*days?/);
    if (m) return m[1] + "天";
    return raw.length > 8 ? raw.substring(0, 8) : raw;
  }
  function ago(ts) {
    if (!ts) return "--";
    var t = Date.parse(ts);
    if (isNaN(t)) return String(ts).substring(0, 19).replace("T", " ");
    var s = Math.max(0, Math.floor((Date.now() - t) / 1000));
    if (s < 60) return s + " 秒前";
    if (s < 3600) return Math.floor(s / 60) + " 分钟前";
    if (s < 86400) return Math.floor(s / 3600) + " 小时前";
    return Math.floor(s / 86400) + " 天前";
  }
  function usageColor(v) { return v >= 90 ? "var(--red)" : v >= 75 ? "var(--amber)" : "var(--green)"; }
  function usageSoft(v) { return v >= 90 ? "var(--red-soft)" : v >= 75 ? "var(--amber-soft)" : "var(--green-soft)"; }
  function maskName(s) {
    s = String(s == null ? "" : s);
    if (s.length <= 2) return s;
    if (/^[0-9]+$/.test(s)) return s.substring(0, 3) + "****" + s.substring(s.length - 2);
    return s.substring(0, 1) + "****" + s.substring(s.length - 1);
  }
  function fee(quota, per) { return quota ? (quota / (per || 500000)).toFixed(2) : "--"; }

  function toast(msg) {
    var el = document.getElementById("toast");
    el.textContent = msg;
    el.classList.add("show");
    clearTimeout(toast._t);
    toast._t = setTimeout(function () { el.classList.remove("show"); }, 2200);
  }

  /* ───────────── 图标（内联 SVG，无网络依赖） ───────────── */

  var ICONS = {
    server: "M4 4h16v6H4zM4 14h16v6H4zM7 7h.01M7 17h.01",
    alert: "M12 3l9 16H3zM12 9v5M12 17h.01",
    gateway: "M4 6h16M4 12h16M4 18h16M8 6v12M16 6v12",
    tools: "M14 3l7 7-3 3-7-7zM3 21l6-6M10 14l-7 7",
    lock: "M6 10V8a6 6 0 1112 0v2M5 10h14v11H5z",
    gear: "M12 9a3 3 0 100 6 3 3 0 000-6zM12 2l1.6 3.2 3.5-.6 1 3.4 3.4 1-2 3 2 3-3.4 1-1 3.4-3.5-.6L12 22l-1.6-3.2-3.5.6-1-3.4L2.5 15l2-3-2-3 3.4-1 1-3.4 3.5.6z",
    back: "M15 5l-7 7 7 7",
    disk: "M4 4h16v16H4zM4 9h16M4 15h16M8 12h.01",
    cpu: "M7 7h10v10H7zM3 9h4M3 15h4M17 9h4M17 15h4M9 3v4M15 3v4M9 17v4M15 17v4",
    clock: "M12 3a9 9 0 100 18 9 9 0 000-18zM12 7v5l4 2",
    refresh: "M20 11a8 8 0 10-2.3 5.7M20 5v6h-6",
    flame: "M12 22c4-2 6-5 6-9 0-4-3-6-3-9 0 0-3 2-3 5 0-1-1-2-2-2-1 3-4 4-4 8 0 3 2 6 6 7z",
    link: "M10 13a5 5 0 007 0l2-2a5 5 0 00-7-7l-1 1M14 11a5 5 0 00-7 0l-2 2a5 5 0 007 7l1-1",
    chat: "M4 5h16v11H9l-5 4z",
    list: "M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01",
    play: "M6 4l14 8-14 8z",
    check: "M4 12l5 5L20 6",
    term: "M4 5h16v14H4zM7 9l3 3-3 3M12 15h5"
  };
  function icon(name, size) {
    var d = ICONS[name] || ICONS.list;
    return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" ' +
      'stroke-linecap="round" stroke-linejoin="round" width="' + (size || 16) + '" height="' + (size || 16) + '">' +
      '<path d="' + d + '"/></svg>';
  }

  /* ───────────── 组件 ───────────── */

  function card(inner, cls) { return '<div class="card ' + (cls || "") + '">' + inner + '</div>'; }

  function uiSection(title, summary, key, detail, defaultOpen) {
    var open = ui.sections[key] === undefined ? !!defaultOpen : !!ui.sections[key];
    return card('<div class="row between"><span class="card-title">' + esc(title) + '</span>' +
      '<button class="btn small" data-ui-expand="' + esc(key) + '">' + (open ? "收起" : "展开") + '</button></div>' +
      '<div class="mt-2">' + summary + '</div>' +
      (open ? '<div class="ui-section-detail mt-3">' + detail() + '</div>' : ""));
  }

  function usageTile(label, value) {
    var v = Number(value) || 0;
    return '<div class="tile" style="background:' + usageSoft(v) + '">' +
      '<div class="v" style="color:' + usageColor(v) + '">' + Math.round(v) + '%</div>' +
      '<div class="k">' + esc(label) + '</div>' +
      '<div class="bar"><i style="width:' + Math.min(100, Math.max(0, v)) + '%;background:' + usageColor(v) + '"></i></div>' +
      '</div>';
  }

  function netTile(tx, rx) {
    return '<div class="tile tint">' +
      '<div class="k mb-1">网络</div>' +
      '<div class="net">↓ ' + esc(rx) + '</div>' +
      '<div class="net mt-1">↑ ' + esc(tx) + '</div>' +
      '</div>';
  }

  function gauge(value, label) {
    var v = Number(value) || 0;
    var c = usageColor(v);
    var arc = 270 * Math.min(100, v) / 100;
    var r = 20, cx = 26, cy = 26, circumference = 2 * Math.PI * r;
    var len = circumference * (arc / 360);
    var track = circumference * 0.75;
    return '<div class="gauge">' +
      '<svg viewBox="0 0 52 52">' +
      '<circle cx="' + cx + '" cy="' + cy + '" r="' + r + '" fill="none" stroke="var(--track)" stroke-width="4.5" ' +
      'stroke-linecap="round" stroke-dasharray="' + track + ' ' + circumference + '" transform="rotate(135 ' + cx + ' ' + cy + ')"/>' +
      '<circle cx="' + cx + '" cy="' + cy + '" r="' + r + '" fill="none" stroke="' + c + '" stroke-width="4.5" ' +
      'stroke-linecap="round" stroke-dasharray="' + len + ' ' + circumference + '" transform="rotate(135 ' + cx + ' ' + cy + ')"/>' +
      '</svg>' +
      '<div class="gv" style="color:' + c + '">' + Math.round(v) + '%</div>' +
      '<div class="k tiny muted">' + esc(label) + '</div>' +
      '</div>';
  }

  function serviceRow(name, svc) {
    var up = svc && svc.status === "up";
    return '<div class="list-row">' +
      '<span class="dot ' + (up ? "g" : "r") + '"></span>' +
      '<span class="grow">' + esc(name) + '</span>' +
      '<span class="caption muted">' + (svc && svc.ms != null ? svc.ms.toFixed(1) + " ms" : "--") + '</span>' +
      '<span class="pill ' + (up ? "on" : "off") + '">' + (up ? "正常" : "异常") + '</span>' +
      '</div>';
  }

  function lineChart(series, opts) {
    opts = opts || {};
    var w = 1000, h = opts.height || 240, pad = { l: 34, r: 10, t: 12, b: 22 };
    var all = [];
    series.forEach(function (s) { s.points.forEach(function (p) { if (isFinite(p.y)) all.push(p.y); }); });
    if (!all.length) return '<div class="empty">暂无趋势数据，等待采集…</div>';
    var min = Math.min.apply(null, all), max = Math.max.apply(null, all);
    if (min === max) { min = min - 1; max = max + 1; }
    if (opts.minFloorZero) min = 0;
    if (opts.percent) { min = 0; max = 100; }
    var n0 = series[0].points.length;
    function X(i, count) { return pad.l + (w - pad.l - pad.r) * (count <= 1 ? 0 : i / (count - 1)); }
    function Y(v) { return pad.t + (h - pad.t - pad.b) * (1 - (v - min) / (max - min)); }

    var grid = "";
    for (var gi = 0; gi <= 4; gi++) {
      var gv = min + (max - min) * gi / 4;
      var gy = Y(gv);
      grid += '<line x1="' + pad.l + '" y1="' + gy + '" x2="' + (w - pad.r) + '" y2="' + gy +
        '" stroke="var(--track)" stroke-width="1"/>' +
        '<text x="' + (pad.l - 5) + '" y="' + (gy + 3.5) + '" text-anchor="end" font-size="9" fill="var(--subtle)">' +
        (opts.percent ? Math.round(gv) + "%" : (Math.abs(max) >= 1000 ? Math.round(gv / 1000) + "k" : Math.round(gv))) +
        '</text>';
    }

    var paths = series.map(function (s) {
      var d = "";
      s.points.forEach(function (p, i) {
        if (!isFinite(p.y)) return;
        d += (d ? " L" : "M") + X(i, s.points.length).toFixed(1) + " " + Y(p.y).toFixed(1);
      });
      return '<path d="' + d + '" fill="none" stroke="' + s.color + '" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>';
    }).join("");

    var labels = "";
    if (series[0].points.length > 1) {
      var ticks = [0, Math.floor((n0 - 1) / 2), n0 - 1];
      ticks.forEach(function (i) {
        var p = series[0].points[i];
        if (!p) return;
        labels += '<text x="' + X(i, n0) + '" y="' + (h - 6) + '" text-anchor="middle" font-size="9" fill="var(--subtle)">' +
          esc(p.label || "") + '</text>';
      });
    }

    var legend = series.map(function (s) {
      return '<span><i style="background:' + s.color + '"></i>' + esc(s.name) + '</span>';
    }).join("");

    return '<div><svg class="spark" viewBox="0 0 ' + w + ' ' + h + '" preserveAspectRatio="none">' +
      grid + paths + labels + '</svg>' +
      (series.length > 1 ? '<div class="legend">' + legend + '</div>' : '') + '</div>';
  }

  function barRow(label, value, max, color, suffix) {
    var p = max > 0 ? Math.min(100, value / max * 100) : 0;
    return '<div class="mb-2">' +
      '<div class="row between caption"><span>' + esc(label) + '</span>' +
      '<span class="muted">' + esc(value) + (suffix || "") + '</span></div>' +
      '<div class="bar bar-sm"><i style="width:' + p + '%;background:' + color + '"></i></div>' +
      '</div>';
  }

  /* ───────────── 路由 ───────────── */

  var ROUTES = [
    ["overview", "服务器", "server", "总览"],
    ["alerts", "告警", "alert", "告警"],
    ["gateway", "网关", "gateway", "网关"],
    ["tools", "工具", "tools", "工具"],
    ["security", "安全", "lock", "安全"],
    ["settings", "设置", "gear", "设置"]
  ];

  function currentRoute() {
    var h = location.hash.replace(/^#\/?/, "");
    return h || "overview";
  }
  function go(route) { location.hash = "#/" + route; }

  var SUBTABS = {
    overview: [
      ["overview", "总览"], ["nas", "NAS"], ["aliyun", "阿里云"], ["tencent", "腾讯云"]
    ],
    gateway: [
      ["gateway", "概览"], ["gateway/performance", "性能"], ["gateway/logs", "日志"],
      ["gateway/channels", "渠道"], ["gateway/models", "用量"], ["gateway/proxy", "代理"]
    ],
    tools: [
      ["tools", "工具"], ["tools/downloader", "下载器"], ["ask", "知识问答"], ["knowledge-archive", "知识归档"]
    ],
    security: [
      ["security", "NAS"], ["security/aliyun", "阿里云"], ["security/tencent", "腾讯云"]
    ],
    settings: [
      ["settings", "外观"], ["settings/display", "显示"], ["settings/data", "连接与数据"]
    ]
  };

  function primaryTabKey(route) {
    var root = route.split("/")[0];
    if (root === "overview" || root === "nas" || root === "aliyun" || root === "tencent" ||
        root === "trends" || root === "temperature" || root === "containers" ||
        root === "services" || root === "disks" || root === "router") return "overview";
    if (root === "tools" || root === "ask" || root === "knowledge-archive") return "tools";
    return ROUTES.some(function (item) { return item[0] === root; }) ? root : "overview";
  }

  function ownerRouteKey(route) {
    var root = primaryTabKey(route);
    if (root === "overview") {
      var child = route.split("/")[0];
      if (child === "nas" || child === "aliyun" || child === "tencent") return child;
      if (child === "router" || child === "disks") return "overview";
      if (child === "trends" || child === "temperature" || child === "containers" ||
          child === "services" || child === "router") {
        var childHost = route.split("/")[1];
        if (childHost === "nas" || childHost === "aliyun" || childHost === "tencent") {
          return childHost;
        }
      }
    }
    return route;
  }

  function renderSubtabs(route) {
    var box = document.getElementById("subtabs");
    var root = primaryTabKey(route);
    var items = SUBTABS[root];
    if (!items) { box.innerHTML = ""; return; }
    var key = ownerRouteKey(route);
    box.innerHTML = '<div class="subnav" role="tablist" aria-label="页面标签">' +
      items.map(function (item) {
        var active = item[0] === key;
        return '<a role="tab" aria-selected="' + (active ? "true" : "false") + '" class="' + (active ? "active" : "") +
          '" href="#/' + item[0] + '">' + esc(item[1]) + '</a>';
      }).join("") + '</div>';
  }

  function renderNav() {
    var cur = primaryTabKey(currentRoute());
    var nav = document.getElementById("nav");
    nav.innerHTML = '<div class="nav-inner">' + ROUTES.map(function (r) {
      return '<a href="#/' + r[0] + '" class="' + (cur === r[0] ? "active" : "") + '">' +
        icon(r[2], 17) + '<span>' + r[1] + '</span></a>';
    }).join("") + '</div>';
  }

  /* ───────────── 页面：总览 ───────────── */

  function hostCard(name, hostData, hostId) {
    if (!hostData) return "";
    var online = hostData.status === "up";
    var net = hostData.network || {};
    return '<div id="card-host-' + hostId + '" class="card click overview-card" data-overview-card="' + hostId + '" data-go="' + hostId + '">' +
      '<div class="row">' +
      '<div class="icon-badge">' +
      icon("server", 19) + '</div>' +
      '<div class="grow">' +
      '<div class="row gap-2"><span class="card-title">' + esc(name) + '</span>' +
      '<span class="pill ' + (online ? "on" : "off") + '">' + (online ? "在线" : "离线") + '</span></div>' +
      '<div class="caption muted">组网 IP：' + esc(hostData.mesh_ip || "--") + '</div>' +
      '</div>' +
      '<span class="tiny subtle">' + esc(uptimeShort(hostData.uptime)) + '</span>' +
      '</div>' +
      '<div class="spacer-h"></div>' +
      '<div class="tile-grid">' +
      usageTile("CPU", hostData.cpu) +
      usageTile("内存", hostData.memory) +
      netTile(rateShort(net.tx_bps), rateShort(net.rx_bps)) +
      '</div>' +
      '<div class="row between mt-2">' +
      publicIpInline(hostData.public_ip) +
      coreServicesInline(hostData.core_services || {}) +
      '</div>' +
      '</div>';
  }

  function publicIpInline(ip) {
    if (!ip) return "<span></span>";
    return '<span class="row gap-1">' +
      '<span class="tiny muted">公网 IP</span>' +
      '<span class="mono tiny ipval subtle" data-ip="' + esc(ip) + '">已隐藏</span>' +
      '<button class="btn small toggle-ip" type="button">显示</button>' +
      '</span>';
  }

  function coreServicesInline(svcs) {
    var keys = Object.keys(svcs || {});
    if (!keys.length) return "<span></span>";
    var up = keys.filter(function (k) { return svcs[k].status === "up"; }).length;
    var all = up === keys.length;
    var detail = keys.map(function (k) {
      var s = svcs[k];
      return '<div class="list-row">' +
        '<span class="dot ' + (s.status === "up" ? "g" : "r") + '"></span>' +
        '<span class="grow">' + esc(k) + '</span>' +
        '<span class="caption muted">' + (s.ms != null ? s.ms.toFixed(1) + " ms" : "--") + '</span></div>';
    }).join("");
    return '<span class="row gap-1">' +
      '<span class="dot ' + (all ? "g" : "a") + '"></span>' +
      '<span class="tiny muted">核心服务</span>' +
      '<span class="tiny font-semibold ' + (all ? "green-text" : "amber-text") + '">' + up + "/" + keys.length + ' 正常</span>' +
      '<button class="btn small toggle-svc" type="button" data-list="' + esc(JSON.stringify(keys)) + '">展开</button>' +
      '<span class="svc-detail hidden">' + detail + '</span>' +
      '</span>';
  }

  function routerCard(r) {
    if (!r) return "";
    var online = r.status === "up" && r.internet !== "down";
    var wans = (r.wans || []).filter(function (w) { return w.ip; }).map(function (w) {
      return '<div class="row between tiny mt-2">' +
        '<span class="muted mono grow wrap-text">' + esc((w.name || "").toUpperCase()) + ': ' + esc(w.ip) + '</span>' +
        '<span class="green-text">↓' + rateShort(w.download_speed) + '</span>' +
        '<span class="amber-text">↑' + rateShort(w.upload_speed) + '</span>' +
        '</div>';
    }).join("");
    return '<div id="card-router" class="card click overview-card" data-overview-card="router" data-go="router">' +
      '<div class="row gap-2">' +
      '<div class="icon-badge">' +
      icon("gateway", 19) + '</div>' +
      '<div class="grow">' +
      '<div class="card-title">爱快路由器</div>' +
      '<div class="caption muted">' + esc(r.address || "路由器") + ' · ' + esc(r.wan_ip || "LAN") + '</div>' +
      '</div>' +
      '<span class="pill ' + (online ? "on" : "off") + '">' + (online ? "在线" : "离线") + '</span>' +
      '</div>' +
      '<div class="spacer-h"></div>' +
      '<div class="row gap-3">' + gauge(r.cpu_usage, "CPU") + gauge(r.mem_usage, "内存") +
      '<div class="gauge"><div class="gv ' + (r.cpu_temp > 70 ? "red-text" : "amber-text") + '">' +
      (r.cpu_temp > 0 ? r.cpu_temp + "°C" : "---") + '</div><div class="k tiny muted">温度</div></div>' +
      '</div>' +
      (wans ? '<div class="legend-row tiny subtle">' +
        '<span class="grow">WAN 实时速率</span>' +
        '<span class="green-text">↓ 下载</span>' +
        '<span class="amber-text">↑ 上传</span></div>' : '') + wans + '</div>';
  }

  function airportCard(items) {
    if (!items || !items.length) return "";
    return '<div id="card-airport" class="card overview-card" data-overview-card="airport">' +
      '<div class="row"><span class="accent-text">' + icon("tools", 17) + '</span>' +
      '<span class="card-title ml-2">机场流量</span>' +
      '<span class="grow"></span><span class="tiny subtle">订阅快照</span></div>' +
      '<div class="spacer-h"></div>' +
      items.map(function (it, idx) {
        var c = it.level === "critical" ? "var(--red)" : it.level === "warning" ? "var(--amber)" : "var(--green)";
        return '<div class="' + (idx ? "mt-3" : "") + '">' +
          '<div class="row between baseline"><span class="font-medium text-body">' + esc(it.name) + '</span>' +
          '<span class="num-main" style="color:' + c + '">' +
          (Math.round(it.remaining_gb * 10) / 10) + '<span class="num-unit"> / ' + Math.round(it.total_gb) + ' GB</span></span></div>' +
          '<div class="bar tall"><i style="width:' + Math.max(0, Math.min(100, 100 - it.used_percent)) +
          '%;background:' + c + '"></i></div>' +
          '<div class="tiny subtle mt-1">已用 ' + Math.round(it.used_percent) + '% · 重置 ' +
          (it.reset_days != null ? it.reset_days + "天" : "-") + ' · ' + esc(it.expire || "-") + '</div>' +
          '</div>';
      }).join("") + '</div>';
  }

  function renderOverview() {
    var ov = cache.overview;
    if (!ov) return renderLoading("正在采集服务器数据…");
    var alerts = ov.alerts || [];
    var crit = alerts.filter(function (a) { return a.level === "critical"; }).length;
    var summary = '<div id="card-alerts" data-overview-card="alerts" class="card overview-card alert-summary ' + (crit ? "bg-red-soft" : alerts.length ? "bg-amber-soft" : "bg-green-soft") + '">' +
      '<div class="row">' +
      '<span style="color:' + (crit ? "var(--red)" : alerts.length ? "var(--amber)" : "var(--green)") + '">' + icon("alert", 18) + '</span>' +
      '<div class="grow ml-2">' +
      '<div class="font-semibold text-lg">' + (alerts.length ? alerts.length + " 项待处理" : "系统运行正常") + '</div>' +
      '<div class="caption muted">' + (alerts.length ? (crit ? crit + " 项严重告警" : "均为警告级") : "所有主机与服务在线") + '</div>' +
      '</div>' +
      '<button class="btn small" data-go="alerts">查看</button>' +
      '</div></div>';

    var cards = {
      alerts: summary,
      nas: hostCard(HOST_LABEL.nas, ov.nas, "nas"),
      aliyun: hostCard(HOST_LABEL.aliyun, ov.aliyun, "aliyun"),
      tencent: hostCard(HOST_LABEL.tencent, ov.tencent, "tencent"),
      router: routerCard(ov.router),
      airport: airportCard((cache.airport || {}).items)
    };
    var workbench = orderedOverviewIds().map(function (id) { return cards[id] || ""; }).join("");
    return '<div class="overview-workbench">' + workbench + '</div>' +
      '<div class="footer-note">数据自 ' + esc(ago(ov.timestamp)) + '更新' +
      (refreshSec() > 0 ? ' · 每 ' + refreshSec() + ' 秒自动刷新' : '') + '</div>' +
      (cache.airport && (cache.airport.items || []).length
        ? orderHint()
        : '<div class="warnbox mt-3">机场流量数据尚未就绪，稍后刷新可继续加载。</div>');
  }

  function orderHint() {
    return '<div class="tiny subtle mt-2">卡片编号与排序可在「设置 → 显示」中调整。</div>';
  }

  function overviewDetail(ov, alerts) {
    var hosts = Object.keys(HOST_LABEL).map(function (id) { return ov[id]; }).filter(Boolean);
    var online = hosts.filter(function (host) { return host.status === "up"; }).length;
    var maxDisk = 0;
    hosts.forEach(function (host) {
      (host.disks || []).forEach(function (disk) { maxDisk = Math.max(maxDisk, Number(disk.percent) || 0); });
    });
    return card('<div class="card-title">运行概览</div>' +
      '<div class="detail-stat"><span>主机在线</span><strong>' + online + ' / ' + hosts.length + '</strong></div>' +
      '<div class="detail-stat"><span>待处理告警</span><strong class="' + (alerts.length ? "red-text" : "green-text") + '">' + alerts.length + '</strong></div>' +
      '<div class="detail-stat"><span>最高磁盘占用</span><strong class="' + (maxDisk >= 90 ? "red-text" : maxDisk >= 75 ? "amber-text" : "green-text") + '">' + Math.round(maxDisk) + '%</strong></div>' +
      '<div class="detail-stat"><span>最近更新</span><strong>' + esc(ago(ov.timestamp)) + '</strong></div>' +
      '<div class="detail-note">选择左侧卡片可进入详情、趋势、容器与服务。</div>', "detail-card");
  }

  /* ───────────── 页面：告警 ───────────── */

  function renderAlerts() {
    var ov = cache.overview;
    if (!ov) return renderLoading("正在加载告警…");
    var alerts = (ov.alerts || []).slice().sort(function (a, b) {
      if (a.level === b.level) return String(a.first_seen || "").localeCompare(String(b.first_seen || ""));
      return a.level === "critical" ? -1 : 1;
    });
    if (!alerts.length) {
      return card('<div class="empty"><div class="big green-text">' + icon("check", 40) + '</div>' +
        '<div class="font-semibold green-text">当前没有需要处理的告警</div>' +
        '<div class="caption">一切正常</div></div>');
    }
    var crit = alerts.filter(function (a) { return a.level === "critical"; }).length;
    var head = '<div class="card alert-summary ' + (crit ? "bg-red-soft" : "bg-amber-soft") + '">' +
      '<div class="row"><span style="color:' + (crit ? "var(--red)" : "var(--amber)") + '">' + icon("alert", 18) + '</span>' +
      '<div class="grow ml-2"><div class="font-semibold text-lg">待处理</div>' +
      '<div class="caption muted">按严重度与首次出现时间排序</div></div>' +
      '<span class="pill neutral">' + alerts.length + ' 项</span></div></div>';
    return head + alerts.map(function (a) {
      var c = a.level === "critical";
      return card('<div class="alert-row">' +
        '<span class="alert-dot" style="background:' + (c ? "var(--red)" : "var(--amber)") + '"></span>' +
        '<div class="grow">' +
        '<div class="row gap-2"><span class="tiny muted">' + esc(HOST_LABEL[a.host] || a.host || "--") + '</span>' +
        '<span class="pill ' + (c ? "off" : "warn") + '">' + (c ? "严重" : "警告") + '</span></div>' +
        '<div class="mt-1 font-medium text-body">' + esc(a.message) + '</div>' +
        (a.first_seen ? '<div class="tiny subtle mt-1">首次出现 ' +
          esc(String(a.first_seen).substring(0, 19).replace("T", " ")) + '</div>' : '') +
        '</div></div>');
    }).join("");
  }

  /* ───────────── 页面：网关 ───────────── */

  /* ── 网关页 Tab 定义（与 Android gatewayNavItems 对齐） ── */
  var GATEWAY_TABS = ["概览", "性能", "日志", "渠道", "用量", "代理"];
  var ui = window._ui = window._ui || {};
  function gatewayTab() {
    var slug = currentRoute().split("/")[1];
    var routeTabs = { performance: 1, logs: 2, channels: 3, models: 4, proxy: 5 };
    if (slug in routeTabs) return routeTabs[slug];
    var t = parseInt(localStorage.getItem(GATEWAY_TAB_KEY) || "0", 10);
    return (isNaN(t) || t < 0 || t > 5) ? 0 : t;
  }

  function renderGateway() {
    var gw = cache.gwOverview, bal = cache.gwBalance;
    if (!gw && !bal) return renderLoading("正在加载网关数据…");
    var tab = gatewayTab();
    var bodyFns = [renderGwOverview, renderGwPerf, renderGwLogs, renderGwChannels, renderGwUsage, renderGwProxy];
    return (bodyFns[tab] || renderGwOverview)();
  }

  /* ── Tab 1: 概览 ── */
  function renderGwOverview() {
    var gw = cache.gwOverview, bal = cache.gwBalance, probe = cache.gwProbe;
    var out = "";
    if (gw) {
      var req24 = (gw.requests_24h != null ? gw.requests_24h : gw.recent_requests) || 0;
      out += card(
        '<div class="tile-grid cols-4">' +
        '<div class="tile"><div class="v">' + req24 + '</div><div class="k">24h 请求</div></div>' +
        '<div class="tile"><div class="v amber-text">' + (gw.disabled_channels || 0) + '</div><div class="k">停用渠道</div></div>' +
        '<div class="tile"><div class="v red-text">' + (gw.auto_banned_channels || 0) + '</div><div class="k">自动封禁</div></div>' +
        '<div class="tile"><div class="v accent-text">' + (gw.rpm != null ? gw.rpm : "--") + '</div><div class="k">RPM</div></div>' +
        '</div>');
    }
    var summary = "";
    if (bal) {
      summary += '<div class="row between"><span class="font-medium">渠道余额</span>' +
        '<span class="font-semibold">$' + (bal.total != null ? bal.total.toFixed(2) : "--") + '</span></div>' +
        '<div class="tiny subtle mt-1">' + ((bal.channels || []).length) + ' 个渠道 · 更新于 ' +
        esc(bal.updated_at || "--") + '</div>';
    }
    if (probe) {
      var ps = probe.summary || {};
      summary += '<div class="row between mt-3"><span class="font-medium">探针状态</span>' +
        '<span class="pill ' + (probe.probe_status === "ok" ? "on" : probe.probe_status === "degraded" ? "warn" : "off") + '">' +
        esc(probe.probe_status) + '</span></div>' +
        '<div class="tiny subtle mt-1">健康 ' + n(ps.healthy, 0) + ' · 降级 ' + n(ps.degraded, 0) +
        ' · 失败 ' + n(ps.failed, 0) + '</div>';
    }
    if (summary) {
      out += uiSection("网关补充状态", summary, "gw-overview-more", function () {
        return (bal ? renderGwBalance(bal) : "") + (probe ? renderGwProbe(probe) : "");
      });
    }
    return out;
  }

  /* ── Tab 2: 性能 ── */
  function renderGwPerf() {
    var policy = cache.gwPolicy, aff = cache.gwAffinity;
    var out = "";
    if (policy) {
      var policyChs = (cache.gwOverview && cache.gwOverview.channels || []);
      var selCh = policy.channel_id || ((policyChs[0] || {}).id) || "";
      out += card('<div class="row between"><span class="card-title">路由策略</span>' +
        '<span class="pill ' + (policy.active ? "on" : "neutral") + '">' + (policy.active ? "已生效" : "未启用") + '</span></div>' +
        '<div class="kv mt-3">' +
        '<div class="kv-item"><span>当前模式</span><span>' + esc(policyModeLabel(policy.mode)) + '</span></div>' +
        '<div class="kv-item"><span>目标渠道</span><span>' + esc(policy.channel_name || "-") + '</span></div>' +
        '<div class="kv-item"><span>生效时间</span><span>' + esc(policy.applied_at || "-") + '</span></div>' +
        '</div>' +
        '<div class="mt-3"><label class="field"><span>选择渠道</span>' +
        '<select id="policy-channel" class="policy-ch-select">' +
        policyChs.map(function (c) {
          return '<option value="' + c.id + '"' + (String(c.id) === String(selCh) ? " selected" : "") + '>' +
            esc(c.name) + '（ID ' + c.id + '）</option>';
        }).join("") +
        '</select></label></div>' +
        '<div class="row gap-2 mt-3 wrap-flex">' +
        '<button class="btn small" data-policy="preferred">设为最优先</button>' +
        '<button class="btn small" data-policy="exclusive">仅使用该渠道</button>' +
        '<button class="btn small danger" data-policy-restore="1">恢复正常路由</button>' +
        '</div>');
    }
    if (aff) {
      out += card('<div class="row between"><span class="card-title">亲和调度</span>' +
        '<span class="pill ' + (aff.enabled ? "on" : "neutral") + '">' + (aff.enabled ? "已开启" : "已关闭") + '</span></div>' +
        '<div class="kv mt-3">' +
        '<div class="kv-item"><span>缓存条目</span><span>' + n((aff.cache || {}).total, 0) + '</span></div>' +
        '<div class="kv-item"><span>容量</span><span>' + n((aff.cache || {}).cache_capacity, 0) + '</span></div>' +
        '<div class="kv-item"><span>算法</span><span>' + esc((aff.cache || {}).cache_algo || "-") + '</span></div>' +
        '</div>' +
        '<div class="row gap-2 mt-3">' +
        '<button class="btn small" data-affinity="' + (aff.enabled ? "0" : "1") + '">' +
        (aff.enabled ? "关闭亲和" : "开启亲和") + '</button>' +
        '<button class="btn small" data-affinity-clear="1">清空缓存</button>' +
        '</div>');
    }
    if (!out) return '<div class="empty caption">暂无性能数据</div>';
    return out;
  }

  /* ── Tab 3: 日志 ── */
  function renderGwLogs() {
    var logs = cache.gwLogs;
    var items = (logs && logs.items) || [];
    var shown = ui.logsExpanded ? items : items.slice(0, 8);
    var hidden = items.length - shown.length;
    return card('<div class="row between"><span class="card-title">调用日志</span>' +
      (logs ? '<span class="tiny subtle">第 ' + Math.max(1, logs.page || 1) + ' 页 · 共 ' + (logs.total || 0) + ' 条</span>' : '') + '</div>' +
      '<div class="spacer-h"></div>' +
      '<div class="row gap-2 mb-3 wrap-flex">' +
      '<button class="btn small" data-logs-page="0">最新</button>' +
      (cache.gwTokens && cache.gwTokens.tokens || []).slice(0, 4).map(function (t) {
        return '<button class="chip logs-token" data-token="' + esc(t) + '">' + esc(t) + '</button>';
      }).join("") +
      '</div>' +
      (items.length ? '<div class="scroll-x"><table><thead><tr>' +
        '<th>时间</th><th>模型</th><th>渠道</th><th>令牌</th><th>推理</th><th class="num">耗时</th><th>结果</th><th class="num">tokens</th>' +
        '</tr></thead><tbody>' + shown.map(function (l) {
          var ok = l.outcome === "success";
          var c = ok ? "g" : l.outcome === "client_cancelled" ? "a" : "r";
          var ts = l.created_at ? new Date(l.created_at * 1000).toLocaleString("zh-CN", { hour12: false }) : "--";
          var effort = l.reasoning_effort || "--";
          var effortClass = effort === "high" ? "red-text" : effort === "medium" ? "amber-text" : "subtle";
          return '<tr><td class="tiny">' + esc(ts) + '</td>' +
            '<td class="wrap-text">' + esc(l.model) + '</td>' +
            '<td class="tiny muted">' + esc(l.channel_name || (l.channel_id != null ? l.channel_id : "-")) + '</td>' +
            '<td class="tiny">' + esc(l.token_name || "-") + '</td>' +
            '<td class="tiny ' + effortClass + '">' + esc(effort) + '</td>' +
            '<td class="num">' + (l.use_time ? (l.use_time / 1000).toFixed(1) + "s" : "--") + '</td>' +
            '<td><span class="dot ' + c + '"></span> ' + esc(l.outcome) + '</td>' +
            '<td class="num tiny">' + ((l.prompt_tokens || 0) + (l.completion_tokens || 0)) + '</td></tr>';
        }).join("") + '</tbody></table></div>' +
      (hidden > 0 ? '<div class="row between mt-3"><span class="tiny subtle">本页显示最新 ' + shown.length +
        ' / ' + items.length + ' 条</span><button class="btn small" data-logs-expand="1">显示本页全部</button></div>' : '') +
      (ui.logsExpanded ? '<div class="row between mt-3"><span class="tiny subtle">已显示本页 ' + shown.length +
        ' 条</span><button class="btn small" data-logs-expand="0">收起</button></div>' : '') +
      (logs && logs.total > 30 ? '<div class="row between mt-3"><span class="tiny subtle">第 ' + Math.max(1, logs.page || 1) + ' 页 · 共 ' + logs.total + ' 条</span>' +
        '<div class="row gap-2">' +
        (Math.max(1, logs.page || 1) > 1 ? '<button class="btn small" data-logs-page="' + (Math.max(1, logs.page || 1) - 1) + '">上一页</button>' : '') +
        '<button class="btn small" data-logs-page="' + (Math.max(1, logs.page || 1) + 1) + '">下一页</button>' +
        '</div></div>' : '')
      : '<div class="empty caption">暂无日志</div>'));
  }

  /* ── Tab 4: 渠道 ── */
  function renderGwChannels() {
    var gw = cache.gwOverview, bal = cache.gwBalance;
    var out = "";
    if (gw && (gw.channels || []).length) {
      var channels = ui.channelsExpanded ? gw.channels : gw.channels.slice(0, 6);
      out += card('<div class="card-title">渠道（' + gw.channels.length + '）</div><div class="spacer-h"></div>' +
        '<div class="channel-grid">' +
        channels.map(function (c) {
          var enabled = c.status === 1;
          var modelsOpen = !!ui.channelModels[c.id];
          return '<div class="channel-item">' +
            '<div class="row between gap-2"><span class="row gap-2 grow"><span class="card-number">' + c.id + '</span>' +
            '<span class="font-medium wrap-text">' + esc(c.name) + '</span></span>' +
            '<span class="pill ' + (enabled ? "on" : "neutral") + '">' + (enabled ? "启用" : "停用") + '</span></div>' +
            '<div class="channel-models ' + (modelsOpen ? "" : "clamped") + '">' + esc(c.models) + '</div>' +
            '<button class="channel-models-toggle" data-channel-models="' + c.id + '">' +
            (modelsOpen ? "收起模型" : "展开模型") + '</button>' +
            '<div class="row between mt-2"><span class="tiny subtle">响应 ' +
            (c.response_time ? (c.response_time / 1000).toFixed(1) + "s" : "--") + '</span>' +
            '<button class="btn small ch-toggle" data-id="' + c.id + '" data-status="' + (enabled ? 2 : 1) + '">' +
            (enabled ? "停用" : "启用") + '</button></div></div>';
        }).join("") + '</div>' +
        (gw.channels.length > 6 ? '<div class="row between mt-3"><span class="tiny subtle">' +
          (ui.channelsExpanded ? '已显示全部 ' + gw.channels.length : '显示前 6 / 共 ' + gw.channels.length) +
          ' 个渠道</span><button class="btn small" data-channels-expand="' +
          (ui.channelsExpanded ? '0' : '1') + '">' + (ui.channelsExpanded ? '收起' : '显示全部') + '</button></div>' : ''));
    }
    if (bal) out += renderGwBalance(bal);
    if (!out) return '<div class="empty caption">暂无渠道数据</div>';
    return out;
  }

  /* ── Tab 5: 用量 ── */
  function renderGwUsage() {
    var data = cache.gwUsage;
    if (!data) {
      return card('<div class="row between"><span class="card-title">模型用量</span>' +
        '<button class="btn small" data-usage-retry="1">' + icon("refresh", 13) + ' 重试</button></div>' +
        '<div class="empty caption">用量统计暂不可用，请检查统计接口或稍后重试。</div>');
    }
    var windowKey = ui.usageWindow || "1d";
    var current = (data.windows || {})[windowKey] || {};
    var models = current.models || [];
    var totals = models.reduce(function (sum, item) {
      sum.requests += Number(item.requests) || 0;
      sum.tokens += Number(item.tokens) || 0;
      return sum;
    }, { requests: 0, tokens: 0 });
    var labels = { "1d": "1 天", "7d": "7 天", "30d": "30 天" };
    var out = '<div class="usage-summary">' +
      '<div class="usage-summary-head"><div><div class="usage-title">模型用量</div>' +
      '<div class="usage-subtitle">' + esc(current.start_date || "--") + ' 至 ' +
      esc(current.end_date || "--") + '</div></div>' +
      '<span class="pill ' + (data.stale ? "warn" : data.source === "sqlite" ? "on" : "warn") + '">' +
      esc(data.stale ? "旧快照" : data.source === "sqlite" ? "实时快照" : "降级数据") + '</span></div>' +
      '<div class="usage-summary-metrics">' +
      '<div><strong>' + compactNumber(totals.requests) + '</strong><span>请求数</span></div>' +
      '<div><strong>' + compactNumber(totals.tokens) + '</strong><span>Token 数</span></div>' +
      '<div><strong>' + models.length + '</strong><span>活跃模型</span></div>' +
      '</div></div>' +
      '<div class="usage-segment-wrap" role="tablist" aria-label="用量时间窗口">' +
      Object.keys(labels).map(function (key) {
        return '<button class="usage-segment ' + (windowKey === key ? "active" : "") +
          '" data-usage-window="' + key + '" role="tab" aria-selected="' +
          (windowKey === key ? "true" : "false") + '">' + labels[key] + '</button>';
      }).join("") + '</div>';
    if (data.coverage && data.coverage.complete === false) {
      out += '<div class="usage-partial-note">当前为旧快照或部分结果</div>';
    }
    if (!models.length) {
      return out + card('<div class="empty caption">该窗口暂无模型调用记录。</div>');
    }
    out += '<div class="usage-distribution-card">' +
      '<div class="usage-card-head"><div><div class="usage-card-title">模型分布</div>' +
      '<div class="usage-card-caption">按请求数占比</div></div></div>' +
      '<div class="usage-distribution">' +
      renderUsageDonut(models) +
      '<div class="usage-model-table-wrap"><table class="usage-model-table"><thead><tr>' +
      '<th>模型</th><th class="num">请求数</th><th class="num">Token 数</th></tr></thead><tbody>' +
      models.map(function (item, index) {
        return '<tr><td><span class="usage-model-dot" style="background:' + usagePalette[index % usagePalette.length] + '"></span>' +
          '<span class="usage-model-name">' + esc(item.model) + '</span></td>' +
          '<td class="num">' + compactNumber(item.requests) + '</td>' +
          '<td class="num">' + compactNumber(item.tokens) + '</td></tr>';
      }).join("") + '</tbody></table></div></div></div>';
    var daily = (data.daily || []).filter(function (item) {
      return item.date >= (current.start_date || "");
    });
    var byDate = {};
    daily.forEach(function (item) {
      if (!byDate[item.date]) byDate[item.date] = { requests: 0, tokens: 0 };
      byDate[item.date].requests += item.requests || 0;
      byDate[item.date].tokens += item.tokens || 0;
    });
    var dates = Object.keys(byDate).sort();
    out += renderUsageTrend(dates.map(function (date) {
      return { label: date.substring(5), requests: byDate[date].requests, tokens: byDate[date].tokens };
    }));
    return out;
  }

  var usagePalette = ["#3b82f6", "#10b981", "#f59e0b", "#ef4444", "#8b5cf6", "#ec4899", "#14b8a6"];

  function compactNumber(value) {
    value = Number(value) || 0;
    if (value >= 100000000) return (value / 100000000).toFixed(2) + " 亿";
    if (value >= 10000) return (value / 10000).toFixed(1) + " 万";
    return value.toLocaleString("zh-CN");
  }

  function renderUsageDonut(models) {
    var total = models.reduce(function (sum, item) { return sum + (Number(item.requests) || 0); }, 0) || 1;
    var radius = 52, circumference = 2 * Math.PI * radius, offset = 0;
    var arcs = models.map(function (item, index) {
      var ratio = (Number(item.requests) || 0) / total;
      var length = circumference * ratio;
      var circle = '<circle cx="64" cy="64" r="' + radius + '" fill="none" stroke="' +
        usagePalette[index % usagePalette.length] + '" stroke-width="16" stroke-dasharray="' +
        Math.max(0, length - 2) + ' ' + circumference + '" stroke-dashoffset="' + (-offset) +
        '" transform="rotate(-90 64 64)"></circle>';
      offset += length;
      return circle;
    }).join("");
    return '<div class="usage-donut"><svg viewBox="0 0 128 128" role="img" aria-label="模型请求占比">' +
      '<circle cx="64" cy="64" r="' + radius + '" fill="none" stroke="var(--track)" stroke-width="16"></circle>' +
      arcs + '</svg><div class="usage-donut-center"><strong>' + compactNumber(total) + '</strong><span>请求</span></div></div>';
  }

  function monotoneUsagePath(points) {
    if (!points.length) return "";
    if (points.length === 1) return "M" + points[0].x + " " + points[0].y;
    var n = points.length, dx = [], slopes = [], tangents = [];
    for (var i = 0; i < n - 1; i++) {
      dx[i] = points[i + 1].x - points[i].x;
      slopes[i] = (points[i + 1].y - points[i].y) / Math.max(dx[i], 0.0001);
    }
    tangents[0] = slopes[0];
    tangents[n - 1] = slopes[n - 2];
    for (var j = 1; j < n - 1; j++) {
      tangents[j] = slopes[j - 1] * slopes[j] <= 0 ? 0 : (slopes[j - 1] + slopes[j]) / 2;
    }
    for (var k = 0; k < n - 1; k++) {
      if (slopes[k] === 0) {
        tangents[k] = 0;
        tangents[k + 1] = 0;
      } else {
        var a = tangents[k] / slopes[k], b = tangents[k + 1] / slopes[k];
        var h = Math.sqrt(a * a + b * b);
        if (h > 3) {
          tangents[k] = 3 * slopes[k] * a / h;
          tangents[k + 1] = 3 * slopes[k] * b / h;
        }
      }
    }
    var path = "M" + points[0].x + " " + points[0].y;
    for (var p = 0; p < n - 1; p++) {
      var third = dx[p] / 3;
      path += " C" + (points[p].x + third) + " " + (points[p].y + tangents[p] * third) +
        " " + (points[p + 1].x - third) + " " + (points[p + 1].y - tangents[p + 1] * third) +
        " " + points[p + 1].x + " " + points[p + 1].y;
    }
    return path;
  }

  function usageAreaPath(points, baseline) {
    if (!points.length) return "";
    return monotoneUsagePath(points) + " L" + points[points.length - 1].x + " " + baseline +
      " L" + points[0].x + " " + baseline + " Z";
  }

  function renderUsageTrend(points) {
    var cardHead = '<div class="usage-card-head"><div><div class="usage-card-title">用量趋势</div>' +
      '<div class="usage-card-caption">请求数与 Token 变化</div></div>' +
      '<div class="usage-legend"><span><i style="background:#3b82f6"></i>请求数</span>' +
      '<span><i style="background:#10b981"></i>Token</span></div></div>';
    if (!points.length) return '<div class="usage-trend-card">' + cardHead + '<div class="empty caption">该窗口暂无每日数据。</div></div>';
    var width = 720, height = 192, pad = { left: 10, right: 12, top: 18, bottom: 28 };
    var maxRequests = Math.max.apply(null, points.map(function (p) { return p.requests; })) || 1;
    var maxTokens = Math.max.apply(null, points.map(function (p) { return p.tokens; })) || 1;
    var innerWidth = width - pad.left - pad.right, innerHeight = height - pad.top - pad.bottom;
    var requestPoints = points.map(function (point, index) {
      return { x: pad.left + innerWidth * (points.length <= 1 ? 0 : index / (points.length - 1)),
        y: pad.top + innerHeight * (1 - point.requests / maxRequests) };
    });
    var tokenPoints = points.map(function (point, index) {
      return { x: pad.left + innerWidth * (points.length <= 1 ? 0 : index / (points.length - 1)),
        y: pad.top + innerHeight * (1 - point.tokens / maxTokens) };
    });
    var grid = "";
    for (var gi = 0; gi <= 4; gi++) {
      var y = pad.top + innerHeight * gi / 4;
      grid += '<line x1="' + pad.left + '" y1="' + y + '" x2="' + (width - pad.right) + '" y2="' + y +
        '" stroke="var(--usage-grid)" stroke-width="1"></line>';
    }
    var ticks = points.length <= 1 ? [0] : [0, Math.floor((points.length - 1) / 2), points.length - 1];
    var labels = ticks.filter(function (v, i, a) { return a.indexOf(v) === i; }).map(function (index) {
      return '<text x="' + requestPoints[index].x + '" y="' + (height - 8) +
        '" text-anchor="middle" font-size="10" fill="var(--subtle)">' + esc(points[index].label) + '</text>';
    }).join("");
    var hitWidth = innerWidth / Math.max(1, points.length - 1);
    var hits = points.map(function (point, index) {
      return '<rect class="usage-trend-hit" data-usage-index="' + index + '" x="' +
        Math.max(pad.left, requestPoints[index].x - hitWidth / 2) + '" y="' + pad.top +
        '" width="' + Math.max(4, hitWidth) + '" height="' + innerHeight + '" fill="transparent"></rect>';
    }).join("");
    return '<div class="usage-trend-card">' + cardHead +
      '<div class="usage-trend-wrap"><svg class="usage-trend" viewBox="0 0 ' + width + ' ' + height +
      '" preserveAspectRatio="none">' + grid +
      '<path d="' + usageAreaPath(tokenPoints, height - pad.bottom) + '" fill="#10b981" fill-opacity="0.08" stroke="none"></path>' +
      '<path d="' + usageAreaPath(requestPoints, height - pad.bottom) + '" fill="#3b82f6" fill-opacity="0.08" stroke="none"></path>' +
      '<path d="' + monotoneUsagePath(tokenPoints) + '" fill="none" stroke="#10b981" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"></path>' +
      '<path d="' + monotoneUsagePath(requestPoints) + '" fill="none" stroke="#3b82f6" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"></path>' +
      labels + hits + '</svg><div class="usage-tooltip" id="usage-tooltip"></div></div>' +
      '<div class="usage-trend-data" hidden>' + JSON.stringify(points) + '</div></div>';
  }

  /* ── Tab 6: Mihomo 代理 ── */
  function renderGwProxy() {
    var overview = cache.mihomoOverview;
    var airports = cache.mihomoAirports;
    if (!overview && !airports) return renderLoading("正在读取 Mihomo 代理状态…");
    var instances = (overview && overview.instances) || [];
    var activeId = ui.mihomoInstance;
    if (!activeId || !instances.some(function (item) { return item.id === activeId; })) {
      activeId = (instances[0] && instances[0].id) || "core";
    }
    var instance = instances.find(function (item) { return item.id === activeId; });
    var airport = ((airports && airports.instances) || []).find(function (item) { return item.id === activeId; });
    var out = '<div class="proxy-instance-tabs">' + instances.map(function (item) {
      return '<button class="chip ' + (item.id === activeId ? "active" : "") +
        '" data-mihomo-instance="' + esc(item.id) + '">' + esc(item.name || item.id) + '</button>';
    }).join("") + '</div>';
    if (!instance) return out + '<div class="warnbox">代理实例暂时不可用</div>';
    if (instance.available === false) {
      return out + '<div class="warnbox">' + esc(instance.error || "该实例暂时不可达") + '</div>';
    }
    out += card('<div class="row between"><span class="card-title">Mihomo ' + esc(instance.version || "--") + '</span>' +
      '<button class="btn small" data-mihomo-panel="1">管理面板</button></div>' +
      '<div class="caption muted mt-1">' + esc(instance.mode || "--") + ' · 混合端口 ' + esc(instance.mixed_port || "--") + '</div>' +
      '<div class="proxy-metrics mt-3">' +
      '<div class="tile"><div class="v">' + n(instance.connections, 0) + '</div><div class="k">连接</div></div>' +
      '<div class="tile"><div class="v green-text">' + rateShort(instance.download_speed) + '</div><div class="k">下行</div></div>' +
      '<div class="tile"><div class="v amber-text">' + rateShort(instance.upload_speed) + '</div><div class="k">上行</div></div>' +
      '</div>');
    var groups = instance.groups || [];
    var selectors = groups.filter(function (group) { return group.type === "Selector"; });
    var mobile = window.matchMedia("(max-width: 560px)").matches;
    var visibleSelectors = mobile && !ui.proxyGroupsExpanded ? selectors.slice(0, 6) : selectors;
    var groupSummary = '<div class="row between"><span class="font-medium">首个策略组</span>' +
      '<span class="tiny accent-text">' + esc((selectors[0] && selectors[0].now) || "-") + '</span></div>' +
      '<div class="tiny subtle mt-1">' + selectors.length + ' 个 Selector 策略组</div>';
    var groupDetail = '<div class="row between"><span class="card-title">策略组</span>' +
      '<span class="tiny subtle">' + selectors.length + ' 个可切换</span></div><div class="spacer-h"></div>' +
      (selectors.length ? visibleSelectors.map(function (group) {
          var openKey = activeId + ":" + group.name;
          var open = !!ui.proxyGroups[openKey];
          return '<div class="proxy-group">' +
            '<button class="proxy-group-head" data-mihomo-group="' + esc(group.name) + '">' +
            '<span class="grow"><span class="font-medium wrap-text">' + esc(group.name) + '</span>' +
            '<span class="tiny subtle wrap-text">' + esc(group.type) + ' · 当前 ' + esc(group.now || "-") + '</span></span>' +
            '<span class="tiny accent-text proxy-toggle">' + (open ? "收起" : "切换") + '</span></button>' +
            (open ? '<div class="proxy-nodes">' + (group.all || []).map(function (target) {
              var current = target === group.now;
              return '<button class="proxy-node ' + (current ? "current" : "") + '" data-mihomo-select="' +
                esc(group.name) + '" data-mihomo-target="' + esc(target) + '"' + (current ? " disabled" : "") + '>' +
                '<span class="wrap-text">' + esc(target) + '</span>' +
                (current ? '<span class="tiny accent-text">当前</span>' : '') + '</button>';
            }).join("") + '</div>' : '') + '</div>';
        }).join("") +
        (mobile && selectors.length > visibleSelectors.length
          ? '<div class="row between mt-3"><span class="tiny subtle">显示前 ' + visibleSelectors.length +
            ' / ' + selectors.length + ' 个策略组</span><button class="btn small" data-proxy-expand="1">显示全部</button></div>'
          : mobile && ui.proxyGroupsExpanded && selectors.length > 6
            ? '<div class="row between mt-3"><span class="tiny subtle">已显示全部 ' + selectors.length +
              ' 个策略组</span><button class="btn small" data-proxy-expand="0">收起</button></div>'
            : '')
        : '<div class="empty caption">暂无 Selector 策略组</div>');
    var airportSummary = airport && (airport.items || []).length
      ? '<div class="row between"><span class="font-medium">机场流量</span><span class="tiny subtle">' +
        (airport.items || []).length + ' 组</span></div><div class="tiny subtle mt-1">' +
        esc((airport.items[0] || {}).name || "-") + ' · 剩余 ' +
        (Math.round(((airport.items[0] || {}).remaining_gb || 0) * 10) / 10) + ' GB</div>'
      : '<div class="tiny subtle">' + esc((airport && airport.error) || "暂无机场流量数据") + '</div>';
    out += '<div class="proxy-layout">' +
      uiSection("策略组", groupSummary, "proxy-groups-" + activeId, function () { return groupDetail; }, true) +
      uiSection("机场看板", airportSummary, "proxy-airport-" + activeId, function () { return renderMihomoAirport(airport); }, true) +
      '</div>';
    return out;
  }

  function renderMihomoAirport(instance) {
    if (!instance || !(instance.items || []).length) {
      return card('<div class="card-title">机场看板</div><div class="empty caption">' +
        esc((instance && instance.error) || "暂无机场流量数据") + '</div>');
    }
    return card('<div class="row between"><span class="card-title">机场看板</span>' +
      '<span class="tiny subtle">每 ' + n(instance.history_seconds, 5) + ' 秒采样</span></div><div class="spacer-h"></div>' +
      instance.items.map(function (item) {
        var color = item.level === "critical" ? "var(--red)" : item.level === "warning" ? "var(--amber)" : "var(--green)";
        return '<div class="mb-3"><div class="row between baseline"><span class="font-medium wrap-text">' + esc(item.name) + '</span>' +
          '<span class="tiny" style="color:' + color + '">' + (Math.round((item.remaining_gb || 0) * 10) / 10) + ' / ' +
          Math.round(item.total_gb || 0) + ' GB</span></div>' +
          '<div class="bar"><i style="width:' + Math.max(0, Math.min(100, 100 - (item.used_percent || 0))) +
          '%;background:' + color + '"></i></div>' +
          '<div class="tiny subtle mt-1">已用 ' + Math.round(item.used_percent || 0) + '% · 重置 ' +
          esc(item.reset_days == null ? "-" : item.reset_days + "天") + '</div></div>';
      }).join("") + renderMihomoTraffic(instance.history || []));
  }

  function renderMihomoTraffic(points) {
    if (points.length < 2) return '<div class="empty caption">速率曲线采样中，等待下一轮数据。</div>';
    var max = Math.max.apply(null, points.map(function (point) { return Math.max(Number(point.up || 0), Number(point.down || 0)); }).concat([1]));
    return '<div class="traffic-pair" aria-label="代理上下行速率趋势">' + points.slice(-36).map(function (point) {
      var up = Math.max(2, Number(point.up || 0) / max * 30);
      var down = Math.max(2, Number(point.down || 0) / max * 30);
      return '<i class="down" style="height:' + down.toFixed(1) + 'px"></i>' +
        '<i class="up" style="height:' + up.toFixed(1) + 'px"></i>';
    }).join("") + '</div><div class="legend tiny subtle"><span>绿色 下行</span><span>橙色 上行</span></div>';
  }

  /* ── 共用：渠道余额 + WorkBuddy ── */
  function renderGwBalance(bal) {
    var chs2 = bal.channels || [];
    var out = card('<div class="row between"><span class="card-title">渠道余额</span>' +
      '<span class="tiny subtle">' + esc(bal.updated_at || "--") + '</span></div>' +
      '<div class="spacer-h"></div>' +
      '<div class="tile-grid"><div class="tile"><div class="v">$' + (bal.total != null ? bal.total.toFixed(2) : "--") +
      '</div><div class="k">合计</div></div>' +
      chs2.map(function (c) {
        var clickable = c.name === "pipio" || c.name === "pipio2";
        return '<div class="tile' + (clickable ? ' pipio-balance-tile clickable' : '') + '"' +
          (clickable ? ' data-pipio="' + esc(c.name) + '" title="点击查看使用详情"' : '') + '><div class="v">$' + (c.usd != null ? c.usd.toFixed(2) : "--") + '</div>' +
          '<div class="k">' + esc(c.name) + (c.today_used_usd != null ? ' · 今日 $' + c.today_used_usd.toFixed(3) : '') + '</div></div>';
      }).join("") + '</div>');
    if (cache.gwPipioDetail) out += renderPipioDetail(cache.gwPipioDetail);
    var wb = bal.workbuddy;
    if (wb) {
      var accountRows = (wb.accounts || []).map(function (a) {
        var st = a.disabled ? ["off", "停用"] : a.cooling ? ["warn", "冷却"] : ["on", "正常"];
        var cap = a.daily_cap;
        var capText = cap == null ? "基线缺失" : cap === 0 ? "0（不计算百分比）" : n(cap, "--");
        var pctText = (typeof a.credits === "number" && typeof cap === "number" && isFinite(cap) && cap > 0)
          ? ' · 剩余 ' + Math.round(a.credits * 100 / cap) + '%'
          : cap === 0 ? ' · 百分比不可用' : ' · 基线缺失';
        return '<div class="list-row">' +
          '<span class="grow"><span class="font-medium">' + esc(maskName(a.nickname)) + '</span>' +
          '<div class="tiny subtle">额度 ' + n(a.credits, "--") + ' / ' + capText + pctText +
          ' · 成功 ' + n(a.success_count, 0) + ' · 今日 ' + n(a.today_used, 0) + ' · 熔断 ' + n(a.breaker_fails, 0) + '</div></span>' +
          '<span class="pill ' + st[0] + '">' + st[1] + '</span></div>';
      }).join("");
      out += card('<div class="row between"><span class="card-title">WorkBuddy 账号池</span>' +
        '<span class="tiny subtle">共 ' + wb.total + ' 个</span></div>' +
        '<div class="row gap-2 mt-2 wrap-flex">' +
        '<span class="pill on">健康 ' + wb.healthy + '</span>' +
        (wb.cooling ? '<span class="pill warn">冷却 ' + wb.cooling + '</span>' : '') +
        (wb.disabled ? '<span class="pill off">停用 ' + wb.disabled + '</span>' : '') +
        '<span class="pill ' + ((wb.realm_servable || {}).global ? "on" : "warn") + '">global ' +
        ((wb.realm_servable || {}).global ? "可用" : "不可用") + '</span>' +
        '<span class="tiny subtle ml-auto font-semibold">余额汇总 ' +
        (wb.accounts || []).reduce(function (sum, a) { return sum + (typeof a.credits === 'number' ? a.credits : 0); }, 0).toLocaleString() +
        '</span></div>' +
        '<div class="row between mt-3"><span class="tiny subtle">账号明细</span>' +
        '<button class="btn small" data-workbuddy-expand="' + (ui.workbuddyExpanded ? "0" : "1") + '">' +
        (ui.workbuddyExpanded ? "收起" : "展开账号池") + '</button></div>' +
        (ui.workbuddyExpanded ? accountRows : ''));
    }
    return out;
  }

  /* ── 共用：探针状态 ── */
  function renderGwProbe(probe) {
    var s = probe.summary || {};
    return card('<div class="row between"><span class="card-title">探针状态</span>' +
      '<span class="pill ' + (probe.probe_status === "ok" ? "on" : probe.probe_status === "degraded" ? "warn" : "off") + '">' +
      esc(probe.probe_status) + '</span></div>' +
      '<div class="row gap-2 mt-2 wrap-flex">' +
      '<span class="pill neutral">总计 ' + n(s.total, 0) + '</span>' +
      '<span class="pill on">健康 ' + n(s.healthy, 0) + '</span>' +
      '<span class="pill warn">降级 ' + n(s.degraded, 0) + '</span>' +
      '<span class="pill off">失败 ' + n(s.failed, 0) + '</span>' +
      '</div>' +
      '<div class="tiny subtle mt-2">最近检查 ' + esc(ago(probe.checked_at)) +
      (probe.is_stale ? ' · 数据已过期' : '') + '</div><div class="spacer-h"></div>' +
      (probe.items || []).slice(0, 14).map(function (it) {
        var c = it.status === "healthy" ? "on" : it.status === "failed" ? "off" : "warn";
        return '<div class="list-row">' +
          '<span class="dot ' + (it.status === "healthy" ? "g" : it.status === "failed" ? "r" : "a") + '"></span>' +
          '<span class="grow"><span class="font-medium">' + esc(it.name) + '</span>' +
          '<div class="tiny subtle wrap-text">' + esc(it.evidence || it.provider || "") +
          (it.consecutive_failures ? ' · 连续失败 ' + it.consecutive_failures : '') + '</div></span>' +
          '<span class="pill ' + c + '">' + esc(it.status) + '</span></div>';
      }).join(""));
  }

  function policyModeLabel(m) {
    return m === "preferred" ? "最优先" : m === "exclusive" ? "仅使用" : "正常路由";
  }

  /* ───────────── 页面：工具 ───────────── */

  function toolEntries(host) {
    var label = HOST_LABEL[host];
    var list = [
      { icon: "cpu", title: "趋势", sub: label + " 资源曲线", route: "trends/" + host },
      { icon: "flame", title: "温度", sub: label + " 传感器", route: "temperature/" + host },
      { icon: "list", title: "容器", sub: host === "nas" ? "NAS Docker 状态与日志" : label + " 容器清单", route: "containers/" + host },
      { icon: "gateway", title: "服务", sub: label + " 服务探活", route: "services/" + host },
      { icon: "term", title: "服务器", sub: "进入" + label + "详情", route: host }
    ];
    if (host === "tencent") list.splice(1, 1);
    if (host === "nas") list.push({ icon: "disk", title: "磁盘", sub: "NAS 存储卷", route: "disks" });
    return list;
  }

  function renderTools(sub) {
    if (sub === "downloader") return renderDownloaderStatus();
    var host = sub && HOST_LABEL[sub] ? sub : "nas";
    var out = '<div class="host-picker">' + Object.keys(HOST_LABEL).map(function (h) {
      return '<button class="chip ' + (h === host ? "active" : "") + '" data-tabhost="' + h + '">' + esc(HOST_LABEL[h]) + '</button>';
    }).join("") + '</div><div class="grid grid-auto">' +
      toolEntries(host).map(function (t) {
        return '<div class="card click tight mb-0" data-go="' + t.route + '">' +
          '<span class="accent-text">' + icon(t.icon, 18) + '</span>' +
          '<div class="mt-3 font-semibold text-body">' + esc(t.title) + '</div>' +
          '<div class="tiny muted mt-1">' + esc(t.sub) + '</div></div>';
      }).join("") + '</div>';
    out += '<div class="spacer-h"></div><div class="divider-pt"></div><div class="grid grid-auto">' +
      '<div class="card click tight" data-go="ask"><span class="accent-text">' + icon("chat", 18) + '</span>' +
      '<div class="mt-3 font-semibold text-body">知识问答</div><div class="tiny muted">AnythingLLM 知识库</div></div>' +
      '<div class="card click tight" data-go="knowledge-archive"><span class="accent-text">' + icon("link", 18) + '</span>' +
      '<div class="mt-3 font-semibold text-body">知识归档</div><div class="tiny muted">链接与文件收藏</div></div>' +
      '</div>';
    return out;
  }

  function renderDownloaderStatus() {
    return card('<div class="row"><span class="icon-badge">' + icon("list", 19) + '</span>' +
      '<div class="grow ml-2"><div class="card-title">下载器</div>' +
      '<div class="caption muted">Transmission / qBittorrent 任务管理</div></div>' +
      '<span class="pill warn">待接入</span></div>' +
      '<div class="warnbox mt-3">当前后端没有下载器代理接口。Web 端已保留入口，但不会伪造列表、速度或任务操作。</div>' +
      '<div class="kv mt-3">' +
      '<div class="kv-item"><span>App 端能力</span><span>列表、任务、暂停继续、磁力添加</span></div>' +
      '<div class="kv-item"><span>Web 端状态</span><span>等待后端接口</span></div>' +
      '<div class="kv-item"><span>数据边界</span><span>未读取真实下载器数据</span></div>' +
      '</div>');
  }

  /* ───────────── 页面：安全 ───────────── */

  function renderPipioDetail(d) {
    var rate = 500000;
    function usd(q) { return q == null ? "--" : "$" + (Number(q) / rate).toFixed(2); }
    var rawDaily = d.daily || [];
    var dailyMax = Math.max.apply(null, rawDaily.concat([1]));
    var chartW = 720, chartH = 190, baseY = 135, slot = chartW / Math.max(rawDaily.length, 1);
    var bars = rawDaily.map(function (q, i, a) {
      var date = new Date();
      date.setHours(0, 0, 0, 0);
      date.setDate(date.getDate() - (a.length - 1 - i));
      var label = String(date.getMonth() + 1).padStart(2, "0") + "/" + String(date.getDate()).padStart(2, "0");
      var h = Math.max(4, Math.round(Number(q || 0) / dailyMax * 98));
      var x = Math.round(i * slot + slot * 0.22), w = Math.max(18, Math.round(slot * 0.56));
      return '<g><title>' + label + ' ' + usd(q) + '</title>' +
        '<rect x="' + x + '" y="' + (baseY - h) + '" width="' + w + '" height="' + h + '" rx="5" fill="' + (i === a.length - 1 ? '#5b7cfa' : '#b9c7ff') + '" />' +
        '<text x="' + (x + w / 2) + '" y="' + Math.max(12, baseY - h - 7) + '" text-anchor="middle" font-size="11" fill="#667085">' + usd(q) + '</text>' +
        '<text x="' + (x + w / 2) + '" y="' + (baseY + 22) + '" text-anchor="middle" font-size="11" fill="#667085">' + label + '</text></g>';
    }).join('');
    var daily = '<div class="pipio-usage-chart mt-2">' +
      '<svg viewBox="0 0 ' + chartW + ' ' + chartH + '" width="100%" height="190" role="img" aria-label="近 7 天消耗柱状图" preserveAspectRatio="none">' +
      '<line x1="0" y1="' + baseY + '" x2="' + chartW + '" y2="' + baseY + '" stroke="#d9dee8" />' + bars + '</svg></div>';
    var tok = (d.tokens || []).map(function (x) {
      return '<div class="list-row"><span class="grow">' + esc(x.name) + '</span><span>' + usd(x.quota) + ' · ' + n(x.calls, 0) + ' 次</span></div>';
    }).join("");
    return card('<div class="row between"><span class="card-title">' + esc(d.name) + ' 使用详情</span>' +
      '<button class="btn small pipio-detail-close">关闭</button></div>' +
      '<div class="tiny subtle mt-2">' + esc(d.display || '') + ' · 更新于 ' + esc(d.updated_at || '--') + '</div>' +
      (d.ok === false ? '<div class="warnbox mt-3">' + esc(d.error || '数据不可用') + '</div>' :
        '<div class="tile-grid mt-3"><div class="tile"><div class="v">' + usd(d.today) + '</div><div class="k">今日消耗</div></div>' +
        '<div class="tile"><div class="v">' + usd(d.yesterday) + '</div><div class="k">昨日消耗</div></div>' +
        '<div class="tile"><div class="v">' + usd(d.week) + '</div><div class="k">近 7 天</div></div>' +
        '<div class="tile"><div class="v">' + usd(d.used_total) + '</div><div class="k">累计已用</div></div></div>' +
        '<div class="tiny subtle mt-3">今日 ' + n(d.today_calls, 0) + ' 次调用 · ' + n(d.today_errors, 0) + ' 次错误</div>' +
        '<div class="caption mt-3">近 7 天消耗</div>' + daily +
        (tok ? '<div class="caption mt-3">今日令牌归因</div>' + tok : '')));
  }

  function renderSecurity(sub) {
    var host = sub && HOST_LABEL[sub] ? sub : "nas";
    var out = '<div class="host-picker">' + Object.keys(HOST_LABEL).map(function (h) {
      return '<button class="chip ' + (h === host ? "active" : "") + '" data-tabhost="' + h + '">' + esc(HOST_LABEL[h]) + '</button>';
    }).join("") + '</div>';

    if (["nas", "aliyun", "tencent"].indexOf(host) < 0) return out + '<div class="warnbox">尚未接入独立安全采集器，Glances 系统监控可正常使用。</div>';
    var d = cache["sec_" + host];
    if (!d) return out + renderLoading("正在加载安全数据…");
    if (d.status === "unavailable") {
      return out + '<div class="warnbox">' + esc(d.message || (HOST_LABEL[host] + " 安全采集器尚未接入")) + '</div>';
    }

    if (host === "nas") {
      var sf = d.safeline || {};
      var top = sf.top_ips || [];
      var mx = Math.max.apply(null, top.map(function (x) { return x.count || 0; }).concat([1]));
      out += card('<div class="row between"><span class="card-title">雷池 WAF</span>' +
        '<span class="pill ' + (sf.status === "online" ? "on" : "off") + '">' + (sf.status === "online" ? "在线" : "离线") + '</span></div>' +
        '<div class="spacer-h"></div><div class="tile-grid">' +
        '<div class="tile"><div class="v">' + n(sf.today_requests, 0) + '</div><div class="k">今日请求</div></div>' +
        '<div class="tile"><div class="v">' + n(sf.today_ips, 0) + '</div><div class="k">今日 IP</div></div>' +
        '<div class="tile"><div class="v amber-text">' + n(sf.today_anomaly, 0) + '</div><div class="k">今日异常</div></div>' +
        '</div><div class="spacer-h"></div><div class="tile-grid">' +
        '<div class="tile"><div class="v">' + n(sf.week_requests, 0) + '</div><div class="k">本周请求</div></div>' +
        '<div class="tile"><div class="v">' + n(sf.week_ips, 0) + '</div><div class="k">本周 IP</div></div>' +
        '<div class="tile"><div class="v">' + n(sf.today_internal, 0) + '</div><div class="k">今日内网</div></div>' +
        '</div>');

      if (top.length) {
        out += card('<div class="card-title">今日访问地址前 5 名</div><div class="spacer-h"></div>' +
          top.map(function (i) {
            return '<div class="mb-3">' +
              '<div class="row between"><span class="mono tiny wrap-text">' + esc(i.ip) + '</span>' +
              '<span class="tiny muted">' + esc(i.geo || "") + '</span></div>' +
              '<div class="bar bar-sm"><i style="width:' + ((i.count || 0) / mx * 100) + '%;background:var(--accent)"></i></div>' +
              '<div class="row between tiny subtle mt-1"><span>' + n(i.count, 0) + ' 次</span>' +
              '<span>异常 ' + n(i.errors, 0) + '</span></div></div>';
          }).join(""));
      }

      var temps = d.hdd_temps || {};
      var tk = Object.keys(temps);
      if (tk.length) {
        out += card('<div class="card-title">硬盘温度</div><div class="spacer-h"></div>' +
          '<div class="tile-grid">' + tk.map(function (k) {
            var v = temps[k];
            var c = v >= 50 ? "var(--red)" : v >= 45 ? "var(--amber)" : "var(--green)";
            return '<div class="tile"><div class="v" style="color:' + c + '">' + v + '°C</div><div class="k">' + esc(k) + '</div></div>';
          }).join("") + '</div>');
      }

      var live = cache.sec_live;
      if (live && (live.rows || []).length) {
        out += card('<div class="row between"><span class="card-title">最近访问 IP</span>' +
          '<span class="tiny subtle">' + n(live.total, 0) + ' 次 · ' + n(live.unique, 0) + ' IP</span></div>' +
          '<div class="tiny subtle mt-1">当前为近 5 分钟实时窗口</div><div class="spacer-h"></div>' +
          live.rows.slice(0, 12).map(function (r) {
            return '<div class="list-row">' +
              '<span class="grow"><span class="mono text-xs">' + esc(r.ip) + '</span>' +
              '<div class="tiny subtle wrap-text">' + esc(r.geo || "") + '</div></span>' +
              '<span class="caption">' + n(r.count, 0) + ' 次' +
              (r.errors ? ' · <span class="amber-text">异常 ' + r.errors + '</span>' : '') + '</span></div>';
          }).join(""));
      }
      return out;
    }

    var items = [];
    function add(k, v) { if (v !== undefined && v !== null && v !== "") items.push([k, v]); }
    if (host === "aliyun") {
      add("firewalld", (d.firewalld || {}).active ? "运行中 · 默认区域 " + ((d.firewalld || {}).default_zone || "-") : "未运行");
      add("Fail2ban", (d.fail2ban || {}).service_active ? "运行中 · 当前封禁 " + n((d.fail2ban || {}).currently_banned, 0) +
        " · 累计 " + n((d.fail2ban || {}).total_banned, 0) : "未运行");
      add("SSH", (d.ssh || {}).service_active ? "运行中 · MaxAuthTries " + n((d.ssh || {}).maxauthtries, "-") : "未运行");
      add("云安全中心", (d.aegis || {}).aliyundun_active ? "已启用（含监控）" : "未启用");
      add("Coraza WAF", (d.coraza || {}).running ? "容器 " + ((d.coraza || {}).container || "coraza-waf") + " 运行中" : "未运行");
    } else {
      add("UFW", (d.firewalld || {}).active ? "运行中 · 入站 " + ((d.firewalld || {}).default_incoming || "-") +
        " / 出站 " + ((d.firewalld || {}).default_outgoing || "-") : "未运行");
      add("Fail2ban", (d.fail2ban || {}).service_active ? "运行中 · 当前封禁 " + n((d.fail2ban || {}).currently_banned, 0) : "未运行");
      add("SSH", (d.ssh || {}).service_active ? "运行中 · MaxAuthTries " + n((d.ssh || {}).maxauthtries, "-") : "未运行");
      add("auditd", (d.aegis || {}).auditd_active ? "运行中" : "未运行");
      add("earlyoom", (d.aegis || {}).earlyoom_active ? "运行中" : "未运行");
    }
    out += card('<div class="row between"><span class="card-title">' + esc(HOST_LABEL[host]) + ' 主机防护</span>' +
      '<span class="pill ' + (d.status === "ok" ? "on" : "warn") + '">' + (d.status === "ok" ? "已接入" : esc(d.status)) + '</span></div>' +
      '<div class="kv mt-3">' + items.map(function (kv) {
        return '<div class="kv-item"><span>' + esc(kv[0]) + '</span><span>' + esc(kv[1]) + '</span></div>';
      }).join("") + '</div>' +
      (d.collected_at ? '<div class="tiny subtle mt-3">采集时间 ' + esc(String(d.collected_at).substring(0, 19).replace("T", " ")) + '</div>' : ''));
    return out;
  }

  /* ───────────── 页面：详情 ───────────── */

  function renderHostDetail(host) {
    var d = cache["detail_" + host];
    if (!d) return renderLoading("正在加载 " + HOST_LABEL[host] + " 详情…");
    var sys = d.system || {};
    var cpu = sys.cpu || {}, mem = sys.memory || {}, disks = sys.disks || [], net = sys.network || [];
    var out = "";

    out += card('<div class="row between"><span class="card-title">' + esc(HOST_LABEL[host]) + ' 系统</span>' +
      '<span class="tiny subtle">开机 ' + esc(uptimeShort(sys.uptime || "")) + '</span></div>' +
      '<div class="spacer-h"></div><div class="tile-grid">' +
      usageTile("CPU", cpu.percent) + usageTile("内存", mem.percent) +
      '<div class="tile"><div class="v">' + (sys.load ? (Math.round(sys.load.min1 * 100) / 100) : "--") + '</div><div class="k">负载 1m</div></div>' +
      '</div>');

    if (cpu.cores) {
      out += card('<div class="kv">' +
        '<div class="kv-item"><span>CPU 核心</span><span>' + cpu.cores + '</span></div>' +
        '<div class="kv-item"><span>用户态</span><span>' + pct(cpu.user, "0%") + '</span></div>' +
        '<div class="kv-item"><span>IO 等待</span><span>' + pct(cpu.iowait, "0%") + '</span></div>' +
        '<div class="kv-item"><span>内存</span><span>' + gb(mem.used_gb) + ' / ' + gb(mem.total_gb) + ' GB</span></div>' +
        '<div class="kv-item"><span>可用内存</span><span>' + gb(mem.available_gb) + ' GB</span></div>' +
        '<div class="kv-item"><span>Load 5/15</span><span>' + (sys.load ? (Math.round(sys.load.min5 * 100) / 100 + " / " + Math.round(sys.load.min15 * 100) / 100) : "--") + '</span></div>' +
        '</div>');
    }

    if (disks.length) {
      out += card('<div class="card-title">磁盘（' + disks.length + '）</div><div class="spacer-h"></div><div class="scroll-x"><table>' +
        '<thead><tr><th>挂载点</th><th>设备</th><th>已用 / 容量</th><th class="num">占用</th></tr></thead><tbody>' +
        disks.map(function (dk) {
          return '<tr><td class="wrap-text">' + esc(dk.mount) + '</td>' +
            '<td class="tiny muted wrap-text">' + esc(dk.device) + '</td>' +
            '<td class="tiny">' + gb(dk.used_gb) + ' / ' + gb(dk.total_gb) + ' GB</td>' +
            '<td class="num font-semibold" style="color:' + usageColor(dk.percent) + '">' + pct(dk.percent, "0%") + '</td></tr>';
        }).join("") + '</tbody></table></div>');
    }

    if (net.length) {
      out += card('<div class="card-title">网络接口</div><div class="spacer-h"></div>' +
        net.slice(0, 8).map(function (i) {
          return '<div class="list-row"><span class="grow mono text-xs">' + esc(i.name) + '</span>' +
            '<span class="caption green-text">↓ ' + esc(rate(i.rx_bps)) + '</span>' +
            '<span class="caption amber-text">↑ ' + esc(rate(i.tx_bps)) + '</span></div>';
        }).join(""));
    }

    if (host === "nas") {
      var docker = d.docker || {};
      out += card('<div class="row between"><span class="card-title">Docker</span>' +
        '<span class="tiny subtle">运行 ' + n(docker.running, 0) + ' / 共 ' + n(docker.total, 0) +
        ' · 健康检查 ' + n(docker.healthy, 0) + '</span></div>' +
        '<div class="spacer-h"></div>' +
        (docker.containers || []).slice(0, 30).map(function (c) {
          return '<div class="list-row"><span class="dot ' + (c.status === "running" ? "g" : "r") + '"></span>' +
            '<span class="grow wrap-text">' + esc(c.name) + '<div class="tiny subtle">' + esc(c.image) +
            (c.ports ? ' · 端口 ' + esc(c.ports) : '') + '</div></span>' +
            '<span class="pill ' + (c.healthy ? "on" : "neutral") + '">' + esc(c.status) + '</span></div>';
        }).join(""));

      var ha = d.ha || {};
      var volumes = ha.volumes || [];
      if (volumes.length) {
        out += card('<div class="card-title">存储卷</div><div class="spacer-h"></div>' +
          volumes.map(function (v) {
            return '<div class="mb-3">' +
              '<div class="row between"><span class="font-medium">' + esc(v.friendly || v.name) + '</span>' +
              '<span class="tiny font-semibold" style="color:' + usageColor(v.percent) + '">' + pct(v.percent, "0%") + '</span></div>' +
              '<div class="bar bar-sm"><i style="width:' + Math.min(100, v.percent) + '%;background:' + usageColor(v.percent) + '"></i></div>' +
              '<div class="tiny subtle mt-1">' + esc(v.capacity) + '</div></div>';
          }).join(""));
      }

      var fans = ha.fans || {}, fk = Object.keys(fans);
      var ups = ha.ups, cab = ha.cabinet;
      if (fk.length || ups || cab) {
        out += card('<div class="card-title">硬件</div><div class="kv mt-3">' +
          fk.map(function (k) {
            return '<div class="kv-item"><span>' + esc(k) + '</span><span>PWM ' + esc(fans[k].pwm || "-") +
              ' · ' + esc(fans[k].mode || "-") + ' · ' + esc(fans[k].rpm || "0") + ' RPM</span></div>';
          }).join("") +
          (ups ? '<div class="kv-item"><span>UPS</span><span>' + esc(ups.status || "-") + ' · 电量 ' + esc(ups.charge || "-") +
            '% · 负载 ' + esc(ups.load || "-") + '%</span></div>' : '') +
          (cab ? '<div class="kv-item"><span>机柜</span><span>' + esc(cab.temperature || "-") + '°C · 湿度 ' +
            esc(cab.humidity || "-") + '% · ' + esc(cab.power || "-") + 'W</span></div>' : '') +
          '</div>');
      }

      var kuma = d.kuma || [];
      if (kuma.length) {
        out += card('<div class="card-title">Uptime Kuma</div><div class="spacer-h"></div>' +
          kuma.map(function (k) {
            return '<div class="list-row"><span class="dot ' + (k.status === "up" ? "g" : "r") + '"></span>' +
              '<span class="grow">' + esc(k.name) + '</span>' +
              '<span class="caption muted">' + (k.latency_ms ? k.latency_ms + " ms" : "--") + '</span>' +
              '<span class="pill ' + (k.status === "up" ? "on" : "off") + '">' + (k.status === "up" ? "正常" : "异常") + '</span></div>';
          }).join(""));
      }
    }

    var svcs = d.services || {};
    if (Object.keys(svcs).length) {
      out += card('<div class="card-title">服务探活</div><div class="spacer-h"></div>' +
        Object.keys(svcs).map(function (k) { return serviceRow(k, svcs[k]); }).join(""));
    }
    return out;
  }

  function renderRouter() {
    var r = cache.router;
    if (!r) return renderLoading("正在加载路由器数据…");
    var online = r.status === "up" && r.internet !== "down";
    var out = card('<div class="row between"><span class="card-title">爱快路由器</span>' +
      '<span class="pill ' + (online ? "on" : "off") + '">' + (online ? "在线" : "离线") + '</span></div>' +
      '<div class="kv mt-3">' +
      '<div class="kv-item"><span>网关</span><span>' + esc(r.address) + '</span></div>' +
      '<div class="kv-item"><span>系统版本</span><span>iKuai ' + esc(r.version) + '</span></div>' +
      '<div class="kv-item"><span>在线设备</span><span>' + n(r.online_user_count, 0) + ' / 共 ' + n(r.total_devices, 0) + '</span></div>' +
      '<div class="kv-item"><span>连接数</span><span>' + n(r.connections, 0) + '</span></div>' +
      '<div class="kv-item"><span>内存</span><span>' + gb(r.mem_usage) + '% · 可用 ' + gb(r.mem_available) + ' / ' + gb(r.mem_total) + ' GB</span></div>' +
      '<div class="kv-item"><span>运行时长</span><span>' + Math.floor((r.uptime || 0) / 86400) + ' 天</span></div>' +
      '</div>' +
      '<div class="spacer-h"></div>' +
      '<div class="row gap-3">' + gauge(r.cpu_usage, "CPU") + gauge(r.mem_usage, "内存") +
      '<div class="gauge"><div class="gv ' + (r.cpu_temp > 70 ? "red-text" : "amber-text") + '">' +
      (r.cpu_temp > 0 ? r.cpu_temp + "°C" : "---") + '</div><div class="k tiny muted">温度</div></div>' +
      '</div>');

    var wans = r.wans || [];
    if (wans.length) {
      out += card('<div class="card-title">WAN 线路</div><div class="spacer-h"></div>' + wans.map(function (w) {
        return '<div class="list-row">' +
          '<span class="dot ' + (w.internet === "up" ? "g" : "r") + '"></span>' +
          '<span class="grow"><span class="font-medium">' + esc((w.name || "").toUpperCase()) + '</span>' +
          '<div class="tiny subtle mono wrap-text">' + esc(w.ip || "-") + ' · 网关 ' + esc(w.gateway || "-") + ' · ' + n(w.speed, 0) + 'M</div></span>' +
          '<span class="caption green-text">↓ ' + esc(rate(w.download_speed)) + '</span>' +
          '<span class="caption amber-text">↑ ' + esc(rate(w.upload_speed)) + '</span></div>';
      }).join(""));
    }

    out += card('<div class="row between"><span class="card-title">宽带测速</span>' +
      '<button class="btn small primary" id="speedtest">' + (r.speedtest_running ? "测速中…" : "开始测速") + '</button></div>' +
      '<div class="kv mt-3">' +
      '<div class="kv-item"><span>下载（上次）</span><span>' + n(r.speedtest_download, "--") + ' Mbps</span></div>' +
      '<div class="kv-item"><span>上传（上次）</span><span>' + n(r.speedtest_upload, "--") + ' Mbps</span></div>' +
      '<div class="kv-item"><span>状态</span><span>' + esc(r.speedtest_status || "未测速") + '</span></div>' +
      '</div><div class="tiny subtle mt-2">测速约需 60 秒，期间上行带宽会被占满</div>');

    var devices = r.devices || [];
    if (devices.length) {
      out += card('<div class="card-title">在线设备（' + devices.length + '，ARP 活跃）</div><div class="spacer-h"></div>' +
        '<div class="scroll-x"><table><thead><tr><th>名称</th><th>IP</th><th>MAC</th></tr></thead><tbody>' +
        devices.map(function (d) {
          return '<tr><td class="wrap-text">' + esc(d.name || "-") + '</td><td class="mono tiny">' + esc(d.ip) +
            '</td><td class="mono tiny subtle">' + esc(d.mac || "-") + '</td></tr>';
        }).join("") + '</tbody></table></div>');
    }
    return out;
  }

  function renderDisks() {
    var ov = cache.overview;
    if (!ov) return renderLoading("正在加载存储数据…");
    var out = "";
    Object.keys(HOST_LABEL).forEach(function (h) {
      var host = ov[h];
      if (!host || !(host.disks || []).length) return;
      var title = h === "nas" ? "NAS 存储卷" : HOST_LABEL[h] + " ECS 存储";
      var disks = h === "nas" ? host.disks.filter(function (d) { return /^\/vol/.test(d.mount); }) : host.disks;
      if (!disks.length) return;
      out += card('<div class="card-title">' + esc(title) + '（' + disks.length + '）</div><div class="spacer-h"></div>' +
        disks.map(function (d) {
          return '<div class="mb-3">' +
            '<div class="row between"><span class="font-medium">' + esc(d.mount) + '</span>' +
            '<span class="tiny font-semibold" style="color:' + usageColor(d.percent) + '">' + pct(d.percent, "0%") + '</span></div>' +
            '<div class="bar tall"><i style="width:' + Math.min(100, d.percent) + '%;background:' + usageColor(d.percent) + '"></i></div>' +
            '<div class="row between tiny subtle mt-1">' +
            '<span>已用 ' + gb(d.used_gb) + ' / 共 ' + gb(d.total_gb) + ' GB</span>' +
            (d.percent >= 90 ? '<span class="red-text">建议清理</span>' : '') + '</div></div>';
        }).join(""));
    });
    return out || '<div class="warnbox">暂无磁盘数据</div>';
  }

  function renderTrends(host) {
    var d = cache["trends_" + host];
    if (!d) return renderLoading("正在加载趋势数据…");
    var pts = d.points || [];
    if (!pts.length) return '<div class="warnbox">暂无趋势数据，等待采集…</div>';
    var step = Math.max(1, Math.floor(pts.length / 320));
    var sampled = pts.filter(function (_, i) { return i % step === 0; });
    function label(p) {
      var t = String(p.t || "");
      var m = t.match(/T(\d{2}:\d{2})/);
      if (m) return m[1];
      var t2 = Date.parse(t);
      if (!isNaN(t2)) return new Date(t2).toTimeString().substring(0, 5);
      return t.substring(11, 16);
    }
    var cpuSeries = [{ name: "CPU", color: "var(--accent)", points: sampled.map(function (p) { return { y: p.cpu, label: label(p) }; }) }];
    var memSeries = [{ name: "内存", color: "var(--purple)", points: sampled.map(function (p) { return { y: p.mem, label: label(p) }; }) }];
    var diskSeries = [{ name: "磁盘", color: "var(--green)", points: sampled.map(function (p) { return { y: p.disk, label: label(p) }; }) }];
    var netSeries = [
      { name: "下行", color: "var(--green)", points: sampled.map(function (p) { return { y: p.rx, label: label(p) }; }) },
      { name: "上行", color: "var(--amber)", points: sampled.map(function (p) { return { y: p.tx, label: label(p) }; }) }
    ];
    var durations = [60, 360, 1440];
    return '<div class="tabs">' + durations.map(function (m) {
      return '<button class="' + (d.minutes === m ? "active" : "") + '" data-trendmin="' + m + '">' + (m / 60 >= 1 ? (m / 60) + "h" : m + "m") + '</button>';
    }).join("") + '</div>' +
      card('<div class="card-title">CPU 使用率</div>' + lineChart(cpuSeries, { percent: true })) +
      card('<div class="card-title">内存使用率</div>' + lineChart(memSeries, { percent: true })) +
      card('<div class="card-title">磁盘占用</div>' + lineChart(diskSeries, { percent: true })) +
      card('<div class="card-title">网络流量</div>' + lineChart(netSeries, {}));
  }

  function renderTemperature(host) {
    var d = cache["temp_" + host];
    if (!d) return renderLoading("正在加载温度趋势…");
    var disks = d.disks || {};
    var keys = Object.keys(disks);
    if (!keys.length) return '<div class="warnbox">暂无温度趋势数据，等待采集…</div>';
    var colors = ["var(--accent)", "var(--green)", "var(--amber)", "var(--purple)", "var(--red)", "#16B1FF"];
    var series = keys.slice(0, 6).map(function (k, i) {
      var pts = disks[k] || [];
      var step = Math.max(1, Math.floor(pts.length / 300));
      return {
        name: k, color: colors[i % colors.length],
        points: pts.filter(function (_, j) { return j % step === 0; }).map(function (p) {
          var t = String(p.t || "");
          var m = t.match(/T(\d{2}:\d{2})/);
          return { y: p.temp, label: m ? m[1] : t.substring(11, 16) };
        })
      };
    });
    return card('<div class="card-title">硬盘温度（' + keys.length + ' 个传感器）</div>' + lineChart(series, { height: 260 }));
  }

  function renderContainers(host) {
    var d = cache["containers_" + host];
    var stats = cache["cstats_" + host];
    if (!d) return renderLoading("正在加载容器列表…");
    var statsMap = {};
    if (stats && stats.containers) stats.containers.forEach(function (c) { statsMap[c.name] = c; });
    var list = d.containers || [];
    if (!list.length) return '<div class="warnbox">' + esc(HOST_LABEL[host]) + ' 暂无容器数据</div>';

    var PER_PAGE = 15;
    var totalPages = Math.ceil(list.length / PER_PAGE);
    var page = Math.min(Math.max(1, parseInt(localStorage.getItem("nas_containers_page_" + host) || "1", 10) || 1), totalPages);
    var pageItems = list.slice((page - 1) * PER_PAGE, page * PER_PAGE);

    var pager = '';
    if (totalPages > 1) {
      pager = '<div class="row between mt-3"><span class="tiny subtle">第 ' + page + ' / ' + totalPages + ' 页 · 共 ' + list.length + ' 个</span>' +
        '<div class="row gap-2">' +
        (page > 1 ? '<button class="btn small" data-cpage="' + (page - 1) + '">上一页</button>' : '') +
        (page < totalPages ? '<button class="btn small" data-cpage="' + (page + 1) + '">下一页</button>' : '') +
        '</div></div>';
    }

    return card('<div class="row between"><span class="card-title">' + esc(HOST_LABEL[host]) + ' 容器</span>' +
      '<span class="tiny subtle">运行 ' + n(d.running, 0) + ' / 共 ' + n(d.total, 0) +
      (d.healthy ? ' · 健康 ' + d.healthy : '') + '</span></div>' +
      '<div class="spacer-h"></div>' +
      '<label class="field"><span>筛选</span><input type="text" id="cflt" placeholder="按名称或镜像过滤"></label>' +
      '<div id="clist">' + pageItems.map(function (c) {
        var st = statsMap[c.name];
        return '<div class="list-row" data-cname="' + esc(c.name) + '">' +
          '<span class="dot ' + (c.status === "running" ? "g" : "r") + '"></span>' +
          '<span class="grow wrap-text"><span class="font-medium">' + esc(c.name) + '</span>' +
          '<div class="tiny subtle wrap-text">' + esc(c.image) + (c.ports ? ' · 端口 ' + esc(c.ports) : '') +
          (st ? ' · CPU ' + (Math.round(st.cpu_percent * 10) / 10) + '% · 内存 ' + Math.round(st.memory_usage_mb) + ' MB' : '') +
          '</div></span>' +
          '<button class="btn small c-log" data-cname="' + esc(c.name) + '">日志</button></div>';
      }).join("") + '</div>' + pager);
  }

  function renderServices(host) {
    var d = cache["detail_" + host] || cache["nas"];
    if (!d) return renderLoading("正在加载服务探活…");
    var svcs = d.services || {};
    var keys = Object.keys(svcs);
    if (!keys.length) return '<div class="warnbox">' + esc(HOST_LABEL[host]) + ' 暂无服务探活数据</div>';
    return card('<div class="card-title">' + esc(HOST_LABEL[host]) + ' 服务</div><div class="spacer-h"></div>' +
      keys.map(function (k) { return serviceRow(k, svcs[k]); }).join(""));
  }

  /* ───────────── 页面：问答 / 归档 ───────────── */

  function renderAsk() {
    return card('<div class="card-title">知识问答</div>' +
      '<div class="caption muted mt-1">基于 AnythingLLM 运维知识库（nas-docker）回答，并列出引用来源</div>' +
      '<div class="spacer-h"></div>' +
      '<label class="field"><span>问题</span><textarea id="askq" placeholder="例如：MoviePilot 的部署配置是什么？"></textarea></label>' +
      '<button class="btn primary" id="askbtn">提问</button>' +
      '<div id="askout" class="mt-4"></div>');
  }

  function renderArchive() {
    return card('<div class="card-title">知识归档</div>' +
      '<div class="caption muted mt-1">把链接或文件交给 AnythingLLM 收藏归档（工作区 nas-docker）</div>' +
      '<div class="spacer-h"></div>' +
      '<label class="field"><span>页面链接</span><input type="text" id="arcurl" placeholder="粘贴 http(s) 链接"></label>' +
      '<button class="btn primary" id="archivebtn">归档链接</button>' +
      '<div class="spacer-h"></div><div class="divider-pt-3"></div>' +
      '<label class="field"><span>本地文件</span><input type="file" id="archivefile"></label>' +
      '<button class="btn" id="archivefilebtn">归档文件</button>' +
      '<div class="tiny subtle mt-2">支持 PDF、Word、Markdown、源码文本等；上限 100MB</div>' +
      '<div id="archiveout" class="mt-4"></div>');
  }

  /* ───────────── 页面：设置 ───────────── */

  function renderSettings(sub) {
    if (sub === "display") return renderDisplaySettings();
    if (sub === "data") return renderDataSettings();
    return card('<div class="card-title">外观主题</div><div class="spacer-h"></div>' +
      '<div class="row gap-2 wrap-flex">' +
      [["light", "浅色"], ["dark", "深色"], ["purple", "紫夜"]].map(function (t) {
        return '<button class="chip ' + (theme() === t[0] ? "active" : "") + '" data-theme="' + t[0] + '">' + t[1] + '</button>';
      }).join("") + '</div>' +
      '<div class="spacer-h"></div><div class="card-title text-body">主题色</div><div class="spacer-h"></div>' +
      '<div class="row gap-2 wrap-flex">' + ACCENTS.map(function (a) {
        var active = accent().toLowerCase() === a[1].toLowerCase();
        return '<button class="chip ' + (active ? "active" : "") + '" data-accent="' + a[1] + '">' +
          '<span class="dot" style="background:' + a[1] + '"></span> ' + a[0] + '</button>';
      }).join("") + '</div>') +
      '<div class="footer-note">NAS Monitor Web · 数据源 nas-monitor-api</div>';
  }

  function renderDisplaySettings() {
    return card('<div class="card-title">字体大小</div><div class="caption muted mt-1">与 App 的字号档位对齐，影响全站正文和界面标签。</div>' +
      '<div class="row gap-2 wrap-flex mt-3">' +
      [["standard", "标准"], ["large", "较大"], ["extra_large", "特大"]].map(function (option) {
        return '<button class="chip ' + (displayFont() === option[0] ? "active" : "") +
          '" data-display-font="' + option[0] + '">' + option[1] + '</button>';
      }).join("") + '</div>') +
      card('<div class="card-title">列表密度</div><div class="caption muted mt-1">视觉卡片保留指标；简洁列表减少卡片内留白和摘要高度。</div>' +
        '<div class="row gap-2 wrap-flex mt-3">' +
        [["visual", "视觉卡片"], ["compact", "简洁列表"]].map(function (option) {
          return '<button class="chip ' + (displayDensity() === option[0] ? "active" : "") +
            '" data-display-density="' + option[0] + '">' + option[1] + '</button>';
        }).join("") + '</div>') +
      card('<div class="card-title">卡片编号</div>' +
        '<div class="row between mt-2"><span class="caption muted">服务器页显示 01–06 编号</span>' +
        '<button class="switch ' + (cardNumbers() ? "on" : "") + '" role="switch" aria-checked="' +
        (cardNumbers() ? "true" : "false") + '" data-card-numbers="' + (cardNumbers() ? "0" : "1") + '">' +
        '<span></span></button></div>' +
        '<div class="row gap-2 mt-3">' +
        '<button class="btn small" data-display-move="up">选中卡片上移</button>' +
        '<button class="btn small" data-display-move="down">下移</button>' +
        '<button class="btn small" data-display-reset="1">恢复默认顺序</button>' +
        '</div>' +
        '<div class="tiny subtle mt-2">顺序同样适用于拖拽：可在服务器页直接拖动卡片。</div>') +
      card('<div class="card-title">当前顺序</div><div class="order-list mt-2">' +
      orderedOverviewIds().map(function (id, index) {
        return '<button class="order-item" data-order-select="' + esc(id) + '">' +
          '<span class="card-number">' + String(index + 1).padStart(2, "0") + '</span>' +
          '<span class="grow">' + esc(overviewCardLabel(id)) + '</span>' +
          '<span class="tiny subtle">选择</span></button>';
      }).join("") + '</div>');
  }

  function overviewCardLabel(id) {
    return id === "alerts" ? "告警摘要" : id === "router" ? "路由器" :
      id === "airport" ? "机场看板" : HOST_LABEL[id] || id;
  }

  function renderDataSettings() {
    return card('<div class="card-title">自动刷新</div><div class="spacer-h"></div>' +
        '<div class="row gap-2 wrap-flex">' +
        [[0, "关闭"], [5, "5 秒"], [10, "10 秒"], [30, "30 秒"], [60, "1 分钟"]].map(function (o) {
          return '<button class="chip ' + (refreshSec() === o[0] ? "active" : "") + '" data-refresh="' + o[0] + '">' + o[1] + '</button>';
        }).join("") + '</div>' +
        '<div class="tiny subtle mt-2">关闭后仍需手动刷新页面才会更新数据</div>') +
      card('<div class="card-title">App 扫码导入</div>' +
        '<div class="caption muted mt-1">用 NAS Monitor App 的「扫码导入」扫描下面的二维码，即可自动填入后端地址与访问口令。</div>' +
        '<div class="spacer-h"></div>' +
        '<div id="setup-qr" class="qr-box"><span class="spinner"></span></div>' +
        '<div class="tiny subtle mt-2" id="setup-qr-note"></div>') +
      card('<div class="card-title">访问口令</div>' +
        '<div class="caption muted mt-1">口令仅保存在本浏览器的 localStorage，用于调用 /api/*</div>' +
        '<div class="spacer-h"></div>' +
        '<button class="btn danger" id="logout">清除口令并退出</button>') +
      '<div class="footer-note">NAS Monitor Web · 数据源 nas-monitor-api</div>';
  }

  function renderLoading(msg) {
    return '<div class="card"><div class="empty"><span class="spinner"></span><div class="caption">' + esc(msg) + '</div></div></div>';
  }

  /* ───────────── 渲染主流程 ───────────── */

  function pageTitle(route) {
    var p = route.split("/");
    if (p[0] === "overview") return "总览";
    if (p[0] === "alerts") return "告警";
    if (p[0] === "gateway") return "网关";
    if (p[0] === "tools") return "工具";
    if (p[0] === "security") return "安全";
    if (p[0] === "settings") return "设置";
    if (p[0] === "router") return "爱快路由器";
    if (p[0] === "disks") return "存储";
    if (p[0] === "ask") return "知识问答";
    if (p[0] === "knowledge-archive") return "知识归档";
    if (p[0] === "trends") return "趋势 · " + (HOST_LABEL[p[1]] || p[1]);
    if (p[0] === "temperature") return "温度 · " + (HOST_LABEL[p[1]] || p[1]);
    if (p[0] === "containers") return "容器 · " + (HOST_LABEL[p[1]] || p[1]);
    if (p[0] === "services") return "服务 · " + (HOST_LABEL[p[1]] || p[1]);
    if (HOST_LABEL[p[0]]) return HOST_LABEL[p[0]] + " 详情";
    return "总览";
  }

  function isMainRoute(route) {
    return ROUTES.some(function (r) { return r[0] === route.split("/")[0]; });
  }

  /* 路由内部 UI 状态：重渲染时保留输入框/筛选/展开态（自动刷新每 10s 触发一次） */
  var ui = {
    cFilter: "", ipShown: {}, svcOpen: {}, logName: null, logText: null,
    askQ: "", askOut: null, askPending: false, archiveUrl: "", archiveOut: null,
    sections: {}, proxyGroups: {}, mihomoInstance: "core", confirmAction: null,
    displayFont: "standard", displayDensity: "visual", cardNumbers: true,
    cardOrder: [], selectedCard: null, logsExpanded: false,
    channelModels: {}, channelsExpanded: false, workbuddyExpanded: false,
    usageWindow: "1d", proxyGroupsExpanded: false
  };

  function render() {
    var route = currentRoute();
    if (route !== "knowledge-archive" && timers.archive) {
      clearInterval(timers.archive);
      timers.archive = null;
    }
    var main = route.split("/");
    var sub = main[1];
    var root = primaryTabKey(route);

    var head = "";
    if (!isMainRoute(route)) {
      head = '<div class="detail-page-head">' +
        '<button class="btn small" id="back">← 返回</button>' +
        '<h1 class="page-title">' + esc(pageTitle(route)) + '</h1>' +
        '<button class="btn small" id="manual-refresh">' + icon("refresh", 13) + ' 刷新</button>' +
        '</div>';
    } else {
      head = '<div class="row between"><h1 class="page-title">' + esc(pageTitle(root)) + '</h1>' +
        '<div class="row gap-2">' +
        (route === "overview" ? '<button class="btn small" data-go="disks">' + icon("disk", 13) + ' 存储</button>' : '') +
        '<button class="btn small" id="manual-refresh">' + icon("refresh", 13) + '</button>' +
        '</div></div>';
    }

    var content = "";
    if (main[0] === "overview") content = renderOverview();
    else if (main[0] === "alerts") content = renderAlerts();
    else if (main[0] === "gateway") content = renderGateway();
    else if (main[0] === "tools") content = renderTools(sub);
    else if (main[0] === "security") content = renderSecurity(sub);
    else if (main[0] === "settings") content = renderSettings(sub);
    else if (main[0] === "router") content = renderRouter();
    else if (main[0] === "disks") content = renderDisks();
    else if (main[0] === "trends") content = renderTrends(sub || "nas");
    else if (main[0] === "temperature") content = renderTemperature(sub || "nas");
    else if (main[0] === "containers") content = renderContainers(sub || "nas");
    else if (main[0] === "services") content = renderServices(sub || "nas");
    else if (main[0] === "ask") content = renderAsk();
    else if (main[0] === "knowledge-archive") content = renderArchive();
    else if (HOST_LABEL[main[0]]) content = renderHostDetail(main[0]);
    else content = '<div class="warnbox">未找到页面：' + esc(route) + '</div>';

    document.getElementById("pagehead").innerHTML = head;
    renderSubtabs(route);
    document.getElementById("page").innerHTML = content;
    // extras（容器日志等次级卡片）只在对应路由下保留
    if (route.indexOf("containers") !== 0) {
      document.getElementById("extras").innerHTML = "";
      ui.logName = null; ui.logText = null;
    } else {
      renderExtras();
    }
    renderNav();
    applyOverviewPreferences(route);
    applyCardOrder(route);
    renderDetailPanel(route);
    if (ui.lastRoute !== route) {
      ui.lastRoute = route;
      window.scrollTo(0, 0);
    }
    wire();
  }

  function applyOverviewPreferences() {
    var page = document.getElementById("page");
    var workbench = page && page.querySelector(".overview-workbench");
    if (!workbench) return;
    orderedOverviewIds().forEach(function (id) {
      var node = workbench.querySelector('[data-overview-card="' + id + '"]');
      if (node) workbench.appendChild(node);
    });
    Array.prototype.forEach.call(workbench.querySelectorAll("[data-overview-card]"), function (node, index) {
      var label = String(index + 1).padStart(2, "0");
      var number = node.querySelector(":scope > .card-number");
      if (!number) {
        number = document.createElement("span");
        number.className = "card-number";
        node.insertBefore(number, node.firstChild);
      }
      number.textContent = label;
      if (window.matchMedia("(max-width: 560px)").matches) {
        node.classList.add("mobile-summary-card");
      } else {
        node.classList.remove("mobile-summary-card");
      }
    });
  }

  function renderDetailPanel(route) {
    var detail = document.getElementById("detail");
    if (!detail) return;
    var root = route.split("/")[0];
    if (root === "overview" || HOST_LABEL[root]) {
      detail.innerHTML = '<div class="detail-title">详情抽屉</div>' +
        '<div class="detail-copy">主区与详情使用同一份快照，选择主机可继续查看资源、容器和服务。</div>' +
        '<div class="detail-links"><a href="#/disks">存储空间</a><a href="#/alerts">告警列表</a><a href="#/settings/display">显示设置</a></div>';
      return;
    }
    if (root === "gateway" || root === "tools") {
      detail.innerHTML = '<div class="detail-title">快速入口</div>' +
        '<div class="detail-copy">二级能力收在当前一级页内，六个主导航项始终一键可达。</div>' +
        '<div class="detail-links"><a href="#/overview">服务器总览</a><a href="#/gateway/proxy">代理状态</a><a href="#/tools/downloader">下载器</a></div>';
      return;
    }
    detail.innerHTML = "";
  }

  /* ── 卡片拖拽排序 ── */
  function cardOrderKey(route) { return "nas_card_order_" + route; }
  function getCardOrder(route) {
    try { return JSON.parse(localStorage.getItem(cardOrderKey(route)) || "[]"); } catch (e) { return []; }
  }
  function applyCardOrder(route) {
    var page = document.getElementById("page");
    var order = getCardOrder(route);
    if (!order.length || !page) return;
    var cards = Array.prototype.slice.call(page.querySelectorAll(":scope > .card"));
    var byId = {};
    cards.forEach(function (c) { if (c.id) byId[c.id] = c; });
    order.forEach(function (id) {
      if (byId[id]) page.appendChild(byId[id]);
    });
  }
  function wireCardDrag(route) {
    var page = document.getElementById("page");
    if (!page) return;
    var isOverview = !!page.querySelector(".overview-workbench");
    var scope = isOverview ? page.querySelector(".overview-workbench") : page;
    var selector = isOverview ? "[data-overview-card]" : ":scope > .card";
    scope.querySelectorAll(selector).forEach(function (card) {
      if (!card.id) return;
      card.draggable = true;
      card.addEventListener("dragstart", function (e) {
        e.dataTransfer.setData("text/plain", card.id);
        e.dataTransfer.effectAllowed = "move";
        card.style.opacity = "0.4";
      });
      card.addEventListener("dragend", function () {
        card.style.opacity = "";
      });
      card.addEventListener("dragover", function (e) {
        e.preventDefault();
        e.dataTransfer.dropEffect = "move";
      });
      card.addEventListener("drop", function (e) {
        e.preventDefault();
        var srcId = e.dataTransfer.getData("text/plain");
        var src = document.getElementById(srcId);
        if (!src || src === card) return;
        var cards = Array.prototype.slice.call(scope.querySelectorAll(selector));
        var srcIdx = cards.indexOf(src);
        var dstIdx = cards.indexOf(card);
        if (srcIdx < dstIdx) {
          scope.insertBefore(src, card.nextSibling);
        } else {
          scope.insertBefore(src, card);
        }
        // 保存新顺序
        var newOrder = Array.prototype.slice.call(scope.querySelectorAll(selector)).map(function (c) { return c.id; });
        localStorage.setItem(cardOrderKey(route), JSON.stringify(newOrder));
        if (isOverview) {
          var ids = Array.prototype.slice.call(scope.querySelectorAll("[data-overview-card]"))
            .sort(function (a, b) {
              return parseInt(a.getAttribute("data-card-index"), 10) - parseInt(b.getAttribute("data-card-index"), 10);
            })
            .map(function (node) { return node.getAttribute("data-overview-card"); });
          localStorage.setItem(OVERVIEW_ORDER_KEY, JSON.stringify(ids));
        }
        toast("卡片顺序已保存");
      });
    });
  }

  /* 只更新 extras（日志面板），不重建页面，避免打断用户输入/滚动 */
  function renderExtras() {
    var box = document.getElementById("extras");
    var host = (currentRoute().split("/")[1]) || "nas";
    if (!ui.logName) { box.innerHTML = ""; return; }
    var body = ui.logText == null ? '<span class="spinner"></span> 加载中…' :
      '<pre class="log">' + esc(ui.logText) + '</pre>';
    box.innerHTML = card('<div class="row between"><span class="card-title">' + esc(ui.logName) + ' 日志</span>' +
      '<span class="row gap-2"><span class="tiny subtle">' + esc(HOST_LABEL[host]) + '</span>' +
      '<button class="btn small" id="closelog">关闭</button></span></div>' +
      '<div class="spacer-h"></div>' + body);
    var cb = document.getElementById("closelog");
    if (cb) cb.addEventListener("click", function () {
      ui.logName = null; ui.logText = null; renderExtras();
    });
  }

  /* ───────────── 数据加载 ───────────── */

  function loadAll(route, background, registryReady) {
    if (!registryReady) return api("/servers").then(function (data) {
      syncServers(data.servers);
      return loadAll(currentRoute(), background, true);
    });
    var m = route.split("/");
    var jobs = [];
    function put(key, p, ttlKey) {
      var ttl = background ? (BACKGROUND_TTL[ttlKey || key] || 30000) : 0;
      // 仅后台轮询走缓存；切换页面/手动刷新仍强制取最新值。
      if (ttl && cache[key] && Date.now() - (cacheAt[key] || 0) < ttl) return;
      jobs.push(api(p).then(function (d) { cache[key] = d; if (key === "overview") syncServers(d.servers); cacheAt[key] = Date.now(); }).catch(function () {}));
    }

    if (m[0] === "overview" || m[0] === "alerts" || m[0] === "disks") {
      put("overview", "/overview");
      put("airport", "/airport-traffic");
    } else if (m[0] === "gateway") {
      put("gwOverview", "/newapi/overview");
      if (m[1] === "models") put("gwUsage", "/newapi/model-usage");
      put("gwBalance", "/newapi/balance");
      put("gwProbe", "/newapi/probe");
      put("gwAffinity", "/newapi/affinity");
      put("gwPolicy", "/newapi/routing-policy");
      put("gwTokens", "/newapi/log-tokens");
      put("gwLogs", "/newapi/logs?p=0&page_size=30");
      put("mihomoOverview", "/mihomo/overview");
      put("mihomoAirports", "/mihomo/airports");
    } else if (m[0] === "security") {
      var h = m[1] && HOST_LABEL[m[1]] ? m[1] : "nas";
      if (["nas", "aliyun", "tencent"].indexOf(h) >= 0) put("sec_" + h, "/security/" + h);
      if (h === "nas") put("sec_live", "/security/nas/live");
    } else if (m[0] === "tools") {
      var th = m[1] && HOST_LABEL[m[1]] ? m[1] : "nas";
      put("overview", "/overview");
      void th;
    } else if (m[0] === "router") {
      put("router", "/router");
    } else if (m[0] === "trends") {
      var rh = m[1] || "nas";
      put("trends_" + rh, "/trends/" + rh + "/" + (cache["trends_" + rh] ? cache["trends_" + rh].minutes : 60));
    } else if (m[0] === "temperature") {
      var ph = m[1] || "nas";
      put("temp_" + ph, "/trends/temperature/" + ph + "?minutes=60");
    } else if (m[0] === "containers") {
      var ch = m[1] || "nas";
      put("containers_" + ch, "/containers?host=" + ch);
      put("cstats_" + ch, "/container/stats/" + ch);
    } else if (m[0] === "services") {
      var sh = m[1] || "nas";
      if (sh === "nas") put("detail_nas", "/nas");
      else put("detail_" + sh, sh === "aliyun" ? "/aliyun" : "/servers/" + sh + "/detail");
    } else if (HOST_LABEL[m[0]]) {
      var dh = m[0];
      if (dh === "nas") put("detail_nas", "/nas");
      else put("detail_" + dh, dh === "aliyun" ? "/aliyun" : "/servers/" + dh + "/detail");
    }
    return Promise.all(jobs).then(function () { return jobs.length; });
  }

  function refreshNow(background) {
    // 标签不可见或上一轮尚未结束时不抢占网络/主线程。
    if (background && document.hidden) return Promise.resolve(0);
    if (refreshing) {
      if (!background) refreshQueued = true;
      return Promise.resolve(0);
    }
    refreshing = true;
    var route = currentRoute();
    refreshRoute = route;
    return loadAll(route, !!background).then(function (count) {
      // 后台缓存未过期时不重建 DOM，避免输入、滚动和大表格产生卡顿。
      if (currentRoute() === refreshRoute && (!background || count > 0)) render();
      return count;
    }).finally(function () {
      refreshing = false;
      if (refreshQueued) {
        refreshQueued = false;
        refreshNow(false);
      }
    });
  }

  /* ───────────── 交互绑定 ───────────── */

  /* 卡片内按钮的稳定 key（用于跨自动刷新保留展开态） */
  function cardKey(b) {
    var cardEl = b.closest(".card");
    if (!cardEl) return "unknown";
    var go = cardEl.getAttribute("data-go");
    if (go) return go;
    var t = cardEl.querySelector(".card-title");
    return t ? "t:" + t.textContent.trim() : "c:unknown";
  }

  function applyIp(b, shown) {
    var span = b.parentElement.querySelector(".ipval");
    if (!span) return;
    if (shown) {
      span.textContent = span.getAttribute("data-ip");
      span.style.color = "var(--ink)";
      b.textContent = "隐藏";
      b.setAttribute("data-shown", "1");
    } else {
      span.textContent = "已隐藏";
      span.style.color = "var(--subtle)";
      b.textContent = "显示";
      b.setAttribute("data-shown", "0");
    }
  }

  function applySvc(b, open) {
    var box = b.parentElement.querySelector(".svc-detail");
    if (!box) return;
    box.classList.toggle("hidden", !open);
    b.textContent = open ? "收起" : "展开";
    if (open) box.style.cssText = "display:block;flex-basis:100%;margin-top:8px;width:100%";
    else box.style.cssText = "";
  }

  function wire() {
    var body = document.getElementById("page");

    body.querySelectorAll("[data-ui-expand]").forEach(function (el) {
      el.addEventListener("click", function () {
        var key = el.getAttribute("data-ui-expand");
        ui.sections[key] = !ui.sections[key];
        render();
      });
    });

    body.querySelectorAll("[data-cpage]").forEach(function (el) {
      el.addEventListener("click", function () {
        var host = (currentRoute().split("/")[1]) || "nas";
        localStorage.setItem("nas_containers_page_" + host, el.getAttribute("data-cpage"));
        render();
      });
    });

    body.querySelectorAll("[data-gwtab]").forEach(function (el) {
      el.addEventListener("click", function () {
        var index = parseInt(el.getAttribute("data-gwtab"), 10);
        var routes = ["gateway", "gateway/performance", "gateway/logs", "gateway/channels", "gateway/models", "gateway/proxy"];
        localStorage.setItem(GATEWAY_TAB_KEY, String(index));
        go(routes[index] || "gateway");
      });
    });

    body.querySelectorAll("[data-logs-expand]").forEach(function (el) {
      el.addEventListener("click", function () {
        ui.logsExpanded = el.getAttribute("data-logs-expand") === "1";
        render();
      });
    });
    body.querySelectorAll("[data-channel-models]").forEach(function (el) {
      el.addEventListener("click", function () {
        var id = el.getAttribute("data-channel-models");
        ui.channelModels[id] = !ui.channelModels[id];
        render();
      });
    });
    body.querySelectorAll("[data-channels-expand]").forEach(function (el) {
      el.addEventListener("click", function () {
        ui.channelsExpanded = el.getAttribute("data-channels-expand") === "1";
        render();
      });
    });
    body.querySelectorAll("[data-workbuddy-expand]").forEach(function (el) {
      el.addEventListener("click", function () {
        ui.workbuddyExpanded = el.getAttribute("data-workbuddy-expand") === "1";
        render();
      });
    });
    body.querySelectorAll("[data-usage-window]").forEach(function (el) {
      el.addEventListener("click", function () {
        ui.usageWindow = el.getAttribute("data-usage-window");
        render();
      });
    });
    body.querySelectorAll("[data-usage-retry]").forEach(function (el) {
      el.addEventListener("click", function () {
        el.disabled = true;
        api("/newapi/model-usage")
          .then(function (d) { cache.gwUsage = d; cacheAt.gwUsage = Date.now(); render(); })
          .catch(function (e) { toast("用量加载失败：" + e.message); el.disabled = false; });
      });
    });
    var usageTrend = body.querySelector(".usage-trend");
    if (usageTrend) {
      var usageData = JSON.parse((body.querySelector(".usage-trend-data") || {}).textContent || "[]");
      var usageTooltip = body.querySelector("#usage-tooltip");
      body.querySelectorAll("[data-usage-index]").forEach(function (hit) {
        function showUsageTip(event) {
          var index = parseInt(hit.getAttribute("data-usage-index"), 10);
          var point = usageData[index];
          if (!point || !usageTooltip) return;
          usageTooltip.innerHTML = '<strong>' + esc(point.label) + '</strong>' +
            '<div><i style="background:#3b82f6"></i>请求数 ' + compactNumber(point.requests) + '</div>' +
            '<div><i style="background:#10b981"></i>Token ' + compactNumber(point.tokens) + '</div>';
          var box = usageTrend.parentElement.getBoundingClientRect();
          usageTooltip.style.left = Math.max(0, Math.min(box.width - 132, event.clientX - box.left + 10)) + "px";
          usageTooltip.style.top = "6px";
          usageTooltip.classList.add("show");
        }
        hit.addEventListener("mousemove", showUsageTip);
        hit.addEventListener("mouseenter", showUsageTip);
        hit.addEventListener("mouseleave", function () { usageTooltip.classList.remove("show"); });
      });
    }
    body.querySelectorAll("[data-proxy-expand]").forEach(function (el) {
      el.addEventListener("click", function () {
        ui.proxyGroupsExpanded = el.getAttribute("data-proxy-expand") === "1";
        render();
      });
    });

    body.querySelectorAll("[data-mihomo-instance]").forEach(function (el) {
      el.addEventListener("click", function () {
        ui.mihomoInstance = el.getAttribute("data-mihomo-instance");
        render();
      });
    });
    body.querySelectorAll("[data-mihomo-group]").forEach(function (el) {
      el.addEventListener("click", function () {
        var key = ui.mihomoInstance + ":" + el.getAttribute("data-mihomo-group");
        ui.proxyGroups[key] = !ui.proxyGroups[key];
        render();
      });
    });
    body.querySelectorAll("[data-mihomo-select]").forEach(function (el) {
      el.addEventListener("click", function () {
        var group = el.getAttribute("data-mihomo-select");
        var target = el.getAttribute("data-mihomo-target");
        if (!group || !target || !window.confirm("将「" + group + "」切换到「" + target + "」？")) return;
        el.disabled = true;
        apiPost("/mihomo/instances/" + encodeURIComponent(ui.mihomoInstance) +
          "/groups/" + encodeURIComponent(group) + "/select", { target: target })
          .then(function (result) {
            if (!result || result.applied !== true) throw new Error("切换未生效，当前为 " + ((result && result.now) || "未知"));
            return api("/mihomo/overview").then(function (fresh) {
              cache.mihomoOverview = fresh;
              cacheAt.mihomoOverview = Date.now();
              var selected = ((fresh.instances || []).find(function (item) { return item.id === ui.mihomoInstance; }) || {});
              var currentGroup = (selected.groups || []).find(function (item) { return item.name === group; });
              if (!currentGroup || currentGroup.now !== target) throw new Error("回读校验失败，节点未变更");
              toast("已切换到 " + target);
              render();
            });
          })
          .catch(function (error) {
            el.disabled = false;
            toast("切换失败：" + error.message);
          });
      });
    });
    var panelButton = body.querySelector("[data-mihomo-panel]");
    if (panelButton) panelButton.addEventListener("click", function () {
      window.open("/panel/index.html?token=" + encodeURIComponent(token()), "_blank", "noopener");
    });

    body.querySelectorAll("[data-pipio]").forEach(function (el) {
      el.addEventListener("click", function () {
        var name = el.getAttribute("data-pipio");
        toast("加载使用详情…");
        api("/newapi/balance/pipio/" + encodeURIComponent(name)).then(function (d) {
          cache.gwPipioDetail = d;
          render();
        }).catch(function (e) { toast(e.message || "明细加载失败"); });
      });
    });
    body.querySelectorAll(".pipio-detail-close").forEach(function (el) {
      el.addEventListener("click", function () { cache.gwPipioDetail = null; render(); });
    });

    body.querySelectorAll("[data-go]").forEach(function (el) {
      el.addEventListener("click", function (e) {
        if (e.target.closest("button")) return;
        go(el.getAttribute("data-go"));
      });
    });

    var back = document.getElementById("back");
    if (back) back.addEventListener("click", function () { history.back(); });

    var mr = document.getElementById("manual-refresh");
    if (mr) mr.addEventListener("click", function () {
      if (refreshing) return toast("刷新进行中…");
      toast("刷新中…");
      refreshNow().then(function (count) { if (count !== 0) toast("已刷新"); });
    });

    // 公网 IP 显示/隐藏（状态存 ui，自动刷新后不丢）
    body.querySelectorAll(".toggle-ip").forEach(function (b) {
      var key = cardKey(b);
      if (ui.ipShown[key]) applyIp(b, true);
      b.addEventListener("click", function () {
        var shown = b.getAttribute("data-shown") === "1";
        ui.ipShown[key] = !shown;
        applyIp(b, !shown);
      });
    });

    // 核心服务展开（状态存 ui）
    body.querySelectorAll(".toggle-svc").forEach(function (b) {
      var key = cardKey(b);
      if (ui.svcOpen[key]) applySvc(b, true);
      b.addEventListener("click", function () {
        var willOpen = b.parentElement.querySelector(".svc-detail").classList.contains("hidden");
        ui.svcOpen[key] = willOpen;
        applySvc(b, willOpen);
      });
    });

    // 主机 tab
    body.querySelectorAll("[data-tabhost]").forEach(function (b) {
      b.addEventListener("click", function () {
        var route = currentRoute().split("/")[0];
        go(route + "/" + b.getAttribute("data-tabhost"));
      });
    });

    // 趋势时长
    body.querySelectorAll("[data-trendmin]").forEach(function (b) {
      b.addEventListener("click", function () {
        var host = currentRoute().split("/")[1] || "nas";
        var min = b.getAttribute("data-trendmin");
        cache["trends_" + host] = null;
        api("/trends/" + host + "/" + min).then(function (d) { cache["trends_" + host] = d; render(); }).catch(function () {});
      });
    });

    // 设置：主题/主题色/刷新
    body.querySelectorAll("[data-theme]").forEach(function (b) {
      b.addEventListener("click", function () {
        localStorage.setItem(THEME_KEY, b.getAttribute("data-theme")); applyTheme(); render();
      });
    });
    body.querySelectorAll("[data-accent]").forEach(function (b) {
      b.addEventListener("click", function () {
        localStorage.setItem(ACCENT_KEY, b.getAttribute("data-accent")); applyTheme(); render();
      });
    });
    body.querySelectorAll("[data-refresh]").forEach(function (b) {
      b.addEventListener("click", function () {
        localStorage.setItem(REFRESH_KEY, b.getAttribute("data-refresh")); setupTimer(); render();
      });
    });
    body.querySelectorAll("[data-display-font]").forEach(function (b) {
      b.addEventListener("click", function () {
        localStorage.setItem(DISPLAY_FONT_KEY, b.getAttribute("data-display-font"));
        applyTheme();
        render();
      });
    });
    body.querySelectorAll("[data-display-density]").forEach(function (b) {
      b.addEventListener("click", function () {
        localStorage.setItem(DISPLAY_DENSITY_KEY, b.getAttribute("data-display-density"));
        applyTheme();
        render();
      });
    });
    var numberToggle = body.querySelector("[data-card-numbers]");
    if (numberToggle) numberToggle.addEventListener("click", function () {
      localStorage.setItem(CARD_NUMBERS_KEY, numberToggle.getAttribute("data-card-numbers"));
      applyTheme();
      render();
    });
    body.querySelectorAll("[data-order-select]").forEach(function (b) {
      b.addEventListener("click", function () {
        ui.selectedCard = b.getAttribute("data-order-select");
        toast("已选择" + overviewCardLabel(ui.selectedCard));
      });
    });
    body.querySelectorAll("[data-display-move]").forEach(function (b) {
      b.addEventListener("click", function () {
        if (!ui.selectedCard) return toast("请先选择卡片");
        var order = orderedOverviewIds();
        var index = order.indexOf(ui.selectedCard);
        var next = index + (b.getAttribute("data-display-move") === "up" ? -1 : 1);
        if (index < 0 || next < 0 || next >= order.length) return toast("已经到边界");
        order.splice(index, 1);
        order.splice(next, 0, ui.selectedCard);
        localStorage.setItem(OVERVIEW_ORDER_KEY, JSON.stringify(order));
        render();
      });
    });
    var orderReset = body.querySelector("[data-display-reset]");
    if (orderReset) orderReset.addEventListener("click", function () {
      localStorage.removeItem(OVERVIEW_ORDER_KEY);
      ui.selectedCard = null;
      render();
      toast("已恢复默认顺序");
    });
    var lg = document.getElementById("logout");
    if (lg) lg.addEventListener("click", function () {
      localStorage.removeItem(TOKEN_KEY); location.reload();
    });

    // 设置：App 扫码导入二维码（令牌只存在于二维码图形内，不落到 DOM 文本）
    var qrBox = document.getElementById("setup-qr");
    if (qrBox) {
      api("/app-setup-qr").then(function (d) {
        qrBox.innerHTML = d.svg || "";
        var note = document.getElementById("setup-qr-note");
        if (note) note.textContent = "后端地址：" + (d.url || "--") + " · 生成于 " + (d.generated_at || "--");
      }).catch(function (e) {
        qrBox.innerHTML = '<div class="tiny subtle">二维码不可用：' + esc(e.message) + '</div>';
      });
    }

    // 网关：渠道开关
    body.querySelectorAll(".ch-toggle").forEach(function (b) {
      b.addEventListener("click", function () {
        var id = parseInt(b.getAttribute("data-id"), 10);
        var status = parseInt(b.getAttribute("data-status"), 10);
        b.disabled = true;
        apiPost("/newapi/channels/" + id + "/status", { status: status })
          .then(function () { toast("渠道已" + (status === 1 ? "启用" : "停用")); return refreshNow(); })
          .catch(function (e) { toast("失败：" + e.message); })
          .finally(function () { b.disabled = false; });
      });
    });

    // 网关：路由策略
    body.querySelectorAll("[data-policy]").forEach(function (b) {
      b.addEventListener("click", function () {
        var mode = b.getAttribute("data-policy");
        var sel = body.querySelector("#policy-channel");
        var target = sel ? parseInt(sel.value, 10) : NaN;
        if (isNaN(target)) target = (cache.gwPolicy && cache.gwPolicy.channel_id) || (cache.gwOverview && (cache.gwOverview.channels || [])[0] || {}).id;
        if (!target) return toast("未找到可用渠道");
        b.disabled = true;
        apiPost("/newapi/routing-policy", { channel_id: target, mode: mode })
          .then(function () { toast("策略已应用（渠道 " + target + "）"); return refreshNow(); })
          .catch(function (e) { toast("失败：" + e.message); })
          .finally(function () { b.disabled = false; });
      });
    });
    var pr = body.querySelector("[data-policy-restore]");
    if (pr) pr.addEventListener("click", function () {
      pr.disabled = true;
      apiPost("/newapi/routing-policy/restore", {})
        .then(function () { toast("已恢复正常路由"); return refreshNow(); })
        .catch(function (e) { toast("失败：" + e.message); })
        .finally(function () { pr.disabled = false; });
    });

    // 网关：亲和
    var af = body.querySelector("[data-affinity]");
    if (af) af.addEventListener("click", function () {
      var enable = af.getAttribute("data-affinity") === "1";
      af.disabled = true;
      apiPost("/newapi/affinity", { enabled: enable })
        .then(function () { toast("亲和调度已" + (enable ? "开启" : "关闭")); return refreshNow(); })
        .catch(function (e) { toast("失败：" + e.message); })
        .finally(function () { af.disabled = false; });
    });
    var ac = body.querySelector("[data-affinity-clear]");
    if (ac) ac.addEventListener("click", function () {
      ac.disabled = true;
      apiPost("/newapi/affinity/cache/clear", {})
        .then(function () { toast("缓存已清空"); return refreshNow(); })
        .catch(function (e) { toast("失败：" + e.message); })
        .finally(function () { ac.disabled = false; });
    });

    // 网关：日志分页/按令牌过滤
    body.querySelectorAll("[data-logs-page]").forEach(function (b) {
      b.addEventListener("click", function () {
        var p = b.getAttribute("data-logs-page");
        var t = cache._logToken || "";
        api("/newapi/logs?p=" + p + "&page_size=30" + (t ? "&token=" + encodeURIComponent(t) : ""))
          .then(function (d) { cache.gwLogs = d; render(); }).catch(function () {});
      });
    });
    body.querySelectorAll(".logs-token").forEach(function (b) {
      b.addEventListener("click", function () {
        var t = b.getAttribute("data-token");
        cache._logToken = (cache._logToken === t) ? "" : t;
        var q = cache._logToken ? "&token=" + encodeURIComponent(cache._logToken) : "";
        api("/newapi/logs?p=0&page_size=30" + q)
          .then(function (d) { cache.gwLogs = d; render(); }).catch(function () {});
      });
    });

    // 路由器测速
    var st = document.getElementById("speedtest");
    if (st) st.addEventListener("click", function () {
      st.disabled = true; st.textContent = "测速中…";
      api("/router/speedtest/start").then(function (d) {
        toast(d.message || "测速已启动");
        setTimeout(function () { api("/router/speedtest/result").then(function (r) { cache.router = Object.assign({}, cache.router, r); render(); }).catch(function () {}); }, 62000);
      }).catch(function (e) { toast("失败：" + e.message); st.disabled = false; st.textContent = "开始测速"; });
    });

    // 容器筛选 + 日志
    var flt = document.getElementById("cflt");
    if (flt) {
      flt.value = ui.cFilter;
      var applyFilter = function () {
        var q = flt.value.trim().toLowerCase();
        ui.cFilter = flt.value;
        document.querySelectorAll("#clist .list-row").forEach(function (r) {
          r.classList.toggle("hidden", !!q && r.textContent.toLowerCase().indexOf(q) < 0);
        });
      };
      flt.addEventListener("input", applyFilter);
      if (ui.cFilter) applyFilter();
    }
    body.querySelectorAll(".c-log").forEach(function (b) {
      b.addEventListener("click", function () {
        var name = b.getAttribute("data-cname");
        ui.logName = name;
        ui.logText = null;
        renderExtras();
        var host = currentRoute().split("/")[1] || "nas";
        api("/container/" + host + "/" + encodeURIComponent(name)).then(function (res) {
          // 真实端返回 {host,name,lines,logs}；兼容旧版直接返回纯文本
          var text;
          if (typeof res === "string") text = res;
          else if (res && typeof res.logs === "string") text = res.logs;
          else text = JSON.stringify(res);
          ui.logText = text.slice(-60000);
          renderExtras();
        }).catch(function (e) {
          ui.logText = "读取失败：" + e.message;
          renderExtras();
        });
      });
    });

    // 问答
    var askBtn = document.getElementById("askbtn");
    var askBox = document.getElementById("askq");
    if (askBox) {
      askBox.value = ui.askQ;
      askBox.addEventListener("input", function () { ui.askQ = askBox.value; });
    }
    if (askBtn) askBtn.addEventListener("click", function () {
      var q = (askBox ? askBox.value : "").trim();
      var out = document.getElementById("askout");
      if (!q) return toast("请输入问题");
      ui.askQ = q;
      askBtn.disabled = true;
      out.innerHTML = renderLoading("正在检索知识库并生成回答…");
      apiPost("/ask", { question: q }).then(function (d) {
        askBtn.disabled = false;
        var src = (d.sources || []).map(function (s) {
          return '<div class="list-row"><span class="grow wrap-text">' + esc(s.title || s.url) +
            '<div class="tiny subtle wrap-text">' + esc(s.url || "") + '</div></span>' +
            '<span class="tiny subtle">' + (s.score ? (Math.round(s.score * 100) / 100) : "") + '</span></div>';
        }).join("");
        ui.askOut = (d.ok ? card('<div class="row between"><span class="caption muted">回答</span></div>' +
          '<div class="mt-2 answer-text">' + esc(d.answer || "") + '</div>') :
          '<div class="err">' + esc(d.error || "回答失败") + '</div>') +
          (src ? card('<div class="caption muted">引用来源（' + (d.sources || []).length + '）</div>' + src) : "");
        out.innerHTML = ui.askOut;
      }).catch(function (e) {
        askBtn.disabled = false;
        ui.askOut = '<div class="err">请求失败：' + esc(e.message) + '</div>';
        out.innerHTML = ui.askOut;
      });
    });
    var askOutEl = document.getElementById("askout");
    if (askOutEl && ui.askOut) askOutEl.innerHTML = ui.askOut;

    // 归档
    var ab = document.getElementById("archivebtn");
    var curl = document.getElementById("arcurl");
    if (curl) {
      curl.value = ui.archiveUrl;
      curl.addEventListener("input", function () { ui.archiveUrl = curl.value; });
    }
    if (ab) ab.addEventListener("click", function () {
      var url = (curl ? curl.value : "").trim();
      var out = document.getElementById("archiveout");
      if (!/^https?:\/\//i.test(url)) return toast("请输入 http(s) 链接");
      ui.archiveUrl = url;
      ab.disabled = true;
      out.innerHTML = renderLoading("正在归档…");
      apiPost("/knowledge/archive/url", { url: url }).then(function (d) {
        ab.disabled = false;
        ui.archiveOut = '<div class="okbox">已提交归档任务：' + esc(d.task_id || "") + '</div>';
        out.innerHTML = ui.archiveOut;
        pollArchive(d.task_id, out);
      }).catch(function (e) {
        ab.disabled = false;
        ui.archiveOut = '<div class="err">归档失败：' + esc(e.message) + '</div>';
        out.innerHTML = ui.archiveOut;
      });
    });
    var afb = document.getElementById("archivefilebtn");
    if (afb) afb.addEventListener("click", function () {
      var f = document.getElementById("archivefile").files[0];
      var out = document.getElementById("archiveout");
      if (!f) return toast("请选择文件");
      if (f.size > 100 * 1024 * 1024) return toast("文件超过 100MB");
      afb.disabled = true;
      out.innerHTML = renderLoading("正在上传归档…");
      var fd = new FormData();
      fd.append("addToWorkspaces", "nas-docker");
      fd.append("metadata", "{}");
      fd.append("file", f);
      fetch("/api/knowledge/archive/file", {
        method: "POST", headers: { "Authorization": "Bearer " + token() }, body: fd
      }).then(function (r) { return r.json(); }).then(function (d) {
        afb.disabled = false;
        if (d.error) { ui.archiveOut = '<div class="err">' + esc(d.error) + '</div>'; out.innerHTML = ui.archiveOut; return; }
        ui.archiveOut = '<div class="okbox">已提交归档任务：' + esc(d.task_id || "") + '</div>';
        out.innerHTML = ui.archiveOut;
        pollArchive(d.task_id, out);
      }).catch(function (e) {
        afb.disabled = false;
        ui.archiveOut = '<div class="err">上传失败：' + esc(e.message) + '</div>';
        out.innerHTML = ui.archiveOut;
      });
    });
    var archOutEl = document.getElementById("archiveout");
    if (archOutEl && ui.archiveOut) archOutEl.innerHTML = ui.archiveOut;
  }

  function pollArchive(taskId, out) {
    if (!taskId) return;
    if (timers.archive) clearInterval(timers.archive);
    var tries = 0;
    var t = setInterval(function () {
      tries++;
      if (tries > 40) { clearInterval(t); if (timers.archive === t) timers.archive = null; return; }
      api("/knowledge/archive/status?task_id=" + encodeURIComponent(taskId)).then(function (d) {
        if (d.status === "completed" || d.status === "failed") {
          clearInterval(t);
          if (timers.archive === t) timers.archive = null;
          out.innerHTML = d.status === "completed"
            ? '<div class="okbox">归档完成（工作区 ' + esc(d.workspace || "nas-docker") + '）</div>'
            : '<div class="err">归档失败：' + esc(d.error || "未知错误") + '</div>';
        }
      }).catch(function () {});
    }, 3000);
    timers.archive = t;
  }

  function setupTimer() {
    if (timers.poll) clearInterval(timers.poll);
    var s = refreshSec();
    if (s <= 0) return;
    timers.poll = setInterval(function () { refreshNow(true); }, s * 1000);
    wireCardDrag(currentRoute().split("/")[0]);
  }

  /* ───────────── 登录门 ───────────── */

  function showAuth(msg) {
    document.getElementById("auth").classList.remove("hidden");
    document.getElementById("app").classList.add("hidden");
    if (msg) document.getElementById("autherr").textContent = msg;
  }

  function gate() {
    if (!token()) return showAuth("");
    api("/overview").then(function (overview) {
      // 认证探测结果就是首屏数据，避免登录后把慢的 overview 再请求一次。
      cache.overview = overview;
      cacheAt.overview = Date.now();
      document.getElementById("auth").classList.add("hidden");
      document.getElementById("app").classList.remove("hidden");
      start(true);
    }).catch(function (e) {
      if (e.code === 401) { localStorage.removeItem(TOKEN_KEY); showAuth("访问口令无效，请重新输入"); }
      else showAuth("无法连接 Monitor API：" + e.message);
    });
  }

  function start(hasOverview) {
    applyTheme();
    document.getElementById("nav").innerHTML = "";
    var route = currentRoute();
    // 总览已经由鉴权请求拿到，首屏只补充轻量流量卡，避免双重慢请求。
    if (hasOverview && route === "overview") {
      loadAll(route, true).then(function () { render(); setupTimer(); });
    } else {
      refreshNow(false).then(setupTimer);
    }
  }

  /* ───────────── 启动 ───────────── */

  window.addEventListener("hashchange", function () {
    if (currentRoute() !== "knowledge-archive" && timers.archive) {
      clearInterval(timers.archive);
      timers.archive = null;
    }
    render();
    refreshNow(false);
  });
  document.addEventListener("visibilitychange", function () {
    if (!document.hidden) refreshNow(true);
  });

  document.addEventListener("DOMContentLoaded", function () {
    document.getElementById("authbtn").addEventListener("click", function () {
      var v = document.getElementById("authtoken").value.trim();
      if (v.length < 24) { document.getElementById("autherr").textContent = "请输入有效访问口令"; return; }
      document.getElementById("autherr").textContent = "";
      document.getElementById("authbtn").disabled = true;
      setToken(v);
      api("/overview").then(function (overview) {
        cache.overview = overview;
        cacheAt.overview = Date.now();
        document.getElementById("auth").classList.add("hidden");
        document.getElementById("app").classList.remove("hidden");
        start(true);
      }).catch(function (e) {
        localStorage.removeItem(TOKEN_KEY);
        document.getElementById("autherr").textContent = e.code === 401 ? "访问口令无效" : "连接失败：" + e.message;
      }).finally(function () {
        document.getElementById("authbtn").disabled = false;
      });
    });
    document.getElementById("authtoken").addEventListener("keydown", function (e) {
      if (e.key === "Enter") document.getElementById("authbtn").click();
    });
    gate();
  });
})();
