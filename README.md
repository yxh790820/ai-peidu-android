# AI 陪读 · 安卓版（PeiduDroid）

**English** | 中文

An Android reading assistant: **select text in any app → tap "Peidu" → a floating card answers** without leaving your book. APK ~3.8 MB, no Python / Termux / runtime needed.

- **Ask three ways**: selected text (system menu), floating ball → crop a screen region (scanned books, formulas, charts), or share from other apps
- **Save to Obsidian**: pick a folder once (SAF, **no storage permission**); each answer appends to today's `YYYY-MM-DD.md`, screenshots go to `attachments/`
- **Wikipedia lookup**: local Kiwix + official Wikipedia, any number of sources; simplified and traditional spellings are both queried, a Chinese query goes to the Chinese library first and a Latin one to the English library; the ☑ toggle in the toolbar is the master switch — when it's off the app says "Wikipedia is not enabled" instead of letting the model guess
- **Everything editable**: services (base / model / key / params) and prompt presets can be added, renamed, deleted; edit system prompt and template inline
- KaTeX math, dark / light theme, font size 11–22, live status line showing what it's doing right now
- Android 7.0+ · no API key bundled · [Releases](../../releases)
- 💻 Windows version: [ai-peidu](https://github.com/yxh790820/ai-peidu)

---

## 中文

把 [AI 陪读小窗](https://github.com/yxh790820/ai-peidu) 搬到手机上：**选中文字 → 点「陪读」→ 半屏小窗弹出来，原来的读书 App 还在上面**，问完点一下就退回，接着看。

> **💻 电脑版（Windows）**：[**AI 陪读小窗**](https://github.com/yxh790820/ai-peidu) —— 常驻托盘，`Ctrl+Alt+A` 取词、`Ctrl+Alt+S` 框选截图，同一套提示词档位和维基检索，还能一键存进 Obsidian。

![悬浮小窗](docs/screenshot.png)

> WebView 壳（界面是一个单文件 HTML）+ 原生只做系统入口、流式转发、窗口形态、截屏取图、维基检索、存笔记。APK 约 3.8 MB，手机不用装 Python / Termux / 任何运行库。

## 和电脑版的区别

| 能力 | 电脑版 | 安卓版 |
|---|---|---|
| 唤起 | `Alt+1` 全局热键取词 | 选中文字 → 系统菜单「陪读」；或悬浮球截屏框选 |
| 形态 | 桌面小窗 + 托盘常驻 | **悬浮小窗**：可拖动、可调大小、位置记忆 |
| 公式 | matplotlib 画成 PNG | **KaTeX** 矢量渲染（分数根号上下标都清楚） |
| 取图 | 屏幕框选截图 | 悬浮球 + 无障碍截屏 + 拖框裁切，只发框住那块 |
| 存笔记 | 一键写 Obsidian | 选一次目录后逐条「存笔记」，格式互通 |
| 维基 | 本地 Kiwix / 官方维基 | 同左，且是**多源列表**（可增删改、分别打钩） |
| 服务/档位 | 改配置文件 | 界面上增删改 |
| 全局热键 | Alt+1 / Alt+2 | 无热键，用系统入口替代 |

## 功能

### 三种问法

- **划词问**：任意 App 里长按选中文字 → 菜单里点「陪读」→ 小窗自动带着这段发问，答完点上面的原 App 区域就退回，**对话和上下文都保留**
- **截图问**：桌面上的蓝色「读」悬浮球点一下 → 屏幕定格 → **拖一个框框住要问的段落** → 只把那一块发给模型（扫描版书页、公式、图表都能问）
- **分享问**：别的 App 分享文字或图片给「陪读」，同样自动提问

### 三个能力

- **存笔记到 Obsidian**：⚙ 里选一次笔记目录（系统目录选择器，**不需要存储权限**），每条回答点「存笔记」→ 追加到当天 `YYYY-MM-DD.md`，截图写进 `attachments/`，正文用 `![[图]]`，Obsidian 里图文都能看
- **维基百科检索**：主界面 ☑ 打钩后，提问前先查维基。支持**多个源**（本地 Kiwix + 官方 Wikipedia，可增删改），按顺序试、拿到就停；回答顶部标「📖 参考维基百科：《条目》」。没打钩就明确说"没有开启查维基"，**不让模型凭记忆编**
- **检索细节（与电脑版一致）**：一个词会同时查**简体和繁体**两种写法（`三国演义` 也查 `三國演義`）；**打中文先查中文库、打英文单词先查英文库**，查不到再换另一种（英文结果会标注「下面是英文条目，请用中文回答」）；词条大小写不敏感，`china` 和 `China` 一样能查到
- **服务和档位都能自己管**：服务（地址/模型/Key/附加参数）和提示词档位都是列表，可添加、改名、删除；档位点「编辑」能直接看和改 system 与提问模板（`$content` 是选中文/图的占位）

### 界面

- **悬浮小窗**：卡片顶部横杠拖动、底边短横条改高度，位置记忆
- **九个内置档位**：陪读 / 翻译 / 润色 / 总结 / 拼音+简单解释 / 古文今译 / 古文纯译 / 截图转公式 / 维基百科
- **状态条**：输入框上方实时显示在干什么（`查维基 · 本地 Kiwix…` / `思考中 5s` / `生成中 8s · 1200 字`），蓝点呼吸表示还活着；30 秒没响应会提示"可能网络或服务有问题"
- **KaTeX 公式**：行内行间公式都能渲染，中文不会变方框
- 深色/浅色主题（可跟随系统）、字号 11–22 可调（默认 16）、回答一键复制、代码块单独复制

## 权限

只用两个，都可选：

| 权限 | 用途 | 不给会怎样 |
|---|---|---|
| 显示在其他应用上层 | 放那个蓝色悬浮球 | 球不出现，划词和分享照常 |
| 无障碍（截屏能力） | 点球时截屏 | 点球没反应 |

**不需要**存储权限（存笔记走系统目录授权）。App 首页会直接显示还差哪个权限，点「去开启」直达系统设置。

## 下载

到 [Releases](../../releases) 下载最新 APK（约 3.8 MB，Android 7.0+）。

**包里不含任何 API Key**，第一次打开点右上角 **⚙** 填接口地址 / 模型名 / Key 就能用。

## 构建

需要 **JDK 17 + Android SDK（platform 34、build-tools 34.0.0）+ Gradle 8.7**。

```bash
# 1) 配置你自己的服务（这个文件不会被提交）
cp app/src/main/assets/secrets.example.js app/src/main/assets/secrets.js
# 编辑 secrets.js，填 base / model / key（也可以留空，装完在 App 里填）

# 2) 可选：用你自己的签名库（没有就用系统 debug 签名）
cat > keystore.properties <<'EOF'
storeFile=/absolute/path/to/your.jks
storePassword=你的口令
keyAlias=你的别名
keyPassword=你的口令
EOF

# 3) 打包
gradle assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

依赖走阿里云 Maven 镜像（已写进 `settings.gradle`），国内直连即可。

## 安装

APK 传进手机 → 点开安装 → 系统提示「未知来源」时放行 → 打开 App，点右上角 **⚙** 填接口地址 / 模型名 / API Key。

`secrets.js` 只是把默认值预置进包里，装完在 App 里改同样有效。

## 代码结构

| 文件 | 作用 |
|---|---|
| `MainActivity.kt` | WebView 壳、系统入口（`ACTION_PROCESS_TEXT` / `ACTION_SEND` / 截屏回跳）、JS 桥、窗口形态 |
| `LlmClient.kt` | OkHttp 流式 SSE，逐块回调网页 |
| `FloatingBallService.kt` | 蓝色悬浮球（前台服务 + overlay），可拖动、截屏时自动避让 |
| `PeiduScreenService.kt` | 无障碍服务，只用来拿截屏能力（事件回调是空的） |
| `ShotHolder.kt` | 截屏结果的内存暂存（不落盘） |
| `WikiClient.kt` | 维基检索：Kiwix（直接命中 → 标题索引 → 全文；简繁两字形、按文字选语言库）/ 官方 MediaWiki API |
| `ZhConv.kt` | 简繁单字转换表（从 OpenCC 导出，脚本生成）给检索展查询词用 |
| `app/src/test/…/WikiClientTest.kt` | 回归测试：简繁转换、英文回退（本地桩服务器）、真库直取/全文兜底 |
| `NoteStore.kt` | 存笔记：SAF 目录 + `DocumentFile` 写 md 和 attachments |
| `index.html` | **全部界面**：HTML + CSS + JS（渲染、档位、服务、设置、拖动、流式、框选） |
| `prompts.js` | 九个内置档位 |
| `katex/` | KaTeX 公式引擎与字体（离线，1.5 MB） |

改界面只需要动 `index.html`，不用碰 Kotlin。

## 已知限制

- 悬浮小窗是**透明 Activity**，不是 `SYSTEM_ALERT_WINDOW` 悬浮窗 —— 好处是不用开「显示在其他应用上层」权限，代价是点窗口外那一下只用来关闭小窗，不会同时点到底下的 App
- 悬浮球在系统「设置」页会被隐藏（Android 对非系统悬浮层的保护），回桌面就可见
- 重装 APK 会解绑无障碍，需要重新打开；App 会在首页提示并给一键跳转
- 官方维基需要能连外网（DNS 被污染的环境下用它没结果，切回本地 Kiwix 即可）
- 分享进来的图片要模型支持视觉：DeepSeek v4 支持 webp / png / jpeg / gif，格式不对或超出大小会报 `unsupported image`

## License

MIT
