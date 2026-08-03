# 拾音（ShiYin）

一个界面向主流音乐 App 看齐的安卓音乐播放器，主打「点歌词即跳转」：

> ### 💝 喜欢这个项目？支持一下作者
> 拾音完全免费、无广告、无任何内购。如果你喜欢它，欢迎来爱发电请作者喝杯咖啡，每一份鼓励都是继续更新的动力 ❤️
> **爱发电主页：https://www.ifdian.net/a/ruozhi521**

- 🗂️ **指定一个文件夹**（系统文件选择器，无需任何权限），自动扫描里面的音频
- 📁 **自动归类成歌单**：大文件夹下每个子文件夹自动成为一个歌单，还有「全部歌曲」入口
- 🎵 支持 MP3 / M4A / WAV / FLAC / AAC / OGG 等常见格式（本地文件，超长音频无压力）
- 📝 **自动检索歌词**：同一文件夹里的 `.lrc` / `.vtt` 文件自动匹配到同名音频（支持 `歌名.lrc` 和 `歌名.mp3.vtt` 两种命名）
- 👆 **点击任意一行歌词，立即跳转到对应时间点并播放**
- ▶️ 播放中当前歌词行自动高亮、自动滚动；支持上一首/下一首、列表循环、进度条拖动
- 🔔 **稳定后台播放**：播放器运行在前台服务里，退到后台、锁屏都不中断；通知栏显示歌曲名和播放/暂停/上一首/下一首按钮，锁屏界面也能控制；来电或其他 App 播放音乐时自动暂停，挂断后恢复
- ⏱️ **定时关闭**：播放页时钟按钮可设 15/30/45/60 分钟后自动暂停，助眠神器
- ⚙️ **启动扫描开关**：设置里可关闭「启动时自动扫描文件夹」，改为秒开上次扫描结果，手动点重新扫描更新
- 🖼️ **封面显示**：自动读取音乐内嵌封面，播放页显示大封面，歌单列表显示第一首音乐的封面缩略图
- ⏯️ **断点续播**：大退重开后自动恢复到上次播放的歌曲与进度（暂停态，点播放继续）
- 🎨 **自定义外观**：设置里可切换深色/浅色双主题，可调歌词字号、界面字号、歌词字体
- 🔍 **搜索**：按歌单名或歌曲名实时搜索，点歌单进歌单、点单曲直接播放
- 🏠 **发现页**：随机推荐 8 首歌曲的大封面卡片（封面为主、歌名次位），一键「换一批」
- 🎵 **音乐库**：底部导航「发现 / 音乐库」双页签；音乐库内「歌单 / 歌手」分段，歌单 2 列大封面网格，歌手按音频元数据自动分类（首次进入读取几秒，之后秒开）
- 💿 **播放页**：CD 旋转动画大封面 + 当前歌词行；点「词」进入全屏歌词页（当前行高亮、点击跳转）
- 🎶 播放页像音乐播放器：大号歌词居中显示 + 底部控制条；歌单页底部有迷你播放条
- 🔄 支持 GBK / UTF-8 编码的歌词文件（中文不乱码）
- 💾 记住上次选择的文件夹，下次打开自动恢复

---

## 一、你的电脑上要做什么？

**什么都不用装。** 整个编译在 GitHub 的免费云端服务器完成（GitHub Actions），你只需要有一个 GitHub 账号。

> 没有账号？到 https://github.com 用邮箱注册一个（免费），几分钟搞定。

---

## 二、把工程上传到 GitHub（约 5 分钟）

### 第 1 步：新建仓库

1. 登录 GitHub，点右上角 **+** → **New repository**
2. Repository name 随意填，比如 `subtitle-player`
3. 选 **Private**（私有，推荐）或 Public 都行
4. 其他不要动，点 **Create repository**

### 第 2 步：上传本文件夹里的所有内容

1. 新建好的仓库页面里，点 **Add file** → **Upload files**
2. 把 `subtitle-player` 文件夹里的**所有文件和文件夹**拖进去：
   - `settings.gradle`、`build.gradle`、`gradle.properties`
   - `app` 文件夹（整个拖进去）
   - `.github` 文件夹（如果网页拖拽没有显示/传不上去，见下面的「备用方法」）
3. 点 **Commit changes**（直接确认即可）

> ⚠️ **注意**：网页上传有时会把以 `.` 开头的隐藏文件夹（`.github`）漏掉。传完后到仓库首页检查一下，如果看不到 `.github` 文件夹，就执行备用方法 👇

**备用方法（手动创建 workflow 文件）：**

