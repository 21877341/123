# HyperOS Desktop Smooth

面向「HyperOS 平板新 Rust 桌面越用越掉帧」的 LSPosed 模块。**实验性，未在真机验证。**

> TL;DR：装模块 → 作用域只勾 Android System → 看日志有没有 `would block` →
> 有才开 enforce。没有 `would block` 就说明你的掉帧不是这条链路，别浪费时间。

---

## 0. 先说清楚边界

- LSPosed 的注入点是 ART 进程，只能 hook Java/Kotlin 方法。
- 它 **hook 不到 Rust 渲染循环**，也拦不住 native 代码直接调用的 `madvise` / `mmap`。
- 普通应用进程调用 `mlockall()` 基本会被 `RLIMIT_MEMLOCK` 挡住（通常只有几十 KB 到几 MB），
  没有 root 提不上去。所以本模块**不做**「把 Rust 内存锁住」这种表演。
- 本模块改的是框架层最可能造成「页面随时间被换出、再访问时 fault」的机制。
  system_server 侧**默认只观察、不修改**；桌面进程侧的 trim 封顶只在你把桌面加进作用域后生效。

如果你的掉帧不是这条链路造成的，这个模块不会有效——第 4 节会教你怎么在十几分钟内判断。

---

## 1. 它改了什么

| 进程 | 钩子 | 行为 |
| --- | --- | --- |
| system_server | `CachedAppOptimizer#compact*` | 拦住对桌面进程的 compaction（匿名页换出/回收） |
| system_server | `CachedAppOptimizer#freezeProcess` | 拦住把桌面进程冻结 |
| system_server | `AMS#setProcessMemoryTrimLevel` | 拦住显式 trim |
| 桌面进程 | `ApplicationThread#scheduleTrimMemory`、`ActivityThread#handleTrimMemory`、`Application#onTrimMemory` | 把 trim level 封顶在 `TRIM_MEMORY_UI_HIDDEN` |

为什么是这几个：

Android 12+ 的 `CachedAppOptimizer` 会对「缓存进程」做 compaction：把匿名页换出/回收。
进程再次被访问时，每一页都要重新换入，这就是「放一会儿就卡、一碰就 fault」的典型形态。
冻结本身不换页，但冻结/解冻会改变调度和 `oom adj`，通常和 compaction 一起出现。
`onTrimMemory` 则是框架通知 App「你可以丢缓存了」，
launcher 收到 `UI_HIDDEN` / `BACKGROUND` / `COMPLETE` 后丢掉图标缓存、字形图集，
下次进来重建，同样表现为卡一下。

**关键点：桌面进程本不该被当成 cached app。** 如果 HyperOS 4 的新桌面是独立进程
（或独立进程组），它的 `oom adj` 很可能没有按 launcher 处理，于是被这套机制反复压缩。

---

## 2. 适用性自检（先做这个）

把 `<pkg>` 换成你的桌面包名：

```powershell
# 1) 当前默认桌面是谁
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME

# 2) 它有没有在跑
adb shell pidof <pkg>

# 3) 这个进程里有没有 ART —— 没有的话 LSPosed 根本进不去
adb shell su -c "grep -c libart.so /proc/<pid>/maps"

# 4) 看它的 oom adj / frozen 状态
adb shell dumpsys activity processes | Select-String -Pattern "<pkg>" -Context 2,14
```

- 第 3 步输出 `0`：这是纯 native 进程，LSPosed 无法注入，本方案对你无效。
  只能走 Zygisk/native hook 或 root 层内核参数，不在本模块范围内。
- 第 4 步看到 `oom adj` 是 900+（cached）或 `frozen=true`：命中本模块的假设，继续。

也可以直接跑：

```powershell
.\tools\diagnose.ps1 -Package <pkg>
```

它会采样 `/proc/<pid>/stat` 的 minor/major fault 增量、`VmRSS/VmSwap`、meminfo、
gfxinfo 和 freezer 配置。**先留一份基线，开启 enforce 后再跑一份对比。**

不编译也能先做两个 A/B：

- 开发者选项 → **暂停执行已缓存的应用**（Suspend execution for cached apps）→ 关闭 → 重启。
  如果掉帧明显改善，说明 freezer 这条线有关。
- 设置里关掉 **内存扩展 / 内存融合** → 重启，同样路径对比。这条测的是 zram 换页。

---

## 3. 拿 APK 的三种方式

### A. GitHub 网页上传（最省事，不用 git、不用本地 SDK）

1. 打开 github.com → **New repository** → 名字随意 → 选 **Public** → Create repository。
2. 在空仓库页面点 **uploading an existing file**。
3. 把 `hyperos-desktop-smooth` 文件夹里的**所有内容**拖进去（包括 `.github` 文件夹），
   点 **Commit changes**。
