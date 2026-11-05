## 1.5.5-hotfix1-xbox
- 修复「详情页播放」开关开启后看不到带播放窗口的详情页：播放窗由长列表末尾改为固定在选集正上方，内容上移到可滚动区，页面一打开即可见
- 开关改由宿主在创建详情页时统一读取，覆盖宫格 / 历史 / 收藏 / 第三方脚本等所有入口；关闭时仍按上游两步逻辑
- 带播放窗的详情页保留返回 / 收藏顶栏；播放器在内嵌窗与全屏浮层之间切换前先摘除旧父容器，避免抢父容器导致不显示或崩溃
- 只要拿到可播放选集就触发内嵌播放，线路较晚返回时也能补触发
- 本轮不动 html 网络层；包名不变（com.github.xbox.osc）

---

## 1.5.5-xbox
- 设置项「解析到视频后自动横屏」改为「详情页播放」开关（独立新键、默认开启）；开启时点击宫格直接进带播放的详情页，关闭时按原逻辑先进详情页再点播放
- 带播放的详情页：补固定顶栏返回 / 收藏按钮；播放窗内嵌并下移到选集正上方，原本夹在播放器与选集之间的线路 / 源 / TMDB 内容上移
- 保留画中画全套逻辑；退出全屏不做方向强制，横屏播完返回保持横屏、竖屏播完返回保持竖屏；不再由设置项强制自动横屏
- 设置页保持我们的版本；包名不变（com.github.xbox.osc）
- 远程 html：主文档代取补浏览器请求头 / Cookie 与 403 换桌面 UA 重试；fm.res 网关地址不可用时回退原图地址，避免相对路径裂图

---

## 1.3.4.11-xbox
- 远程 html：fm.req 带 options 改走浏览器原生 fetch（自带页面 Cookie、Referer），原生失败（CORS/混合内容/超时）才回退宿主桥接
- 宿主桥接兜底请求始终补 CookieManager 里该域的 Cookie，Set-Cookie 写回后登录态不再丢
- fm.res：无自定义头且非 include 的图片直接返回原始地址由 WebView 加载，不再绕网关导致裂图
- 不删旧 html spider 单参同步 fm.req，行为保持原样；包名不变


## 1.3.4.10-xbox
- 远程 html 对齐 webhtv 的异步 WebHome 桥：fm.req(url, options) / fm.request 改为 Native 后台 OkHttp + fongmiNative.resolve 回调，不再同步阻塞 WebView JS 线程；多请求可并发，长响应自动分片还原
- fm 桥补结构化响应契约（ok/status/url/headers/cookies/body/error、responseType text/json/base64、timeout、headers.get、text()/json()），老 spider 的单参同步 fm.req 行为不变
- py 弹窗：hidden API 放行提前到 Application.onCreate 最早并全量放行，附反射探测日志；PythonLoader 幂等复用同一兼容层
- 包名保持不变（applicationId = com.github.xbox.osc）

## 1.3.4.9-xbox
- 远程 html：主文档开头内联注入宿主 SDK（对齐 webhtv document-start），页面脚本运行前 window.fm 已就位；DOM 就绪后派发 fmsdk 事件，不再卡在「准备加载…」
- fm 桥升级到主流 WebHome 契约：fm.req(url, options) / fm.request 返回 Promise 结构化响应，补 fm.cache / fm.res / fm.back / fm.reload / fm.play / fm.vod / fm.site / fm.config / fm.device / fm.ctrl / fm.ui 等；老 spider 的同步 fm.req(url) 与 fm.done 保持不变
- 主帧放行条件请求头、去掉 no-store，二次打开可走 304/缓存，不再全量回源
- 画中画上一集/下一集：改走 owner（PlaybackHostApi），与 MediaSession 同一条链路；原先调用的 PlaybackEngine.playNext/playPrevious 是空实现
- 播放页前台判定放宽到 STARTED，三种播放页互相切换的中间态不再被误判为无前端而停播
- 站点管理：分类筛选下按可见序列重排，选中项与可见邻居交换，不再出现「移动无效」的错觉
- py 弹窗：hidden API 豁免前缀补全 + 备用注入路径 + builtins 别名