1. 在仓库首页点 **Add file** → **Create new file**
2. 文件名（Name your file...）里输入：`.github/workflows/build-apk.yml`（输入时会自动创建 `.github/workflows` 文件夹）
3. 把下面这段内容**完整复制粘贴**进去：

```yaml
name: Build APK

on:
  push:
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v3
        with:
          gradle-version: '8.2'

      - name: Show versions
        run: gradle --version

      - name: Build debug APK
        run: gradle assembleDebug

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: app-debug
          path: app/build/outputs/apk/debug/app-debug.apk
```

4. 点 **Commit changes**

### 第 3 步：等云端编译，下载 APK

1. 打开仓库页的 **Actions** 标签（顶部菜单）
2. 你会看到一次名为 **Build APK** 的任务正在运行（黄色转圈）→ 变绿勾 = 成功，红色 ✕ = 失败
3. 大概 2~5 分钟。成功后点进这条任务，最下面有 **Artifacts** 区域，点 **app-debug** 下载
4. 下载的是一个 zip 压缩包，解压后里面就是 **app-debug.apk**

> 💡 如果 Actions 页面提示被禁用：仓库 **Settings → Actions → General → Allow all actions and reusable workflows**，然后再去 Actions 标签重新跑一次（页面有个 **Run workflow** 按钮）。

---

## 三、装到手机上

1. 把 `app-debug.apk` 发到手机：微信/QQ 发文件、网盘、数据线都行
2. 手机文件管理器里点这个 apk 文件安装
3. 如果提示「禁止安装未知应用」，按提示去设置里允许（不同手机提示文案略有不同：华为/小米/OPPO/vivo 都是「允许安装未知来源应用」）
4. 装好后打开「拾音」

---

## 四、怎么用

1. 打开 App → 点 **选择文件夹**，在手机文件管理器里选一个**大文件夹**（比如你放课程音频的文件夹，它的子文件夹会自动成为歌单）
2. 等 1~2 秒自动扫描完成，主界面出现歌单列表（「全部歌曲」+ 每个子文件夹一个歌单）
3. 点进歌单 → 点任意一首歌 → 进入播放页自动播放
4. **点击歌词任意一行，立即跳到那一段并播放**；播放时当前行高亮并滚动到屏幕中间
5. 底部控制：上一首 / 播放暂停 / 下一首；拖动进度条可随意跳转
6. 歌单页底部有**迷你播放条**，随时点开回到播放页

小提示：

- 歌词自动匹配：把 `歌名.lrc` 或 `歌名.vtt`（或 `歌名.mp3.lrc`）放在**和音频同一个文件夹**里即可，无需手动选择
- 文件夹里新增/删除了文件，点 **重新扫描** 刷新
- 想换一个大文件夹，再点 **选择文件夹** 即可
- 每个子文件夹 = 一个歌单；根目录里散落的音频自动归到「根目录」歌单

---

## 五、常见问题

**Q：构建失败（Actions 显示红色）怎么办？**
A：点进失败的任务，把红色错误日志页面截图发给我，我来修。一般几行日志就能定位。

**Q：支持后台播放吗？**
A：支持。播放器跑在前台服务里，按 Home 键或锁屏都不中断，通知栏和锁屏界面可以控制播放/暂停/切歌。首次播放时手机可能弹「允许通知」的请求，**请允许**，否则通知栏控制条不显示（后台播放本身不受影响）。

**Q：为什么是 debug 版？**
A：debug 版自带调试签名，无需任何配置即可安装，功能和正式版完全一样。正式签名版需要你自己的密钥，以后需要再说。

**Q：支持超长音频吗？**
A：支持。系统 MediaPlayer 是流式播放，几十分钟到几小时的音频都没问题，拖动跳转也很快。

**Q：可以用电脑上现有的文件吗？**
A：可以，把 MP3、vtt、lrc 文件传到手机再选即可。

---

## 开发信息（给懂的人）

- 语言：Kotlin
- 构建：AGP 8.2.2 / Gradle 8.2 / JDK 17 / Kotlin 1.9.22
- minSdk 26（Android 8.0+），targetSdk / compileSdk 34
- 依赖：仅 androidx core-ktx / appcompat / recyclerview，无第三方库
- 开源协议：**MIT License**（详见 [LICENSE](LICENSE)）
- 源码结构：

```
app/src/main/java/com/example/subtitleplayer/
├── MainActivity.kt      播放控制、文件选择、进度与高亮
├── SubtitleParser.kt    VTT/LRC 解析（自动识别格式、GBK/UTF-8）
├── SubtitleLine.kt      行数据
└── SubtitleAdapter.kt   列表适配器
```