4. 打开 **Actions** → 左边 **Build APK** → 等 3–5 分钟。
5. 回到仓库首页，右侧 **Releases** → **Latest build** → 下载 `app-debug.apk`。
   公开仓库的 Release 资源不需要登录就能下载。

如果 Actions 里没有 Build APK，说明 `.github/workflows/` 没上传成功：
仓库页面 **Add file → Create new file**，文件名填 `.github/workflows/build-apk.yml`，
把仓库里同名文件的内容粘进去，Commit 即可。

也可以用 git 推：

```bash
git init
git add .
git commit -m "HyperOS desktop smooth"
git branch -M main
git remote add origin https://github.com/<你>/<仓库>.git
git push -u origin main
```

debug APK 用 debug key 签名，可以直接 `adb install`。工作流包含 JDK 17、Gradle 8.9、
SDK 35，构建成功后会自动把 APK 发到 **Releases**。

### B. Android Studio

1. 用 Android Studio 打开本目录。
2. Gradle sync（会从 `https://api.xposed.info/` 拉 Xposed API 82，仅 compileOnly）。
3. Build → Build APK(s)。
4. 产物：`app/build/outputs/apk/debug/app-debug.apk`。

### C. 本机命令行

前提：JDK 17、Android SDK（platform 35 + build-tools 35.0.0）、Gradle 8.9。

```powershell
.\tools\build-apk.ps1
```

脚本会找 `ANDROID_HOME`（没有就用 `%LOCALAPPDATA%\Android\Sdk`）、检查 platform 35，
然后跑 `gradle :app:assembleDebug`，最后把 APK 复制到
`dist\hyperos-desktop-smooth-debug.apk`。

> 仓库没有提交 `gradle-wrapper.jar`（二进制不便入库）。本机没有 `gradle` 的话，
> 先在 Android Studio 里打开一次让它生成 wrapper，或装一个 Gradle 8.9。

---

## 4. 安装 + dry-run（默认，只观察）

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

1. LSPosed → 模块 → 启用 **HyperOS Desktop Smooth**。
   （模块没有启动图标，装完在 LSPosed 管理器里找。）
2. 作用域**先只勾 Android System**（包名 `android`，即 system_server）。
   先不要勾桌面——这样应用侧 trim 封顶完全不介入，基线干净。
3. 重启。system_server 的钩子只在启动时安装。
4. 看日志：

```powershell
adb logcat -s HyperOSDesktopFix
```

或 LSPosed 管理器 → 日志，过滤 `HyperOSDesktopFix`。

启动后会先看到钩子安装日志；目标解析是在系统起来后异步做的，所以 `targets=` 会晚几秒：

```
system_server module loading: Android ... / SDK ... / ...
hooked CachedAppOptimizer#compactApp(ProcessRecord,String,boolean,int)
CachedAppOptimizer hooks installed: N
...
targets=[...] uids=[...] home=... notInstalled=[...] targetsFile(exists=false,readable=false) enforceFile(exists=false)
initialized; mode=DRY-RUN (no /data/system/hyperos_desktop_fix.sys)
```

然后正常使用平板 20–30 分钟，切应用、回桌面、开合后台。如果出现：

```
would block compactApp(com.miui.home)
would block freezeProcess(com.miui.home)
would block setProcessMemoryTrimLevel(com.miui.home)
```

说明这套机制确实在动你的桌面。**如果一直没有 `would block`，就停在这里**：
说明掉帧不是这条链路造成的，开 enforce 也不会有用。

确认命中后，再把桌面应用加进 LSPosed 作用域（启用第 1 节表格里的 trim 封顶），
然后进入第 5 节。

---

## 5. 开启 enforce

确认 dry-run 命中、并且桌面已加入作用域后：

```powershell
.\tools\apply-system.ps1 -Package <pkg>
```

脚本会写入目标包名、放下开关文件并重启（LSPosed 本身就要 root，这里直接复用 `su`）。等价的手工命令：

```powershell
adb shell "su -c 'echo <pkg> > /data/system/hyperos_desktop_targets.txt'"
adb shell "su -c 'chmod 644 /data/system/hyperos_desktop_targets.txt'"
adb shell "su -c 'touch /data/system/hyperos_desktop_fix.sys'"
adb reboot
```

重启后日志里的 `would block` 会变成 `BLOCKED`，并且出现 `initialized; mode=ENFORCE`。

---

## 6. 验证

不要凭两分钟的主观感受。至少做一次前后对比：

```powershell
.\tools\diagnose.ps1 -Package <pkg>
```

重点看：

- `dMajor` / `dMinor` 每 3 秒的增量是否下降；
- `VmSwap` 是否还持续上涨；
- `dumpsys gfxinfo <pkg>` 里 `Janky frames` 百分比；
- 同样的使用路径（开合后台、切换应用、放 10 分钟再回桌面）前后各 30 分钟。

