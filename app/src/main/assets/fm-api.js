/**
 * fm-api.js —— HTML 站点运行时的宿主桥接层。
 *
 * 两套契约共存：
 *  1) TVBox html spider 老契约：fm.req(url) 同步返回正文字符串、fm.done(结果) 回传。
 *     首页/分类/详情这类脚本仍走这条路，行为一字不改。
 *  2) 主流 WebHome 契约（对齐 webhtv 的 Native SDK）：SDK 就绪后派发 fmsdk 事件，
 *     fm.req(url, options) / fm.request() 返回 Promise 结构化响应，
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

  /** 同步请求（老契约）；传了 options 时切到主流 Promise 契约 */
  fm.req = function (url, opt) {
    opt = opt || {};
    var headers = '';
    try { headers = JSON.stringify(opt.headers || {}); } catch (e) {}
    var text = '';
    var failed = false;
    try {
      text = B().req(url, headers, opt.method || 'GET', opt.body || '');
    } catch (e) {
      failed = true;
      fm.log('fm.req failed: ' + e);
    }
    if (arguments.length >= 2 && opt && typeof opt === 'object') {
      var r = makeResponse(text || '', !failed && (text || '') !== '', 0, url, failed ? 'request failed' : '');
      if (r.status === 0) r.status = r.ok ? 200 : 0;
      return Promise.resolve(r);
    }
    return text || '';
  };

  /** 总是返回 Promise 的结构化请求（主流 WebHome 显式通道） */
  fm.request = function (url, opt) {
    opt = opt || {};
    try {
      return Promise.resolve(fm.req(url, opt && typeof opt === 'object' ? opt : {}));
    } catch (e) {
      return Promise.resolve(makeResponse('', false, 0, url, String(e)));
    }
  };

  // 老契约的 get / post 必须同步返回正文，不能经过带 options 的 fm.req
  fm.get = function (url, headers) {
    return safe(function () {
      return B().req(url, headers ? JSON.stringify(headers) : '', 'GET', '');
    }, '');
  };

  fm.post = function (url, body, headers) {
    return safe(function () {
      return B().req(url, headers ? JSON.stringify(headers) : '', 'POST', str(body));
    }, '');
  };

  /** 跨域资源网关：把任意 http(s) url 转成宿主文件服务上的同源地址 */
  fm.res = function (url, opt) {
    opt = opt || {};
    var base = safe(function () { return B().gatewayBase(); }, '');
    if (!base || !/^https?:\/\//i.test(String(url))) return url;
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
    return parse(safe(function () { return B().siteInfo(); }, '{}'), {});
  };
  fm.config = function () {
    return parse(safe(function () { return B().configInfo(); }, '{}'), {});
  };
  fm.device = function () {
    return parse(safe(function () { return B().deviceInfo(); }, '{}'), {});
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
    check: function () { return Promise.resolve({}); },
    play: function () { return Promise.resolve({}); }
  };
  fm.check = function () { return Promise.resolve({}); };

  fm.extInfo = function () {
    return parse(safe(function () { return B().extInfo(); }, '{}'), {});
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

  window.fm = fm;
})();
