# CarWith 白名单扩展模块（LSPatch + Xposed）完整方案

> 目标：**在不 root 的前提下**（你已有 Shizuku），通过给手机端 CarWith 注入一个 Xposed 模块，
> 把自定义应用（如 Rhythm、抖音、汽水音乐等）扩展进 CarWith 的"应用列表/可拉起的应用"入口，
> 让车机能以 CarWith 应用的形式打开它们。
>
> 你的架构：**车机端 = 亿连（亿连通道，作为 ICCOA/CarWith 的接收端）**，
> **手机端 = 小米 CarWith（`com.miui.carwith`）**，白名单/应用列表的决策在**手机端 CarWith**。
> 所以补丁打在手机端 CarWith 上。

---

## 0. 前置认知（先看清，避免白费力）

CarWith 车机端显示的"可打开应用"分两类：

| 类型 | 机制 | 改哪里 |
|---|---|---|
| **官方支持应用**（"已适配"） | CarWith 内部硬编码/配置的**应用白名单（包名 + 类别 + 能力）** | 手机端 CarWith 的数据/读取逻辑 |
| **《我的应用》镜像** | 用户手动添加、任意已安装应用，纯镜像 | 无需改（但镜像，不适合你） |

你要的是**第一类：让自定义应用以"CarWith 应用"身份进入车机入口**。这在 CarWith 里由一组
"读取支持应用列表"的代码决定，**不是用户可配置的配置项**。因此方案 = **Hook 这组读取逻辑，
把你要的包名追加进去**。模块注入用 **LSPatch（免 root LSPosed）+ Shizuku**。

> **诚实提醒**
> - CarWith 是商业化、面向企业主体 + 做认证的体系。此方案是**逆向修改**，灰色地带，自用可以，**不要外传**。
> - CarWith **版本更新（包名/混淆/结构）会导致本模块失效**，需要重新 patch。需接受"每次更新重新做"。
> - LSPatch 给 CarWith 打补丁会**重签名**。若 CarWith 校验签名，需要用 LSPatch 的**本地/便携模式**规避，或承受失败。
> - 给 CarWith 注入、放行任意应用 = 开放车机可运行任意代码的入口，**有安全风险**（尤其第三方应用）。

---

## 1. 整体原理

```
手机端 CarWith (com.miui.carwith)        车机端 (亿连/亿连通道 + 车机屏)
        │  ───── ICCOA/CarWith 协议 ────▶   显示 CarWith 投屏 + 应用棚
        │
        ▼
  读取"支持应用列表"/能力的方法
        │  ← 我们 Hook 这里：把要的包名追加进去
        ▼
  CarWith 认为"这些应用是我支持/能拉起的"
```

- **LSPatch**：基于 LSPosed 核心的免 root Xposed 框架。它把模块代码**重新打包进目标 APK**
  （本地模式/便携模式），或配合 Shizuku 给已安装应用注入，从而无需 root。
- **Shizuku**：用 ADB 无线授权给应用一个"shell 级"权限，让 LSPatch 等工具能以较低权限工作，
  是"伪 root"，**不需要真正解锁 root**。
- **Xposed 模块**：Hook CarWith 解析"支持应用列表"的方法。由于 CarWith 混淆，类/方法名无法写死，
  用 **DexKit**（在运行时动态解析被混淆的类/方法名）来定位。

---

## 2. 需要 Hook 的目标（逆向定位路径）

**前置：拿到 CarWith 手机端 APK（v3.x / v4.x），反编译定位。**

1. **`smali`/`dex` 搜索关键字**（字符串通常在 dex 字符串池里，不会被完全销毁）：
   - 常见已支持包名列表：`com.autonavi.minimap`（高德）、`com.baidu.*`、`com.tencent.qqmusic`、
     `com.netease.cloudmusic`、`com.ss.android.ugc.aweme`（抖音）、`xxx`（汽水音乐）等。
   - 常见方法/字段：`getSupportAppList`、`getRecommendAppList`、`APP_LIST`、`WHITE_LIST`、
     `getAppConfig`、`FilterApp`、`isSupportApp(packageName)` 等。
