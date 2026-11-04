/**
 * fm-api.js —— HTML 站点运行时的宿主桥接层。
 *
 * 两套契约共存：
 *  1) TVBox html spider 老契约：fm.req(url) 同步返回正文字符串、fm.done(结果) 回传。
 *     首页/分类/详情这类脚本仍走这条路，行为一字不改。
 *  2) 主流 WebHome 契约（对齐 webhtv 的 Native SDK）：SDK 就绪后派发 fmsdk 事件，
 *     fm.req(url, options) / fm.request() 返回 Promise 结构化响应（ok/status/url/
 *     headers/cookies/body/error，responseType 支持 text/json/base64）；
 *     走 fongmiNative 异步桥，Native 后台 OkHttp 不阻塞 JS 线程，大响应自动分片。
 *     fm.cache / fm.res / fm.play / fm.vod / fm.back / fm.reload / fm.site 等就位。
 *
 * 关键点：宿主在【主文档开头内联注入本文件】，页面任何脚本运行前 window.fm 已存在；
 * fmsdk 在 DOMContentLoaded（或 load）后派发，页面等 SDK 的初始化才不会卡在
 * 「准备加载… / 聚合搜索中…」。
 */
(function () {
  var fm = window.fm;
  if (!fm || typeof fm !== 'object') {
    fm = {};
    window.fm = fm;
  }
  var _done = false;

  /* ---- 异步桥（对齐 webhtv HomeWebBridge）：JS 发 id → Native 后台 OkHttp → 回调 resolve ---- */
  var _pending = {};
  var _seq = 0;
  var CHUNK = 60000;
  var INLINE_LIMIT_HINT = 12000;

  function jstr(s) {
    return JSON.stringify(String(s === undefined || s === null ? '' : s));
  }

  /** 大响应分片还原：Native 超过阈值只回 {__fmResultId}，这里按块拉回并拼成字符串 */
  function readChunks(rid) {
    var b = B();
    var len = 0;
    try { len = b.resultLength(rid) || 0; } catch (e) { return ''; }
    var out = [];
    for (var s = 0; s < len; s += CHUNK) {
      var part = '';
      try { part = b.resultChunk(rid, s) || ''; } catch (e) {}
      out.push(part);
      if (!part) break;
    }
    try { b.clearResult(rid); } catch (e) {}
    return out.join('');
  }

  /** Native 返回的结构化 payload → WebHome 响应对象（文档 17.1 契约） */
  function toResponse(payload, url, opt) {
    var p = (payload && typeof payload === 'object') ? payload : {};
    if (p.__fmResultId) {
      var raw = readChunks(p.__fmResultId);
      try { p = JSON.parse(raw); } catch (e) { p = {}; }
    }
    var status = Number(p.status || 0);
    var ok = typeof p.ok === 'boolean' ? p.ok : (status >= 200 && status < 300);
    var body = p.body === undefined ? '' : p.body;
    var type = String((opt && opt.responseType) || 'text').toLowerCase();
    if (type === 'json' && typeof body === 'string') {
      try { body = JSON.parse(body); } catch (e) {}
    }
    var hdrs = (p.headers && typeof p.headers === 'object') ? p.headers : {};
    var cookies = (p.cookies && p.cookies.length) ? p.cookies : [];
    var r = {
      ok: ok,
      status: status,
      url: p.url || url || '',
      headers: hdrs,
      cookies: cookies,
      body: body,
      content: typeof body === 'string' ? body : JSON.stringify(body),
      data: body,
      error: p.error || '',
      text: function () {
        return Promise.resolve(typeof body === 'string' ? body : JSON.stringify(body));
      },
      json: function () {
        try {
          return Promise.resolve(typeof body === 'string' ? JSON.parse(body) : body);
        } catch (e) { return Promise.reject(e); }
      },
      clone: function () { return toResponse(p, url, opt); }
    };
    r.headers.get = function (k) {
      var v = hdrs[k];
      if (v === undefined) {
        var lk = String(k).toLowerCase();
        for (var n in hdrs) if (n.toLowerCase() === lk) { v = hdrs[n]; break; }
      }
      return v === undefined ? null : (Array.isArray(v) ? v.join(', ') : String(v));
    };
    return r;
  }

  window.fongmiNative = window.fongmiNative || {
    resolve: function (id, payload) {
      var p = _pending[id];
      if (!p) return;
      delete _pending[id];
      try { p.resolve(toResponse(payload, p.url, p.opt)); }
      catch (e) { try { p.reject(e); } catch (e2) {} }
    },
    reject: function (id, err) {
      var p = _pending[id];
      if (!p) return;
      delete _pending[id];
      p.reject(new Error(String(err || 'request failed')));
    }
  };

  /** 真异步：不阻塞 WebView JS 线程，多个请求可并发（旧同步 req 会把整个页面卡住） */
  function asyncRequest(url, opt) {
    opt = opt || {};
    return new Promise(function (resolve, reject) {
      var id = 'fmreq_' + (++_seq) + '_' + Date.now();
      var secs = Number(opt.timeout || 30);
      if (!(secs > 0)) secs = 30;
      var timer = setTimeout(function () {
        if (!_pending[id]) return;
        delete _pending[id];
        resolve(toResponse({ ok: false, status: 0, body: '', error: 'timeout' }, url, opt));
      }, secs * 1000 + 2000);
      _pending[id] = {
        url: String(url), opt: opt,
        resolve: function (v) { clearTimeout(timer); resolve(v); },
        reject: function (e) { clearTimeout(timer); reject(e); }
      };
      var headers = '';
      try { headers = JSON.stringify(opt.headers || {}); } catch (e) {}
      var type = String(opt.responseType || 'text');
      try {
        B().requestAsync(id, String(url), headers,
          String(opt.method || 'GET').toUpperCase(), str(opt.body || ''), type);
      } catch (e) {
        clearTimeout(timer);
        delete _pending[id];
        resolve(toResponse({ ok: false, status: 0, body: '', error: String(e) }, url, opt));
      }
    });
  }

  function safe(fn, def) {
    try { return fn(); } catch (e) { return def; }
  }
  function B() { return window.FmBridge; }
  function parse(s, def) {
    try { return JSON.parse(s); } catch (e) { return def; }
  }
  function obj(v) { return v && typeof v === 'object' ? v : {}; }
  function str(v) { return v === undefined || v === null ? '' : String(v); }

  /** 结构化响应：同时提供 body/content、text()/json()、headers.get()，兼容主流用法 */
  function makeResponse(text, ok, status, url, error) {
    var r = {
      ok: !!ok,
      status: status || (ok ? 200 : 0),
      body: text,
      content: text,
      data: text,
      url: url || '',
      error: error || '',
      headers: { get: function () { return null; } }
    };
    r.text = function () { return Promise.resolve(text); };
    r.json = function () {
      try { return Promise.resolve(JSON.parse(text)); }
      catch (e) { return Promise.reject(e); }
    };
    return r;
  }

  /* ---------------- 老 spider 契约（保持同步、保持返回字符串） ---------------- */

  fm.reset = function () { _done = false; };

  fm.log = function (m) {
    safe(function () { B().log(String(m)); }, null);
  };

  fm.siteKey = function () {
    return safe(function () { return B().siteKey(); }, '');
  };

  fm.ext = function () {
    return safe(function () { return B().ext(); }, '');
  };

  /** 老 spider 契约：单参同步返回正文字符串（不能改成异步，html spider 依赖它）。
   *  主流 WebHome 契约：传了 options（或走 fm.request）→ 真异步 Promise 结构化响应。 */
  /* ===== 浏览器原生通道（对齐 webhtv：宿主不劫持 fetch/XHR） =====
   *  WebView 原生 fetch 自带页面 Cookie / Referer / 自动解压 / 正确 CORS，
   *  图片与接口都优先走它；只有原生失败（CORS、混合内容）才回退宿主 OkHttp 桥接。 */
  function nativeRequest(url, opt) {
    opt = opt || {};
    return new Promise(function (resolve) {
      var settled = false;
      function done(r) { if (!settled) { settled = true; resolve(r); } }
      try {
        var secs = Number(opt.timeout || 30); if (!(secs > 0)) secs = 30;
        var ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
        var timer = setTimeout(function () { try { ctrl && ctrl.abort(); } catch (e) {} done(toResponse({ ok: false, status: 0, body: '', error: 'timeout' }, url, opt)); }, secs * 1000);
        var h = {};
        try {
          var hh = opt.headers || {};
          Object.keys(hh).forEach(function (k) {
            var l = k.toLowerCase();
            if (l !== 'user-agent' && l !== 'host' && l !== 'origin' && l !== 'content-length') h[k] = hh[k];
          });
        } catch (e) {}
        var ci = opt.credentials; if (ci !== 'include' && ci !== 'omit') ci = 'include';
        var init = { method: String(opt.method || 'GET').toUpperCase(), headers: h, credentials: ci, cache: 'no-cache', signal: ctrl ? ctrl.signal : undefined };
        if (init.method !== 'GET' && init.method !== 'HEAD') init.body = opt.body || undefined;
        fetch(url, init).then(function (resp) {
          clearTimeout(timer);
          var rt = String(opt.responseType || 'text');
          if (rt === 'arraybuffer' || rt === 'blob' || rt === 'base64') {
            resp.arrayBuffer().then(function (buf) {
              var bytes = new Uint8Array(buf), bin = '', i;
              for (i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
              done(toResponse({ ok: resp.ok, status: resp.status, body: typeof btoa !== 'undefined' ? btoa(bin) : bin, b64: true }, url, opt));
            })['catch'](function (e) { done(toResponse({ ok: false, status: resp.status, body: '', error: String(e) }, url, opt)); });
            return;
          }
          resp.text().then(function (t) {
            var r = toResponse({ ok: resp.ok, status: resp.status, body: t }, url, opt);
            try {
              var real = resp.headers.get('set-cookie');
              if (real) { r.setCookie = real; r.cookies = [real]; }
              var hh2 = r.headers;
              r.headers = { get: function (k) { try { return resp.headers.get(k); } catch (e) { return null; } } };
              if (hh2 && hh2.map) r.headers.map = hh2.map;
            } catch (e) {}
            done(r);
          })['catch'](function (e) { done(toResponse({ ok: false, status: resp.status, body: '', error: String(e) }, url, opt)); });
        })['catch'](function (e) {
          clearTimeout(timer);
          done(toResponse({ ok: false, status: 0, body: '', error: String(e && e.message || e) }, url, opt));
        });
      } catch (e) { done(toResponse({ ok: false, status: 0, body: '', error: String(e) }, url, opt)); }
    });
  }

  /** 主流契约：单参 fm.req 保持同步字符串（老 spider 依赖）；带 options 时宿主 OkHttp 优先（对齐 WebHome 官方：
   *  网络统一走 fm.req，浏览器 fetch 仅作兜底）——站点 CDN 会拦 WebView/OkHttp 特征以外的预检与请求，
   *  宿主通道可伪装手机浏览器 UA + 浏览器请求头，并在 403 时换 UA 重试；宿主异常才回退原生 fetch。 */
  fm.req = function (url, opt) {
    var hasOptions = arguments.length >= 2 && opt && typeof opt === 'object';
    if (!hasOptions) {
      var text = '';
      try { text = B().req(url, '', 'GET', ''); } catch (e) { fm.log('fm.req failed: ' + e); }
      return text || '';
    }
    /* 主流 WebHome 页面（如 wogg）自带同源请求：优先走 WebView 原生 fetch，
     * 自动带页面 Cookie / Referer / Origin；被 CORS / 混合内容 / 超时拦下时，
     * 才回退宿主 OkHttp 桥接（伪装 UA + 403/429 换桌面 UA 重试）。 */
    var np = nativeRequest(url, opt);
    return np.then(function (nr) {
      if (nr && nr.status !== 0) return nr;
      fm.log('fm.req native unavailable, fallback host: ' + (nr ? nr.error : 'null') + ' ' + url);
      return asyncRequest(url, opt).then(function (hr) {
        if (hr && hr.status !== 0 && hr.status !== 403 && hr.status !== 429) return hr;
        fm.log('fm.req host blocked (' + (hr ? hr.status : 'null') + '), use native result ' + url);
        return nr;
      });
    });
  };

  /** 跨域资源网关：把任意 http(s) url 转成宿主文件服务上的同源地址 */
  fm.res = function (url, opt) {
    opt = opt || {};
    var base = safe(function () { return B().gatewayBase(); }, '');
    if (!/^https?:\/\//i.test(String(url))) return url;
    if (!base) return url; // 网关未就绪时直连原图，避免相对地址拼错导致裂图
    // 一律走同源网关（对齐官方：防盗链图需带 Referer，且页面在 127.0.0.1 时直连会跨源）
    var q = base + 'webResource?url=' + encodeURIComponent(String(url));
    if (opt.headers) {
      try { q += '&headers=' + encodeURIComponent(JSON.stringify(opt.headers)); } catch (e) {}
    }
    if (opt.credentials === 'include') q += '&credentials=include';
    return q;
  };

  /** 不劫持原生 fetch：保留浏览器异步与速度；需要宿主网络栈时显式 fm.request */
  fm.fetch = function (url, opt) { return fm.request(url, opt); };

  /** 站点 ext 持久化 */
  fm.getVar = function (k, def) {
    var v = safe(function () { return B().getVar(k); }, '');
    return (v === '' || v === null || v === undefined) ? def : v;
  };

  fm.setVar = function (k, v) {
    safe(function () { B().setVar(k, String(v)); }, null);
  };

  /** 回传结果（老 spider 的异步场景） */
  fm.done = function (result) {
    if (_done) return;
    _done = true;
    try {
      B().done(typeof result === 'string' ? result : JSON.stringify(result == null ? {} : result));
    } catch (e) {
      fm.log('fm.done failed: ' + e);
      safe(function () { B().done('{}'); }, null);
    }
  };

  /* ---------------- 主流 WebHome SDK 契约 ---------------- */

  /** Native 持久化，跨 origin、跨 App 重启；只存字符串 */
  fm.cache = {
    get: function (key, rule) {
      return Promise.resolve(safe(function () { return B().cacheGet(str(key), str(rule)); }, ''));
    },
    set: function (key, value, rule) {
      return Promise.resolve(safe(function () { B().cacheSet(str(key), str(value), str(rule)); return {}; }, {}));
    },
    del: function (key, rule) {
      return Promise.resolve(safe(function () { B().cacheDel(str(key), str(rule)); return {}; }, {}));
    },
    remove: function (key, rule) { return fm.cache.del(key, rule); },
    clear: function (rule) {
      return Promise.resolve(safe(function () { B().cacheClear(str(rule)); return {}; }, {}));
    }
  };

  /** 页面返回：优先 WebView 内部 goBack，没有上一级才关闭承载页 */
  fm.back = function () {
    return Promise.resolve(safe(function () { B().back(); return {}; }, {}));
  };

  fm.reload = function () {
    return Promise.resolve(safe(function () { B().reload(); return {}; }, {}));
  };

  fm.toast = function (msg) {
    return Promise.resolve(safe(function () { B().toast(str(msg)); return {}; }, {}));
  };

  /** 站点 / 配置 / 设备信息：主流页面据此判断是否在 App 内、取当前站点 */
  fm.site = function () {
    return Promise.resolve(parse(safe(function () { return B().siteInfo(); }, '{}'), {}));
  };
  fm.config = function () {
    return Promise.resolve(parse(safe(function () { return B().configInfo(); }, '{}'), {}));
  };
  fm.device = function () {
    return Promise.resolve(parse(safe(function () { return B().deviceInfo(); }, '{}'), {}));
  };

  /** 播放直链 / 打开站点影片 / 临时多集 */
  fm.play = function (url, title, options) {
    return Promise.resolve(safe(function () {
      B().open('play', JSON.stringify({ url: str(url), title: str(title), options: obj(options) }));
      return {};
    }, {}));
  };
  fm.vod = function (siteKey, vodId, title, pic, options) {
    return Promise.resolve(safe(function () {
      B().open('vod', JSON.stringify({ siteKey: str(siteKey), vodId: str(vodId), title: str(title), pic: str(pic), options: obj(options) }));
      return {};
    }, {}));
  };
  fm.vodInline = function (payload) {
    return Promise.resolve(safe(function () {
      B().open('vodInline', JSON.stringify(obj(payload)));
      return {};
    }, {}));
  };
  fm.preloadArtwork = function () { return Promise.resolve({}); };

  fm.search = function (keyword, options) {
    return Promise.resolve(safe(function () {
      B().open('search', JSON.stringify({ keyword: str(keyword), options: obj(options) }));
      return {};
    }, {}));
  };
  fm.openVod = function () {
    return Promise.resolve(safe(function () { B().open('openVod', '{}'); return {}; }, {}));
  };
  fm.openLive = function () {
    return Promise.resolve(safe(function () { B().open('openLive', '{}'); return {}; }, {}));
  };
  fm.openKeep = function () {
    return Promise.resolve(safe(function () { B().open('openKeep', '{}'); return {}; }, {}));
  };
  fm.openSetting = function () {
    return Promise.resolve(safe(function () { B().open('openSetting', '{}'); return {}; }, {}));
  };
  fm.history = function () { return Promise.resolve([]); };

  fm.ctrl = function (action) {
    return Promise.resolve(safe(function () { B().ctrl(str(action)); return {}; }, {}));
  };
  fm.stat = function () { return Promise.resolve({}); };

  fm.ui = {
    setToolbar: function (visible) {
      return Promise.resolve(safe(function () { B().chrome(JSON.stringify({ mode: visible ? 'normal' : 'hidden' })); return {}; }, {}));
    },
    setChrome: function (options) {
      return Promise.resolve(safe(function () { B().chrome(JSON.stringify(obj(options))); return {}; }, {}));
    },
    restoreChrome: function () {
      return Promise.resolve(safe(function () { B().chrome('{"mode":"restore"}'); return {}; }, {}));
    },
    getViewport: function () {
      return {
        width: window.innerWidth || 0,
        height: window.innerHeight || 0,
        density: window.devicePixelRatio || 1,
        safeTop: 0, safeBottom: 0, keyboardBottom: 0
      };
    }
  };

  fm.pan = {
    check: function (items) {
      return Promise.resolve({ supported: true, items: asArray(items), results: [] });
    },
    play: function (opt) {
      opt = obj(opt);
      return fm.play(opt.url, opt.title || '', {
        pic: opt.pic || '', wallPic: opt.wallPic || '', headers: opt.headers || {}
      });
    }
  };
  fm.check = function (items) { return fm.pan.check(items); };

  fm.extInfo = function () {
    return Promise.resolve(parse(safe(function () { return B().extInfo(); }, '{}'), {}));
  };
  fm.ext = Object.assign(function () {
    return safe(function () { return B().ext(); }, '');
  }, {
    info: function () { return fm.extInfo(); },
    log: function (m) { fm.log(m); },
    toast: function (m) { return fm.toast(m); }
  });

  /** SDK 就绪事件：主流 WebHome 页面等它才初始化。
   *  去重标记放全局，因为宿主会在 onPageStarted/onPageFinished 重复注入本文件，
   *  局部闭包标记会失效导致 fmsdk 派发两次、页面重复初始化。 */
  window.__fmSdkBoot = function () {
    if (window.__fmSdkFired) return;
    window.__fmSdkFired = true;
    try { if (document.documentElement) document.documentElement.classList.add('fm-native'); } catch (e) {}
    try { window.dispatchEvent(new CustomEvent('fmsdk')); } catch (e) {}
  };

  window.fongmiClient = { mode: 'mobile', isLeanback: false };
  window.fongmi = {
    net: { request: fm.request, resourceUrl: fm.res },
    player: { playUrl: fm.play, playVod: fm.vod, playVodInline: fm.vodInline, control: fm.ctrl, status: fm.stat },
    app: { search: fm.search, openVod: fm.openVod, openLive: fm.openLive, openKeep: fm.openKeep, openSetting: fm.openSetting, history: fm.history },
    cache: fm.cache,
    ui: fm.ui,
    site: { info: fm.site },
    config: { info: fm.config },
    navigation: { back: fm.back, reload: fm.reload }
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { window.__fmSdkBoot(); });
  } else {
    setTimeout(function () { window.__fmSdkBoot(); }, 0);
  }
  window.addEventListener('load', function () { window.__fmSdkBoot(); });

  /* ---------------- 对齐 webhtv devkit 的兼容层 ---------------- */

  function asArray(v) {
    return Object.prototype.toString.call(v) === '[object Array]' ? v : [];
  }

  /** 原生检测：webhtv 页面用 window.fongmiBridge 判断是否在 App 内。
   *  我们宿主注入的 JS 接口名是 FmBridge，这里补一个同名门面，避免页面
   *  误判为浏览器 → 走 fetch fallback → CORS 失败（「资源解析服务请求失败」）。 */
  try {
    var _b = B();
    if (_b) {
      var fb = window.fongmiBridge || {};
      ['requestAsync', 'resultLength', 'resultChunk', 'clearResult', 'req', 'log',
       'cacheGet', 'cacheSet', 'cacheDel', 'cacheClear', 'gatewayBase', 'siteInfo',
       'configInfo', 'deviceInfo', 'extInfo', 'back', 'reload', 'toast', 'chrome',
       'ctrl', 'open', 'done', 'siteKey', 'ext', 'getVar', 'setVar'].forEach(function (n) {
        if (typeof _b[n] === 'function' && typeof fb[n] !== 'function') {
          fb[n] = function () { return _b[n].apply(_b, arguments); };
        }
      });
      /** 通用 RPC 降级：webhtv 扩展走 invoke(id, method, payload)，这里把已知
       *  method 映射到宿主能力并回吐结果；未知 method 返回安全空结构而不是抛错。 */
      fb.invoke = function (id, method, payload) {
        var data = {};
        try { data = JSON.parse(payload || '{}'); } catch (e) {}
        var out = {};
        try {
          if (method === 'net.request' || method === 'request') {
            fm.request(data.url, data).then(function (r) {
              fb.inlineResult && fb.inlineResult(id, JSON.stringify(r));
            });
            return;
          } else if (method === 'cache.get') out = { value: B().cacheGet(data.key, data.rule) };
          else if (method === 'cache.set') { B().cacheSet(data.key, data.value, data.rule); out = {}; }
          else if (method === 'site.info') out = parse(safe(function () { return B().siteInfo(); }, '{}'), {});
          else if (method === 'config.info') out = parse(safe(function () { return B().configInfo(); }, '{}'), {});
          else if (method === 'navigation.back') { B().back(); out = {}; }
          else if (method === 'app.search') { B().open('search', JSON.stringify(data)); out = {}; }
          else if (method === 'player.playUrl') { B().open('play', JSON.stringify(data)); out = {}; }
          else if (method === 'player.playVod') { B().open('vod', JSON.stringify(data)); out = {}; }
        } catch (e) { out = { error: String(e) }; }
        if (typeof fb.inlineResult === 'function') {
          try { fb.inlineResult(id, JSON.stringify(out)); } catch (e) {}
        }
      };
      fb.inlineResult = function (id, payload) {
        try { window.fongmiNative && window.fongmiNative.resolve(id, JSON.parse(payload)); }
        catch (e) {}
      };
      window.fongmiBridge = fb;
    }
  } catch (e) {}

  /** webhtv devkit 的 GM_* 别名：页面用到时不会 ReferenceError 中断初始化 */
  window.GM_getValue = function (k, def) {
    return fm.cache.get(k, 'gm').then(function (v) { return (v === '' || v == null) ? def : v; });
  };
  window.GM_setValue = function (k, v) { return fm.cache.set(k, String(v), 'gm'); };
  window.GM_deleteValue = function (k) { return fm.cache.del(k, 'gm'); };
  window.GM_listValues = function () { return Promise.resolve([]); };
  window.GM_log = function (m) { fm.log(m); };
  window.GM_addStyle = function (css) {
    try {
      var s = document.createElement('style');
      s.textContent = String(css || '');
      (document.head || document.documentElement).appendChild(s);
    } catch (e) {}
  };
  window.GM_xmlhttpRequest = function (details) {
    details = details || {};
    var p = fm.request(details.url, details);
    if (p && typeof p.then === 'function') {
      p.then(function (r) { if (typeof details.onload === 'function') details.onload(r); })
       .catch(function (e) { if (typeof details.onerror === 'function') details.onerror(e); });
    }
    return { abort: function () {} };
  };

  /** webhtv 自带 WebHome 页用 hasNative() 检测这两个函数；缺失会误判为浏览器，
   *  进而放弃 fm.req 走 CORS 受限的原生 fetch。这里补安全的 Promise 降级（宿主
   *  数据由页面自身 API + fm.req 获取，原生分类数据为空时不让页面崩）。 */
  fm.vodHome = function (arg) {
    return Promise.resolve(safe(function () {
      B().open('vodHome', JSON.stringify(obj(arg)));
      return { list: [], categories: [], class: [], filters: {}, page: 1, pagecount: 1 };
    }, { list: [], categories: [], class: [], filters: {} }));
  };
  fm.vodCategory = function (tid, page, arg) {
    return Promise.resolve(safe(function () {
      B().open('vodCategory', JSON.stringify({ tid: str(tid), page: page, arg: obj(arg) }));
      return { list: [], page: page || 1, pagecount: 1, limit: 0, total: 0 };
    }, { list: [], page: 1, pagecount: 1 }));
  };
  fm.openDetail = function (arg) {
    return Promise.resolve(safe(function () {
      B().open('openDetail', JSON.stringify(obj(arg)));
      return {};
    }, {}));
  };

  window.fongmi = window.fongmi || {};
  window.fongmi.pan = { play: fm.pan.play, check: fm.pan.check };
  window.fongmi.ext = { info: fm.ext.info, log: fm.ext.log, toast: fm.ext.toast };
  window.fongmi.device = { info: fm.device };
  window.fongmi.vodHome = fm.vodHome;
  window.fongmi.vodCategory = fm.vodCategory;
  window.fongmi.openDetail = fm.openDetail;

  window.fm = fm;
})();
