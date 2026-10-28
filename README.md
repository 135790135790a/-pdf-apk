# 漫画书架 MangaShelf

> 把 **PDF / 图片** 变成可以用手指翻的漫画书 —— 安卓原生，纯本地、无网络、无广告。

[**↓ 下载最新 APK**](https://github.com/135790135790a/-pdf-apk/releases/latest)　·　[功能](#1-功能清单)　·　[编译](#4-怎么编译)　·　[网页版](#9-网页版web)　·　[已知限制](#7-已知限制)

| | |
| :-- | :-- |
| **最新版本** | v1.5.5（versionCode 16） |
| **安装包** | 6.98 MB · 直接覆盖安装，数据不丢 |
| **系统要求** | Android 7.0+（minSdk 24），targetSdk 34 |
| **技术栈** | Kotlin · Jetpack Compose (Material 3) · Room · Coil · PdfRenderer |
| **权限** | 只申请前台服务相关的 3 条；不读存储、不联网 |
| **数据** | 全部在 App 私有目录，卸载即删；换机用设置里的「备份书架」 |

**怎么用**：导入一个 PDF → 逐页渲染成图片 → 进书架 → 点开就能左右翻页。
也可以一次选一堆图片（`001.jpg`、`002.jpg`…），按文件名顺序自动排成一本；或者选一个文件夹，整个文件夹成一本书。

> 附带一个 [`web/`](web/README.md) 网页版：电脑上双击 `web\start.bat`，或手机连同一 Wi-Fi 用浏览器看。

---

## 1. 功能清单

### 书架

| 能力 | 说明 |
| --- | --- |
| 三种视图 | 网格 / 列表 / 瀑布流，右上角切换并记住 |
| 搜索 | 匹配书名 / 作者 / 标签 |
| 排序 | 最近阅读 / 书名 / 页数，正倒序，选择持久化 |
| 继续阅读 | 顶部卡片直达上次读到的页，搜索时不打扰 |
| 封面 | 缩略图两档（400px / 1000px），快速滑动 250ms 防抖 |
| 主题 | 白天 / 夜晚（深灰，不是纯黑）/ 跟随系统，冷启动闪屏同步 |
| 长按菜单 | 改名、作者/标签、阅读方向与模式、页序、页面管理、保存到相册、删除 |
| 回收站 | 删除进回收站，30 天内可恢复 |
| 备份 | 设置里「备份书架」导出 zip（含图片），「从备份恢复」还原 |

### 导入

| 能力 | 说明 |
| --- | --- |
| 文件夹成书 | `OpenDocumentTree` 递归读，按相对路径自然排序（`2.png` 在 `10.png` 前、`ch2/` 在 `ch10/` 前） |
| 单个 PDF | 系统文件选择器（SAF），不需要存储权限，逐页渲染 1440px |
| 多张图片 | 按文件名排序，自动修正 EXIF 旋转，PNG 透明底合成白底 |
| 杂项忽略 | `说明.txt`、`Thumbs.db`、`.DS_Store` 自动跳过；坏图跳过并继续 |
| 失败回滚 | 导入失败不留垃圾目录 |
| 空间预检 | 导入前算空间，不够直接告诉你差多少 |
| 后台不掉 | 前台服务 + 屏幕常亮，切微信 / 锁屏不中断 |
| 断点恢复 | 任务落库，进程被杀重开后提示「上次有 N 本没导完」可一键重试 |
| 导入排序 | 自然 / 文件名 / 修改时间 / 大小 / 导入顺序，文件夹与图片可各设默认 |
| 页级进度 | 横幅与通知显示「正在渲染第 X / Y 页」，5 页或 300ms 节流 |
| 阅读预取 | 阅读时预取前后各一页，翻页不先白一下 |

### 阅读器

| 能力 | 说明 |
| --- | --- |
| 三种模式 | 左右翻页 / 上下滚动（条漫）/ 双页并排，按书保存 |
| 阅读方向 | 从右往左（日漫）/ 从左往右（国漫、美漫），按书切换 |
| 翻页方式 | 点两侧区域、横向滑动、音量键（可关）、底部滑杆跳页 |
| 手势 | 双指缩放、缩放后拖动、双击缩放 |
| 边界翻页 | 放大后拖到图片边界继续拖才翻页；拖不够就回弹，不误翻 |
| 灵敏度 | 低 · 不易误翻 / 中 · 默认 / 高 · 很灵敏 |
| 长按连翻 | 默认关；开了以后长按不存图，存图用工具栏「保存本页」 |
| 进度记忆 | 自动记住每本读到第几页，下次打开续读 |

---

## 2. 目录结构

```
漫画书翻译/                         ← 工程根目录，用 Android Studio 打开这一层
├─ settings.gradle.kts
├─ build.gradle.kts                 ← 插件版本：AGP 8.5.2 / Kotlin 1.9.24 / KSP
├─ gradle.properties
├─ gradle/wrapper/gradle-wrapper.properties
├─ app/
│  ├─ build.gradle.kts              ← 依赖都在这里
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     ├─ res/values/{strings,themes}.xml
│     ├─ res/drawable/ic_launcher.xml
│     └─ java/com/mangashelf/reader/
│        ├─ MangaShelfApp.kt        ← Application，持有 Repository
│        ├─ MainActivity.kt         ← 书架 ↔ 阅读器 的极简导航（不引 Navigation 库）
│        ├─ data/
│        │  ├─ Book.kt              ← Room 实体
│        │  ├─ BookDao.kt
│        │  ├─ AppDatabase.kt
│        │  ├─ ComicStorage.kt      ← 页面落盘规则（files/comics/<folder>/page_001.jpg）
│        │  └─ BookRepository.kt
│        ├─ importer/
│        │  ├─ PdfImporter.kt       ← PdfRenderer 逐页渲染成 JPEG
│        │  ├─ ImageImporter.kt     ← 批量图片规范化
│        │  └─ FileNames.kt
│        └─ ui/
│           ├─ ShelfScreen.kt       ← 书架 + 导入 + 设置弹窗
│           ├─ ShelfViewModel.kt
│           ├─ ReaderScreen.kt      ← 翻页 / 缩放 / 沉浸模式
│           ├─ ReaderViewModel.kt
│           └─ theme/{Color,Theme}.kt
├─ README.md                        ← 本文件
└─ DESIGN.md                        ← 设计说明（产品流程、数据结构、技术决策）
```

---

## 3. 运行环境

| 项目 | 要求 |
| --- | --- |
| Android Studio | Koala (2024.1.1) 或更新 |
| JDK | **17**（AGP 8.5 强制要求；Android Studio 自带的 JBR 17 即可） |
| Gradle | 8.7（`gradle-wrapper.properties` 已指定） |
| compileSdk / targetSdk | 34 |
| minSdk | 24（Android 7.0） |
| 设备 | 真机或 API 24+ 模拟器 |

## 4. 怎么编译

工程**自带 `gradlew`**（Gradle 8.7 的 wrapper，含 `gradle-wrapper.jar`），
依赖仓库已配好阿里云镜像（`settings.gradle.kts`），所以直接就能构建。

**方式 A：命令行（最快）**

```powershell
cd "C:\Users\86139\Desktop\deepseek\漫画书翻译"
.\gradlew.bat assembleRelease     # 发布包，6.89 MB（R8 压缩已关闭）
```

签名密钥在工程里（`keystore/mangashelf.jks`），debug 和 release 都用它，
所以任何机器编出来的包都能覆盖升级。详见 [版本记录.md](版本记录.md) 的「签名」一节。

**方式 B：Android Studio**

1. `File > Open`，选中 `漫画书翻译` 这个目录
2. 等 Sync 完成（首次会下载依赖，约几百 MB）
3. `Run ▶` 直接装到手机，或 `Build > Build Bundle(s)/APK(s) > Build APK(s)`

APK 输出路径：

```
app\build\outputs\apk\release\app-release.apk     # assembleRelease
app\build\outputs\apk\debug\app-debug.apk         # assembleDebug
```

有 `adb` 的话可以直接装：

```powershell
adb install -r app\build\outputs\apk\release\app-release.apk
```

> 第一次构建会联网下载依赖（约几百 MB），请保证网络通畅。
> 如果官方仓库慢，`settings.gradle.kts` 里已经把阿里云镜像放在最前面了。
> 如果下载慢，可以在 `settings.gradle.kts` 的 `repositories { }` 里把 `google()`、`mavenCentral()`
> 换成国内镜像（阿里云 `maven.aliyun.com/repository/google` 与 `.../public`）。

---

## 5. 使用流程

1. 打开 App → 空书架 → 点 **导入漫画**（或空状态里的按钮）
2. 选 **PDF 文件** 或 **选择图片（可多选）**
3. 看到"正在导入 12 / 86 页"的进度，导入完自动出现在书架上，封面是第一页
4. 点封面进入阅读：
   - 左右滑动翻页
   - 点屏幕中间 → 顶部出现标题/返回/页码，底部出现跳页滑杆与方向切换
   - 点屏幕左侧或右侧 → 上一页/下一页
   - 双指捏合或双击 → 放大看细节，再双击还原
5. 长按书架卡片 → 改名 / 改阅读方向 / 删除

日漫默认是**从右往左**翻（第 2 页在第 1 页的左边）。如果导的是国漫/美漫，在阅读器底部点
"改为从左往右"，或者在书架长按里切换，设置会记住。

---

## 6. 数据存在哪

- 页面图片：`/data/data/com.mangashelf.reader/files/comics/<12位目录名>/page_001.jpg`
- 书籍信息：Room 数据库 `mangashelf.db`（表 `books`）
- 完全在应用私有目录里，卸载即清空。**不申请存储权限**，也不用 MediaStore。

想导出/备份的话，可以把 `files/comics` 目录拷出来（需要 root 或 `adb backup`），
或者在 `ShelfScreen` 的删除逻辑旁边加一个"导出"按钮（见 DESIGN.md 的扩展点）。

---

## 7. 已知限制

- **PDF 每页会被渲染成 1440px 宽的 JPEG**（质量 88）。文字密集的 A4 扫描件放大到极限会略糊；
  想要更清晰把 `PdfImporter.TARGET_WIDTH` 调到 2048（代价是存储和内存翻倍）。
- 有密码保护的 PDF 不支持（会提示失败）。
- PDF 里的**文字不会被抽出来**，没有搜索/选词（这是下一步要做的 OCR 方向）。
- 不支持 CBZ/CBR/ZIP 压缩包；如果图片很多，请先解压再选图片，或者按 DESIGN.md 加一个 ZipImporter。
- 只做了单页排版，没有"双页跨页"（平板横屏适用）。
- 翻页是滑动动画，没有做仿真翻页（page curl）效果。

---

## 8. 下一步可以加什么

按投入产出排序：

1. **翻译**（这就是这个文件夹叫"漫画书翻译"的原因）：长按/框选漫画格子 → 区域截图 → OCR → 翻译 → 浮层盖回原位。详见 `DESIGN.md` 第 7 节，`ui/ReaderScreen.kt` 里已经留好了接入手势的位置。
2. CBZ/ZIP 批量导入（先解压到临时目录，再走 `ImageImporter`）。
3. 平板双页、横屏跨页。
4. 阅读器亮度/暖色滤镜（夜间看白底漫画）。
5. 书架排序（按最近阅读 / 手动拖拽）、文件夹分组。
6. 长条漫画（Webtoon）模式：竖向连续滚动而不是一页一页。

---

## 9. 网页版（`web/`）

同一个产品的电脑端实现，用来先验证交互：

| | 安卓版 `app/` | 网页版 `web/` |
| --- | --- | --- |
| 运行方式 | Android Studio 编译 APK | 双击 `web\start.bat`，浏览器打开 |
| PDF 渲染 | `PdfRenderer`（设备端） | PyMuPDF（本机 Python） |
| 界面 | Kotlin + Jetpack Compose | 原生 HTML / CSS / JS |
| 数据 | Room + `files/comics` | `web/data/library.json` + 页面 JPEG |
| 翻页 | HorizontalPager | 双图层 transform 动画 + 滚轮/键盘/点击分区 |
| 文件夹导入 | **支持**（`OpenDocumentTree` 递归 + 自然排序） | **支持**（自然排序、子文件夹按章节） |

两者的产品结构完全一致：书架 →（导入）→ 阅读器，同一套配色、同一套"从右往左"逻辑。
详见 [web/README.md](web/README.md)。

---

## 10. 在手机上用

### 路 A：装 APK（已编译好，推荐）

**`漫画书架-v1.5.6.apk`（约 6.9 MB）就在这个目录里**，直接装：

1. 手机「设置 → 安全」里允许安装未知来源应用（不同品牌位置略有差异，按提示授权即可）
2. 把 APK 传到手机（数据线 / 微信文件传输 / QQ / 网盘都行）
3. 点开安装 → 打开「漫画书架」

**完全离线可用**：不需要电脑、不需要联网、除了导入时的前台服务，不申请任何权限；读 PDF / 图片走系统文件选择器。

已核对信息：

| 项 | 值 |
| --- | --- |
| 包名 | `com.mangashelf.reader` |
| 版本 | 1.5.2（versionCode 11） |
| 系统要求 | Android 7.0+（minSdk 24），targetSdk 34 |
| 签名 | Android Debug 证书（个人自用没问题） |
| 大小 | 6.89 MB（release，未开 R8；见下方说明） |
| 图标 | 企鹅娘（由 `企鹅娘-无水印.png` 生成，v1.2 起固定使用） |

> 因为用的是 debug 签名，以后要**覆盖安装**必须用同一个 debug key 签名的包；
> 如果哪天换了签名（比如出正式版），需要先卸载旧的再装。
>
> **版本约定**：每次更新生成**新文件**，不覆盖、不删除旧 APK（文件名带版本号）。
> 历史版本、每版改动、SHA-256 都在 [版本记录.md](版本记录.md) 里。

### 自己重新构建

工程里已经带了 `gradlew`（Gradle 8.7），依赖仓库换成了阿里云镜像（快很多）：

```powershell
cd "C:\Users\86139\Desktop\deepseek\漫画书翻译"
.\gradlew.bat assembleRelease
# 产物：app\build\outputs\apk\release\app-release.apk
```

需要 JDK 17。用 Android Studio 打开这个目录也可以（`File > Open` → Sync → Run）。

### 路 B：不装 App，用手机浏览器（局域网）

见 [web/README.md](web/README.md) 的「在手机上用」。三步：

1. 双击 `web\允许手机访问.bat` 放行端口（只需一次）
2. 双击 `打开漫画书架.lnk`，记下窗口里打印的 `手机上： http://192.168.x.x:8971/`
3. 手机连同一个 Wi-Fi，浏览器打开这个地址 → 菜单「添加到主屏幕」

手机上的手势已经做了：左右滑动翻页、点两侧翻页、**双指捏合缩放**、放大后单指平移、双击缩放。

限制：**电脑必须开着**，手机和电脑在同一个 Wi-Fi，PDF 是电脑在渲染。

### 两条路对比

| | APK 版 | 网页版（局域网） |
| --- | --- | --- |
| 能否离线用 | **可以，完全本地** | 不行，电脑要开着 |
| PDF 渲染 | 手机上的 `PdfRenderer` | 电脑上的 PyMuPDF |
| 导入方式 | 系统文件选择器（**文件夹** / PDF / 多选图片） | 文件夹上传 / 电脑路径 / 文件 |
| 文件夹成书 | **支持**（SAF 文件夹树，递归子目录） | 支持（自然排序 + 子文件夹） |
| 进度保存 | Room 数据库 | 服务端 `library.json` |
| 手机手势 | 滑动翻页 + 双指缩放 | 滑动翻页 + 双指缩放 |
