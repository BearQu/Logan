/* Logan H5 监控注入脚本（零依赖 ES5，兼容老 WebView）。
 * 由原生容器在 document-start 注入，业务页面零改动；hook fetch / XMLHttpRequest /
 * window.onerror / unhandledrejection / Resource Timing。上报优先走 window.LoganH5 JS 桥
 * （无跨域、复用原生批量与身份），桥不可用时降级 fetch keepalive 直传平台。
 * 两条硬规则：一切异常吞掉；绝不新增自定义请求头（避免触发业务 CORS 预检把请求搞挂）。
 * 一行关闭：原生侧 LoganH5Bridge.setEnabled(false)，或页面里 window.__LOGAN_H5_OFF__ = true。
 */
(function () {
  'use strict';
  if (window.__LOGAN_H5_INSTALLED__) { return; }
  if (window.__LOGAN_H5_OFF__) { return; }
  var bridge = window.LoganH5 || null;
  var cfg = {};
  try {
    if (bridge && typeof bridge.config === 'function') {
      var raw = bridge.config();
      if (raw) { cfg = JSON.parse(raw) || {}; }
    }
  } catch (e) { cfg = {}; }
  if (cfg && cfg.enabled === false) { return; }
  window.__LOGAN_H5_INSTALLED__ = true;

  var baseUrl = (cfg.baseUrl || '').replace(/\/+$/, '');
  var slowThreshold = typeof cfg.slowThresholdMs === 'number' && cfg.slowThresholdMs > 0 ? cfg.slowThresholdMs : 3000;
  var queue = [];
  var MAX_QUEUE = 200;
  var FLUSH_MS = 3000;
  var MAX_BODY_CHARS = 600;
  var seen = {};
  var seenCount = 0;
  // JS 异常单独成队：走错误通道（logan_error），不与网络事件混在一张表
  var eq = [];
  var errSeen = {};
  var errSeenCount = 0;

  function now() { return (Date.now ? Date.now() : new Date().getTime()); }
  function rid() {
    return 'h5-' + now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
  }
  function page() {
    try { return location.href || ''; } catch (e) { return ''; }
  }
  function abs(u) {
    try {
      if (!u) return '';
      if (typeof u === 'string' && /^https?:\/\//i.test(u)) return u;
      var a = document.createElement('a');
      a.href = String(u);
      return a.href;
    } catch (e) { return String(u || ''); }
  }
  function selfUrl(u) {
    // 只过滤"SDK 自己的上报接口"：H5 页面常与平台同域（或挂在平台网关下），
    // 整域前缀过滤会把 window.onerror / unhandledrejection（url=location.href）全部误杀。
    try {
      if (!baseUrl) { return false; }
      var t = String(u || '');
      if (t.indexOf(baseUrl) !== 0) { return false; }
      var rest = t.substring(baseUrl.length);
      return rest.indexOf('/logan/') === 0 || rest.indexOf('/logan?') === 0;
    } catch (e) { return false; }
  }
  function shortText(v) {
    try {
      if (v === null || v === undefined) return null;
      var s = String(v);
      if (!s) return null;
      return s.length > MAX_BODY_CHARS ? s.slice(0, MAX_BODY_CHARS) : s;
    } catch (e) { return null; }
  }
  function dedupKey(ev) {
    // 去重只针对资源语义（Resource Timing 会重复报同一张图）；XHR/fetch 每次调用都是独立事件
    if (ev.source !== 'resource') return null;
    return ev.method + ' ' + ev.url + ' ' + ev.status + ' ' + (ev.errKind || '');
  }

  function push(ev) {
    try {
      if (!ev || !ev.url || selfUrl(ev.url)) return;
      if (!ev.pageUrl) ev.pageUrl = page();
      if (!ev.occurredAt) ev.occurredAt = now();
      var k = dedupKey(ev);
      if (k) {
        if (seen[k]) return;
        if (seenCount > 4000) { seen = {}; seenCount = 0; }
        seen[k] = 1; seenCount++;
      }
      queue.push(ev);
      if (queue.length >= 30) { flush(); }
    } catch (e) {}
  }

  function flush() {
    try {
      if (!queue.length) return;
      var batch = queue.splice(0, MAX_QUEUE);
      var payload = JSON.stringify({ events: batch });
      if (bridge && typeof bridge.postJson === 'function') {
        try {
          if (bridge.postJson(payload) === true) { return; }
        } catch (e) {}
      }
      // 降级：直传平台（需平台开 CORS，平台侧 /logan/http/report.json 已 @CrossOrigin）
      if (!baseUrl || !window.fetch) { return; }
      var body = {
        appId: cfg.appId || '',
        deviceId: cfg.deviceId || '',
        platform: cfg.platform || 1,
        appVersion: cfg.appVersion || '',
        buildVersion: cfg.buildVersion || '',
        os: 'h5',
        kind: 'h5',
        events: batch
      };
      window.fetch(baseUrl + '/logan/http/report.json', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        credentials: 'omit',
        keepalive: true,
        cache: 'no-store'
      }).catch(function () {});
    } catch (e) {}
  }
  // ---------- 非致命 JS 异常：独立队列 + 独立上报通道 ----------
  function errNameOf(err, fallback) {
    try {
      if (err && typeof err === 'object' && err.name) { return String(err.name); }
      if (typeof err === 'string' && err) { return err.slice(0, 120); }
    } catch (e) {}
    return fallback;
  }
  function pushErr(ev) {
    try {
      if (!ev) return;
      if (!ev.name && !ev.message && !ev.stack) { return; }   // 三要素全空不造数据
      if (!ev.pageUrl) ev.pageUrl = page();
      if (!ev.occurredAt) ev.occurredAt = now();
      if (!ev.kind) ev.kind = 'caught';
      var k = ev.kind + '|' + (ev.name || '') + '|' + (ev.message || '');
      if (errSeen[k]) { return; }                              // 同错误风暴抑制
      if (errSeenCount > 2000) { errSeen = {}; errSeenCount = 0; }
      errSeen[k] = 1; errSeenCount++;
      eq.push(ev);
      if (eq.length >= 20) { flushErr(); }
    } catch (e) {}
  }
  function flushErr() {
    try {
      if (!eq.length) return;
      var batch = eq.splice(0, 100);
      var payload = JSON.stringify({ events: batch });
      if (bridge && typeof bridge.postJson === 'function') {
        try {
          if (bridge.postJson(payload) === true) { return; }
        } catch (e) {}
      }
      if (!baseUrl || !window.fetch) { return; }
      var body = {
        appId: cfg.appId || '',
        deviceId: cfg.deviceId || '',
        platform: cfg.platform || 1,
        appVersion: cfg.appVersion || '',
        buildVersion: cfg.buildVersion || '',
        os: 'h5',
        events: batch
      };
      window.fetch(baseUrl + '/logan/error/report.json', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        credentials: 'omit',
        keepalive: true,
        cache: 'no-store'
      }).catch(function () {});
    } catch (e) {}
  }
  setInterval(flush, FLUSH_MS);
  setInterval(flushErr, FLUSH_MS);
  if (document && document.addEventListener) {
    document.addEventListener('visibilitychange', function () {
      try { if (document.visibilityState === 'hidden') { flush(); flushErr(); } } catch (e) {}
    });
  }
  window.addEventListener('beforeunload', function () { try { flush(); flushErr(); } catch (e) {} });

  function classifyErr(kind, msg) {
    try {
      if (kind) return String(kind);
      var m = String(msg || '').toLowerCase();
      if (/abort/.test(m)) return 'cancel';
      if (/timeout|timed out/.test(m)) return 'timeout';
      if (/ssl|certificate|cert/.test(m)) return 'ssl';
      if (/json|parse|unexpected token/.test(m)) return 'parse';
      if (/network|failed to fetch|connect|connection|dns|enotfound|econnrefused/.test(m)) return 'connect';
      return m ? 'other' : null;
    } catch (e) { return 'other'; }
  }

  // ---------- XMLHttpRequest ----------
  try {
    var XP = window.XMLHttpRequest && window.XMLHttpRequest.prototype;
    if (XP && !XP.__loganWrapped__) {
      var nativeOpen = XP.open;
      var nativeSend = XP.send;
      XP.open = function (method, url) {
        try {
          this.__logan = {
            method: String(method || 'GET').toUpperCase(),
            url: abs(url),
            start: now(),
            traceId: rid()
          };
        } catch (e) {}
        return nativeOpen.apply(this, arguments);
      };
      XP.send = function (body) {
        var meta = this.__logan;
        try {
          if (meta) {
            meta.start = now();
            var self = this;
            var settle = function () {
              try {
                if (meta.settled) return;
                meta.settled = true;
                var st = 0;
                try { st = self.status || 0; } catch (e) { st = 0; }
                var size = null;
                try {
                  if (typeof self.response === 'string') size = self.response.length;
                  else if (self.responseXML) size = 0;
                } catch (e) {}
                push({
                  kind: 'h5',
                  source: 'xhr',
                  method: meta.method,
                  url: meta.url,
                  status: st,
                  errKind: st === 0 ? 'connect' : null,
                  errMsg: st === 0 ? shortText(self.statusText || 'network error') : null,
                  durationMs: now() - meta.start,
                  respBytes: size,
                  slow: (now() - meta.start) >= slowThreshold,
                  traceId: meta.traceId,
                  pageUrl: page()
                });
              } catch (e) {}
            };
            if (this.addEventListener) {
              this.addEventListener('loadend', settle);
              this.addEventListener('error', settle);
              this.addEventListener('abort', settle);
              this.addEventListener('timeout', settle);
            } else {
              this.onreadystatechange = (function (prev) {
                return function () {
                  try { if (prev) prev.apply(self, arguments); } catch (e) {}
                  try { if (self.readyState === 4) settle(); } catch (e) {}
                };
              })(this.onreadystatechange);
            }
          }
        } catch (e) {}
        return nativeSend.apply(this, arguments);
      };
      XP.__loganWrapped__ = true;
    }
  } catch (e) {}

  // ---------- fetch ----------
  try {
    if (window.fetch && !window.fetch.__loganWrapped__) {
      var nativeFetch = window.fetch;
      var wrapped = function (input, init) {
        var target = '';
        var method = 'GET';
        try {
          if (typeof input === 'string') target = abs(input);
          else if (input && typeof input.url === 'string') target = abs(input.url);
          else target = abs(String(input));
          if (init && init.method) method = String(init.method).toUpperCase();
          else if (input && input.method) method = String(input.method).toUpperCase();
        } catch (e) {}
        var start = now();
        var traceId = rid();
        var done = function (ok, status, err) {
          push({
            kind: 'h5',
            source: 'fetch',
            method: method,
            url: target,
            status: ok ? (status || 0) : 0,
            errKind: ok ? (status >= 400 ? null : null) : classifyErr(null, err),
            errMsg: ok ? null : shortText(err && (err.message || err)),
            durationMs: now() - start,
            slow: (now() - start) >= slowThreshold,
            traceId: traceId,
            pageUrl: page()
          });
        };
        var p = nativeFetch.apply(this, arguments);
        try {
          if (!p || typeof p.then !== 'function') { return p; }
          return p.then(
            function (res) { try { done(true, res && res.status ? res.status : 0, null); } catch (e) {} return res; },
            function (err) { try { done(false, 0, err); } catch (e) {} throw err; }
          );
        } catch (e) { return p; }
      };
      wrapped.__loganWrapped__ = true;
      window.fetch = wrapped;
    }
  } catch (e) {}

  // ---------- JS 异常 ----------
  try {
    var prevOnError = window.onerror;
    window.onerror = function (msg, src, line, col, err) {
      try {
        pushErr({
          kind: 'caught',
          name: errNameOf(err, 'window.onerror'),
          message: shortText(msg) + (src ? ' @ ' + src + ':' + line + ':' + col : ''),
          stack: shortText(err && err.stack),
          traceId: rid(),
          pageUrl: (src && /^(https?:)?\/\//i.test(String(src))) ? src : page(),
          extra: 'source=window.onerror'
        });
      } catch (e) {}
      try { if (prevOnError) return prevOnError.apply(this, arguments); } catch (e) {}
      return false;
    };
  } catch (e) {}
  try {
    window.addEventListener('unhandledrejection', function (ev) {
      try {
        var r = ev && ev.reason;
        pushErr({
          kind: 'promise',
          name: errNameOf(r, 'unhandledrejection'),
          message: shortText(r && (r.message || r)),
          stack: shortText(r && r.stack),
          traceId: rid(),
          pageUrl: page(),
          extra: 'source=unhandledrejection'
        });
      } catch (e) {}
    });
  } catch (e) {}

  // ---------- Resource Timing：只报慢资源与非 2xx，避免刷屏 ----------
  try {
    if (window.performance && performance.getEntriesByType) {
      var scan = function () {
        try {
          var list = performance.getEntriesByType('resource') || [];
          for (var i = 0; i < list.length; i++) {
            var en = list[i];
            if (!en || !en.name) continue;
            var dur = en.duration || 0;
            var st = typeof en.responseStatus === 'number' ? en.responseStatus : 0;
            var bad = st >= 400 || (st === 0 && en.transferSize === 0 && en.decodedBodySize === 0);
            if (dur < slowThreshold && !bad) continue;
            push({
              kind: 'h5',
              source: 'resource',
              method: 'GET',
              url: en.name,
              status: st,
              errKind: bad ? 'connect' : null,
              errMsg: bad ? shortText('resource failed status=' + st) : null,
              durationMs: Math.round(dur),
              ttfbMs: en.responseStart && en.requestStart ? Math.round(en.responseStart - en.requestStart) : null,
              respBytes: typeof en.encodedBodySize === 'number' ? en.encodedBodySize : null,
              slow: dur >= slowThreshold,
              traceId: rid(),
              pageUrl: page(),
              extra: 'source=resource;initiator=' + String(en.initiatorType || 'other')
            });
          }
        } catch (e) {}
      };
      setInterval(scan, 5000);
      if (document && document.addEventListener) {
        window.addEventListener('load', function () { setTimeout(scan, 1500); });
      }
    }
  } catch (e) {}

  // ---------- SPA 路由切换后重置去重表，保证新页面能重新采 ----------
  try {
    var onRoute = function () { seen = {}; seenCount = 0; };
    window.addEventListener('hashchange', onRoute);
    window.addEventListener('popstate', onRoute);
  } catch (e) {}

  // 显式入口：特殊客户端可自己报一条（例如自研网络层）
  window.LoganH5Report = function (ev) {
    try {
      var e = ev || {};
      var toErr = e.kind === 'error' || e.kind === 'caught' || e.kind === 'business'
        || e.kind === 'promise' || e.kind === 'ui' || e.kind === 'resource'
        || (e.name && !e.url);
      if (toErr) {
        var o = Object.assign({}, e);
        if (o.kind === 'error') { o.kind = 'caught'; }
        pushErr(o);
      } else {
        push(Object.assign({ kind: 'h5', source: 'manual' }, e));
      }
    } catch (e) {}
  };
  window.LoganH5Flush = function () { try { flush(); flushErr(); } catch (e) {} };
})();