如果 `VmSwap` 不再涨、`dMajor` 明显下降，但主观还是卡，那瓶颈就不在换页，
继续查 GPU/调度（见第 8 节）。

---

## 7. 回滚

```powershell
.\tools\apply-system.ps1 -Disable
```

或手工：

```powershell
adb shell "su -c 'rm -f /data/system/hyperos_desktop_fix.sys'"
adb reboot
```

彻底回滚：LSPosed 里关闭模块 → 卸载 APK。删除 `/data/system/hyperos_desktop_targets.txt`
不是必须的，但可以一起删。

---

## 8. 如果没效果

按顺序排查：

1. **完全没有 `would block`**
   机制没命中。可能是：
   - 包名不对（看启动日志里的 `targets=` 和 `notInstalled=`）；
   - 桌面不是 HOME 应用，也没写进目标文件；
   - 压缩/冻结发生在别的进程（例如 lmkd 直接 `process_madvise`）；
   - 掉帧根本不是内存回收造成的。

2. **有 `would block`，enforce 后没改善**
   压缩不是主因。用 Perfetto 看 `mm_page_fault`、`sched`、`gfx`，别继续加内存钩子。

3. **进程里没有 `libart.so`**
   LSPosed 进不去。这条路走不通，考虑 Zygisk + PLT hook，或 root 侧参数。

4. **怀疑 Rust 自己 `madvise(MADV_DONTNEED/MADV_FREE)`**
   Java hook 拦不到，需要 native PLT/GOT hook。先证明：抓一段 `perfetto` 或
   `strace -f -e trace=madvise`（需要 root），看是不是应用自己在还页。
   如果是，本模块的框架层钩子帮不上。

5. **Rust 侧内存泄漏**
   `dumpsys meminfo` 的 `TOTAL` 持续上涨。这种情况 `mlock` 只会更糟，
   应该抓 `heapprofd` 或至少记录增长曲线反馈给小米。

6. **zram / 内存扩展在疯狂换页**
   设置里关掉「内存扩展/内存融合」做 A/B；看 `/proc/vmstat` 的 `pswpin/pswpout`。

7. **GPU / 调度**
   `dumpsys SurfaceFlinger`、`perfetto` 的 `gfx` 轨、GPU governor。
   这些和本模块无关。

---

## 9. 风险

- 钩 system_server 有 bootloop 风险。**务必先 dry-run**，确认类/方法都能找到，再开 enforce。
- 拦住压缩会把内存留在进程里。8 GB 机器上后台多时，别的应用可能更容易被 lmkd 杀。
  这是取舍，不是白赚。
- HyperOS 可能改类名/方法名。日志里出现 `CachedAppOptimizer not found` 或
  `hooks installed: 0` 就说明对不上，别硬开。
- 模块所有钩子体都有 try/catch，单个回调抛异常不会带走 system_server；但
  hook 安装阶段本身仍可能因为 ROM 差异出问题。保留 adb / recovery 手段。

---

## 10. 目录

```
hyperos-desktop-smooth/
├─ app/
│  ├─ build.gradle.kts
│  ├─ proguard-rules.pro
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     ├─ assets/xposed_init
│     ├─ res/values/strings.xml
│     └─ java/dev/hyperosfix/desktop/
│        ├─ HookEntry.java     # 入口，分派 system_server / 桌面进程
│        ├─ SystemHooks.java   # CachedAppOptimizer + AMS trim
│        ├─ AppHooks.java      # onTrimMemory 封顶
│        ├─ Targets.java       # 包名/UID 解析与匹配
│        └─ L.java             # 双通道日志 + 限流
├─ tools/
│  ├─ diagnose.ps1             # 采样 page fault / swap / gfxinfo
│  ├─ apply-system.ps1         # 开/关 enforce
│  └─ build-apk.ps1            # 本机命令行打包
├─ .github/workflows/
│  └─ build-apk.yml            # GitHub Actions 自动出 APK
├─ build.gradle.kts
├─ settings.gradle.kts
└─ gradle.properties
```

---

## 11. 设计备注

- 没有引入 native 库：普通进程 `mlockall` 拿不到足够 `RLIMIT_MEMLOCK`，
  在 Java 模块里塞一个必然失败的 native 调用没有意义。
- 没有 hook `freezeProcessNoLock`：外层 `freezeProcess` 负责状态记账，
  只拦内层会让 AMS 以为进程已冻结，造成状态不一致。
- `Targets` 同时按包名和 UID 匹配，`pkg:render` 这类子进程也能覆盖。
- dry-run 是刻意设计的：先证明机制命中，再改行为。省得装完觉得「好像快了点」。