## 1.3.4.8-xbox
- 参照 webhtv 修复 html 页面：补 WebView 生命周期（onPause/onResume/resumeTimers）、关闭时立即清理静态引用、撤掉旧页面 reorder 复用，返回宫格后旧脚本不再残留
- 在线 html 主文档改由宿主共享 OkHttp 代取：明确超时、主帧强制 text/html、剥离 CSP/X-Frame-Options 等阻断头、失败给可见提示；子资源仍交回 WebView 原生栈
- 新增 15 秒加载超时自动重建 WebView 与渲染进程崩溃自愈；不再劫持 window.fetch，恢复浏览器原生异步速度
- 宿主新增 /webResource 同源网关与 fm.res()，跨域请求由宿主 OkHttp 代发并加 CORS
- py 宿主放行 ActivityThread 相关 hidden API，兼容自行反射取 Activity 弹窗的子程序；失败时静默跳过

---

## 1.3.4.7-xbox
- 同步上游 main 依赖升级（AGP 9.5.0 / Chaquopy 17.2.0 / Gson 2.15.0），并完成构建验证
- 本次仅推进版本号，未改动功能代码；webhtv 参照修复等暂缓项保持暂停

---

## v1.3.0
- 修复音乐页嗅探错误的问题
- 修复详情页倒序切集不生效的问题
- 修复唤出投屏面板时状态栏闪烁的问题
- 增加直播配置的联网更新机制

---

## v1.2.9
- 修复网盘源点进文件夹后显示成其他影片
- 优化错误提示
- 优化超时处理
- 优化搜索框的焦点处理
- 优化订阅配置的缓存更新逻辑
- 优化软解回退逻辑，失败自动回退硬解
- 优化错误处理逻辑，分离视频和音频的解码错误
- 优化崩溃检测，避免因导入未适配高版本安卓的 jar 源造成无限闪退
- 详情页的弹窗改为固定高度
- 搜索结果页左侧列表新增过滤站源功能
- 支持本地导入 js 源
- 支持播放 iso 格式的蓝光原盘（力竭了😭）

---

## v1.2.8
- 优化平板详情页的 TMDB 海报效果
- 一些 ui 层面上的小修改

---

## v1.2.7
- 修复已知问题
- 继续优化 TMDB 匹配规则
- 开启压缩原生库，减小安装包体积

---

## v1.2.6
- 修复已删除的历史条目自动复活
- 修复弹幕不会跟随播放倍速滚动
- 修复暂停状态重进播放页时弹幕不加载
- 修复进入播放后无法自动加载弹幕，要拖动一次进度条才出现
- 优化弹幕效果和流畅度，屏蔽底部弹幕，支持自适应刷新率
- 优化详情页 TMDB 海报标题的效果
- 一些 ui 层面上的小修改

---

## v1.2.5
- 优化 TMDB 匹配规则
- 优化 TMDB 海报效果，根据设备分辨率自动匹配合适清晰度的海报
- 优化对坏源的判断逻辑，避免被识别为格式错误，导致自动换线失效

---

## v1.2.4
- 修复自动换线在全屏播放下失效
- 优化自动换线效果
- 优化进度条记忆和历史记录统计逻辑
- 优化起播失效后的恢复逻辑，保留同 URL 重试，失败后自动刷新直链

---

## v1.2.3
- 优化细节
- 优化 R8 规则，减小应用体积
- 完整更新日志详见三个alpha版本https://github.com/XiaochangXu/AVBox/releases

---

## v1.2.3-alpha03
> 此版本为测试版，可能存在不稳定问题。
- 修复残留容器遮挡
- 修复跨内容陈旧状态
- 修复退后台回前台黑屏
- 修复画质调色和超分开关关闭后旧效果链残留
- 优化播放内核和解码器的复用逻辑
- 优化进入退出全屏播放的动画效果
- 支持 TMDB 海报和元数据，配置管理 → TMDB → 添加密钥
- 重构播放器界面，支持更丝滑的动画效果
- 新增隐藏状态栏

---

## v1.2.3-alpha02
> 此版本为测试版，可能存在不稳定问题。
- 修复n个bug
- 优化自动横竖屏的判断逻辑
- 优化平板布局

---

## v1.2.3-alpha01
> 此版本为测试版，可能存在不稳定问题。
- 拆分上帝文件
- 技术栈迁移 Java → Kotlin
- 状态管理迁移 LiveData → Flow/StateFlow
- 移除 View 手势控制，改 Compose Modifier 手势
- 重写播放层，移除 dkplayer
- 重写网络调用层，移除 okgo
- 优化搜索排序
- 首页支持切换订阅源
- 历史和收藏合并，新增追剧记录
- 首页新增沉浸式轮播海报
- 重写了影视详情页
