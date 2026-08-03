# 拾音（ShiYin）

一个界面向主流音乐 App 看齐的安卓本地音乐播放器，主打「**点歌词即跳转**」：点哪句歌词，就跳到哪一句。

> ### 💝 喜欢这个项目？支持一下作者
> 拾音完全免费、无广告、无任何内购。如果你喜欢它，欢迎来爱发电请作者喝杯咖啡，每一份鼓励都是继续更新的动力 ❤️
> **爱发电主页：https://www.ifdian.net/a/ruozhi521**

## ✨ 功能亮点

- 🎯 **点击歌词即跳转**：点任意一句歌词立即跳到对应时间点播放，跟唱、精听神器
- 📁 **文件夹即歌单**：指定一个大文件夹，子文件夹自动归类成歌单
- 📝 **歌词全自动匹配**：同名 `.lrc` / `.vtt` 自动对应（支持 `歌名.lrc` 与 `歌名.mp3.lrc` 两种命名），GBK / UTF-8 中文不乱码
- 🎵 **歌手自动分类**：按音频元数据读取歌手并分组浏览
- 🏠 **发现页**：随机推荐大封面卡片，一键「换一批」
- 💿 **播放页**：CD 旋转动画封面 + 当前歌词行，点「词」进入全屏歌词页（高亮跟随、点击跳转）
- 🔔 **稳定后台播放**：前台服务 + 通知栏/锁屏控制，来电自动暂停
- ⏯️ **断点续播**：大退重开后恢复到上次播放进度
- ⏱️ **定时关闭**：15/30/45/60 分钟后自动暂停
- 🎨 **深色 / 浅色双主题**，歌词字号、界面字号、歌词字体可调
- 🔍 **歌单 / 单曲实时搜索**
- 🖼️ **内嵌封面显示**：播放页 CD、歌单、歌曲列表全覆盖
- 🔒 **完全本地**：不联网、无广告、无账号、不收集任何数据，你的音乐只留在你的手机里
- ⏺️ 支持超长音频，MP3 / M4A / WAV / FLAC / AAC / OGG 等常见格式

## 📲 安装

在 [Releases](../../releases) 下载最新 APK（或从 GitHub Actions 的构建产物获取），传到手机安装。首次使用：

1. 打开 App → 「音乐库」→ 点搜索框旁的设置 → **更换文件夹**，选择你的音乐文件夹
2. 子文件夹自动成为歌单，同名歌词自动匹配
3. 点任意歌曲进入播放页，**点歌词任意一行即可跳转**

> 提示：手机可能提示「禁止安装未知来源应用」，在设置中允许即可（功能与正式版一致）。

## 🚀 从源码构建

环境要求：JDK 17 + Android SDK（compileSdk 34）。

```bash
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

仓库已配置 [GitHub Actions](.github/workflows/build-apk.yml)，推送后自动编译并上传 APK 产物，无需本地环境。

## 🧱 技术栈

- 语言：Kotlin
- 构建：AGP 8.2.2 / Gradle 8.2 / Kotlin 1.9.22 / JDK 17
- minSdk 26（Android 8.0+），targetSdk / compileSdk 34
- 依赖：仅 androidx core-ktx / appcompat / recyclerview，零第三方媒体库（媒体会话与通知使用平台 API）

## 📁 目录结构

```
app/src/main/java/com/example/subtitleplayer/
├── MainActivity.kt        界面与导航（发现/音乐库/播放/歌词多页面）
├── MediaPlaybackService.kt  前台播放服务（后台播放、通知栏/锁屏控制、定时关闭、断点持久化）
├── SubtitleParser.kt      VTT / LRC 解析（自动识别格式、GBK / UTF-8）
├── SubtitleLine.kt        歌词行数据
├── LibraryScanner.kt      文件夹扫描（歌单归类、歌词匹配）
├── LibraryCache.kt        扫描结果本地缓存（启动秒开）
├── ArtistLoader.kt        歌手元数据读取与持久化
├── CoverLoader.kt         内嵌封面读取（线程池 + 缓存）
├── Adapters.kt / UIAdapters.kt / SearchAdapter.kt  列表适配器
└── Model.kt               数据模型
```

## 🤝 开源协议

[MIT License](LICENSE) — 自由使用、修改、商用，保留版权声明即可。

## 💝 支持作者

拾音完全免费。如果它帮到了你，欢迎到 [爱发电](https://www.ifdian.net/a/ruozhi521) 支持作者，每一份鼓励都是继续更新的动力 ❤️