2. **定位"判断某应用是否支持"的方法**（返回 boolean）：这是核心 Hook 点。
   通常是 `boolean isCarWithApp(String pkg)` / `boolean checkApp(String pkg)` 之类，
   内部遍历一个列表/集合判断。  
   **Hook 它：对要添加的包名直接返回 `true`**。
3. **定位"返回支持应用列表"的方法**（返回 `List<String>` / `List<AppInfo>`）：
   车机端应用棚的来源。  
   **Hook 它：在原结果里 `add()` 你要的包名/App 模型**。
4. **定位"应用启动/拉起"方法**（由包名启动应用）：默认用 `PackageManager.getLaunchIntentForPackage`，
   若它走捷径/白名单校验，Hook 掉校验即可。

> 这些方法名**高度混淆**（a/b/c.d 等）。所以模块不写死字符串，用 DexKit 在运行时解析。
> DexKit 能按"方法签名提示 + 类结构特征"把混淆名映射回来，因此**更新版本后通常仍能解析**，
> 除非 CarWith 大改结构。

---

## 3. Xposed 模块源码框架（Kotlin）

一个最小的、可用 DexKit 动态解析 + 追加白名单的模块骨架。

### 3.1 模块声明（AndroidManifest.xml 元数据）

```xml
<application>
    <meta-data android:name="xposedmodule" android:value="true"/>
    <meta-data android:name="xposeddescription" android:value="CarWith白名单扩展"/>
    <meta-data android:name="xposedminversion" android:value="93"/>
    <meta-data android:name="xposedscope" android:resource="@array/lsposed_scopes"/>
</application>
```
`lsposed_scopes` 填 `com.miui.carwith`（旧版若是 `com.miui.carlink` 也一起列入）。

### 3.2 入口 Hook（用 DexKit 动态解析）

```kotlin
package com.your.carwithwhitelist

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.lsposed.lsposed.dexkit.*  // 实际用 lsposed 的 DexKit 封装，或 Xposed 生态的 dexkit

class MainHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.miui.carwith" &&
            lpparam.packageName != "com.miui.carlink") return

        // 你要放行的自定义应用包名（打补丁前改这里）
        val extraApps = listOf(
            "chromahub.rhythm.app",   // 你的 Rhythm（demo）
            // "com.ss.android.ugc.aweme",  // 抖音
            // "xxx",                      // 汽水音乐
        )

        try {
            // 1) 用 DexKit 解析"判断是否支持应用"的方法（返回 boolean，入参含 String 包名）
            val isSupportMethod = DexKit.resolve(
                lpparam.classLoader,
                // 特征：方法名含 "App"，返回 boolean，参数含 java.lang.String
            )
            if (isSupportMethod != null) {
                XposedHelpers.findAndHookMethod(
                    isSupportMethod.ownerClassName, lpparam.classLoader,
                    isSupportMethod.methodName, *isSupportMethod.paramTypes,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val pkg = param.args.firstOrNull { it is String } as? String
                            if (pkg != null && extraApps.contains(pkg)) {
                                param.result = true   // 强制放行
                            }
                        }
                    }
                )
            }

            // 2) 用 DexKit 解析"返回支持应用列表"的方法
            val listMethod = DexKit.resolve(...) // 返回 Collection<...>
            if (listMethod != null) {
                XposedHelpers.findAndHookMethod(
                    listMethod.ownerClassName, lpparam.classLoader,
                    listMethod.methodName, *listMethod.paramTypes,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val result = param.result
                            if (result is MutableCollection<*>) {
                                // 追加包名对应的 App 条目模型；若只认 String 包名则直接 add
                                extraApps.forEach { (result as MutableCollection).add(it) }
                            }
                        }
                    }
                )
            }
        } catch (t: Throwable) {
            // 记录日志，避免崩溃
        }
    }
}
```

