/**
 * fm-api.js —— HTML 脚本（type 6）页面可用的原生桥接层。
 *
 * 范式说明（重要）：
 *   本 App 的 type 6 采用 WvWv 范式 —— html 是【页面】，自己渲染自己的内容，
 *   不向宿主返回数据。分类与宫格由 App 自己造，点卡片才把页面拉起来。
 *   所以这里【不会被调用 homeContent / detailContent 之类的爬虫函数】，
 *   只提供页面随时可用的原生能力（fm.req 等）。
 *
 * 脚本用法：
 *   var html = fm.req('https://...');            // 同步请求，自动走站点代理
 *   var v = fm.getVar('k', '默认值');             // 站点级持久化
 *   fm.setVar('k', 'value');
 *   fm.log('调试信息');                           // 输出到 App 日志
 *
 * 注意 fm.req 是【同步】的（Java 侧 OkHttp 同步执行后把正文返回给 JS），
 * 返回字符串。页面若用 Promise 包装（如 Promise.resolve().then(...).then(res => ...))，
 * 字符串也能正常往下走。
 *
 * 关于 fetch：这里【不覆盖】 window.fetch。
 *   页面自己的 fetch 是完整的（含 headers / status / 流式），而我们的垫片是
 *   同步取文本再伪造 Response，覆盖后会破坏页面的正常网络行为。
 *   代理不需要靠覆盖 fetch 实现 —— 子资源请求已由 WebView 的
 *   shouldInterceptRequest 统一接到 App 网络栈，对页面完全透明。
 */
(function () {
  // 允许重复执行：页面重定向/多次注入时重新绑定方法即可，不能写 "if (window.fm) return;"
  var fm = window.fm;
  if (!fm || typeof fm !== 'object') {
    fm = {};
    window.fm = fm;
  }

  fm.log = function (m) {
    try { window.FmBridge.log(String(m)); } catch (e) {}
  };

  fm.siteKey = function () {
    try { return window.FmBridge.siteKey(); } catch (e) { return ''; }
  };

  fm.ext = function () {
    try { return window.FmBridge.ext(); } catch (e) { return ''; }
  };

  /**
   * 同步 HTTP 请求。走 App 自己的网络栈，所以站点代理开关对它同样生效。
   * @param {string} url
   * @param {object} opt  { method, headers:{}, body }
   * @returns {string} 响应正文；失败返回空串
   */
  fm.req = function (url, opt) {
    opt = opt || {};
    var headers = '';
    try { headers = JSON.stringify(opt.headers || {}); } catch (e) {}
    try {
      return window.FmBridge.req(url, headers, opt.method || 'GET', opt.body || '');
    } catch (e) {
      fm.log('fm.req failed: ' + e);
      return '';
    }
  };

  fm.get = function (url, headers) {
    return fm.req(url, { method: 'GET', headers: headers });
  };

  fm.post = function (url, body, headers) {
    return fm.req(url, { method: 'POST', body: body, headers: headers });
  };

  /** 站点 ext 持久化（对应修改扩展参数弹窗写入的值） */
  fm.getVar = function (k, def) {
    try {
      var v = window.FmBridge.getVar(k);
      return (v === '' || v === null || v === undefined) ? def : v;
    } catch (e) { return def; }
  };

  fm.setVar = function (k, v) {
    try { window.FmBridge.setVar(k, String(v)); } catch (e) {}
  };

  /**
   * 展示模式下的兼容实现：页面若仍调用 fm.done 也不会报错。
   * 宿主不再等待回传结果，这里只记日志。
   */
  fm.done = function (result) {
    try {
      window.FmBridge.done(
        typeof result === 'string' ? result : JSON.stringify(result == null ? {} : result)
      );
    } catch (e) {
      try { fm.log('fm.done failed: ' + e); } catch (e2) {}
    }
  };

  /** 复位（保留给需要重复调用的页面，展示模式下无副作用） */
  fm.reset = function () {};

  window.fm = fm;
})();