**说明**
- `DexKit.resolve(...)` 需要你实际传入"解析条件"，这不是一行伪代码；下面 §3.3 给 DexKit 的正确用法。
- "追加 App 条目"要**和列表的泛型一致**。如果列表里是 `AppInfo` 对象而非 String，
  你需要构造同款 `AppInfo`（字段：包名/名称/图标/分类/能力位）并塞进去。
- 如果 CarWith 直接枚举"已安装应用中哪些属于支持列表"，则 Hook 点不同：过滤掉"不是我的"。

### 3.3 DexKit 动态解析（关键）

CarWith 混淆，不能写死方法名。**DexKit** 能按"类/方法签名提示"从内存里把混淆名解析出来。
在 LSPosed 里常配合 `DexKitResolver`。示例（以 lsposed 的 dexkit 为目标）：

```kotlin
val resolver = DexKitResolver()
// 1) 解析"支持应用列表"相关类
val appList = resolver.findClass(
    "com.miui.carwith", // 目标类
    FindClassConfig().apply {
        this.className = ... // 或按特征
    }
)
```

实际上更常用的做法是**直接反编译看混淆后结构**，然后在模块里**用 `XposedHelpers.findClassIfExists` +
特征遍历 + 反射**定位，或干脆用 DexKit 的"方法名反查"。**推荐先用 jadx 反编译 CarWith APK**，
把（a）真实方法签名、（b）含包名字符串的方法，都记下来，然后在模块里用这些**稳定锚点**去 Hook，
比纯 DexKit 更可控。

> 结论：**先用 jadx 拿真实结构 → 模块里用"可读字符串锚点 + 反射/DexKit"定位 Hook**，这样最稳。

---

## 4. 用 Shizuku + LSPatch 注入（不 root）

### 4.1 工具准备
- **Shizuku**：手机上已装，用 ADB 无线授权（开发者选项 → 无线调试 → Shizuku 配对授权）。
  授权后 Shizuku 服务运行，LSPatch 等可借壳。
- **LSPatch Manager**（免 root 的 LSPosed 容器）：管理 LSPosed 模块 + 给目标 APK 打补丁。
- **CarWith APK**：手机端 `com.miui.carwith`（可用 adb 导出当前已装版本：`adb shell pm path com.miui.carwith`，再 `adb pull`）。
- **你的 Xposed 模块编译产物**：即 §3 模块，编译成一个 APK 装到手机。

### 4.2 两种注入方式

**方式 A（推荐，改动最小，失败率低）：LSPatch 便携模式，目标是"你的应用"**
> 注意方向：如果要给 CarWith 追加白名单，便携模式的目标是 **CarWith**。
1. LSPatch Manager →「+」→ 选择 **CarWith APK**（或从已安装列表选）。
2. 选择**本地模式**（模块不内置，配合 Shizuku）或**便携模式**（模块内嵌进 APK，单文件）。
3. 勾选你的 Xposed 模块作为作用域。
4. 开始修补 → 生成修补后的 CarWith APK。
5. （可选）卸载原 CarWith，安装修补后的 APK。若签名冲突，用本地模式 + Shizuku 注入已装应用。

**方式 B：用 Shizuku 给"已安装的 CarWith"注入（不改安装包）**
1. 保证 Shizuku 已激活。
2. LSPatch 选"已安装应用" → CarWith → 用 Shizuku 权限注入模块。
3. 这种方式不改 APK 安装本体，签名校验问题更少，但依赖 LSPatch 对 Shizuku 的支持版本。

### 4.3 验证
- 打开 CarWith → 连接车机（亿连）→ 看应用棚是否出现你追加的包名。
- 若没出现：检查模块日志（LSPosed 日志）看 Hook 是否命中、方法是否解析成功。
- 调整 Hook 点（通常要迭代几次真实结构）。

---

## 5. 更稳的替代路线（也免 root / 免逆向，先试这个）

**如果你的目标是"让 Rhythm（或某应用）以 CarWith 媒体/应用形式出现"，先优先走协议层，改动更小：**

- Rhythm 已实现 Media3 `MediaLibraryService` + MediaSession + `onGetChildren` 浏览。
  只要**车机端支持"手机媒体应用聚合入口"**，它就能以"可控制媒体应用"出现，**无需改 CarWith**。
- 若要更像"官方”，可用 LSPatch 给**你的应用**注入一个模块：监听 CarWith `CONNECTION_SUCCESS` 广播，
  **连接后自动拉起/设为播放音源**。参考 [CarWith Play](https://github.com/zheng926482/Xposed-CarWith-Trigger)、
  [CarWith Enhance](https://github.com/MeshHun/CarWithEnhance)。

> 判断标准：**你车机（亿连）的媒体入口，能不能把"手机上支持 MediaSession 的应用"识别成卡片？**
> 能 → 根本不用改 CarWith，走协议就行。不能 → 才需要上面 §4 的 CarWith 白名单扩展。

---

## 6. 风险评估

| 风险 | 等级 | 缓解 |
|---|---|---|
| CarWith 版本更新失效 | 高 | 锁版本不自动更新；每次更新重新 patch |
| 签名校验冲突 | 中 | LSPatch 本地模式 + Shizuku 注入；或直接替换安装 |
| 车机端收不到新增应用 | 中 | 需反复逆向定位真实 Hook 点；用日志验证 |
| 开放任意应用带来的安全隐患 | 中 | 自用，只加可信应用，不外传 |
| 逆向、修改商业软件合规 | 中 | 个人学习自用；不要分发 |

---

## 7. 落地步骤清单

1. [ ] 用 `adb pull` 导出当前 CarWith APK；用 **jadx** 反编译，定位"支持应用列表"读取方法与含包名锚点。
2. [ ] 改模块源码 `extraApps`（加入你的包名，如 `chromahub.rhythm.app`）。
3. [ ] 编译 Xposed 模块 APK。
4. [ ] Shizuku 授权（ADB 无线调试）。
5. [ ] LSPatch：挑一种方式（便携/Shizuku 注入）给 CarWith 打补丁并注入模块。
6. [ ] 连车机（亿连）验证应用棚出现，必要时回 yáo 调整 Hook 点。
7. [ ] 锁定 CarWith 版本；记录每次 patch 的版本号。

---

## 8. 参考项目

- [CarWithEnhance](https://github.com/MeshHun/CarWithEnhance) —— 已用 DexKit 动态解析 CarWith 混淆方法 + Hook 音频路由/续播等，**是最好的逆向参考**。
- [CarWith Play（Xposed-CarWith-Trigger）](https://github.com/zheng926482/Xposed-CarWith-Trigger) —— Hook CarWith 广播 `CONNECTION_SUCCESS`/`DISCONNECTED`，连接后自动拉起播放器。
- [LSPatch](https://github.com/LSPosed/LSPatch) —— 免 root Xposed 容器（Shizuku 配合）。
- [CarWithAppLauncher（车联助手X 小米版）](https://github.com/XanderYe/CarWithAppLauncher) —— 走 `samsung/vivo/ucar carlink kit` intent 入口 + AccessibilityService/NotificationListener + MediaPlaybackService 注入媒体。

---

## 9. 结论

- **可行**：不 root（Shizuku + LSPatch）给手机端 CarWith 注入 Xposed 模块，Hook 其"支持应用列表"
  读取逻辑，把自定义包名追加进去，是有操作依据、且社区有先例（CarWithEnhance/DexKit）的方案。
- **但它是逆向、易随版本失效、需反复迭代**，且属灰色；请自用。
- **先试更简路线**：确认车机（亿连）是否支持"手机媒体应用聚合"——supported 的话，Rhythm 走 MediaSession
  协议即可，根本不用改 CarWith。
