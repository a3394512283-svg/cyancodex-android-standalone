# CyanCodex 主 App R8 混淆规则
# 目标: 业务代码全部混淆, 仅保留反射/Gson/Retrofit/插件接口所需的类与成员

# ============================================================
# 名称混洗 (混淆字典)
# ============================================================
# 类名 / 包名 / 成员名都从字典取名 (一行一个合法标识符)。
# 字典用"形近字符"(IlIlIl / O0O0O / I1l1I) 、"生僻汉字"(龊褫逭) 和中文词,
# 比默认的 a/b/c 难读得多 —— 不少反编译工具对非 ASCII 标识符支持很差, 直接乱码。
#
# ★ 2026-10-09 类名与成员名**拆成两份字典**:
#     obfuscation-dict-class.txt  4199 词  -> 只给类名/包名用
#     obfuscation-dict.txt          49 词  -> 只给成员名(方法/字段)用
#
#   为什么拆 + 为什么要 4000+ 词 (实测数据):
#     ① 共用一份 49 词字典时, 653 个待重命名的自家类里只有 166 个 (25.4%) 拿到
#        字典词, 其余回退 R8 默认短名 (b0 / w / k91 …)。
#     ② 拆开后 1549 词 -> 379 个 (58.0%); 3199 词 -> 532 个 (81.5%);
#        4199 词 -> 覆盖全部 653 个。
#     ③ 词数要这么多, 是因为 R8 **并不优先照顾应用自己的类** —— 字典词被依赖库
#        大量消耗 (1549 词时 1277 个吃词类里, 898 个是 androidx/kotlin/okhttp 的)。
#     ④ 体积代价可忽略: 49 -> 3199 词只让 APK 动了 ±20 字节 (类名进字符串池, 增量极小)。
#
#   ⛔⛔ **改字典内容后必须顺手改一下本文件** —— Gradle 不把
#   `-classobfuscationdictionary` 指向的字典文件登记为 R8 任务的输入,
#   只改字典内容会导致 `minifyReleaseWithR8` 被判为 UP-TO-DATE 而**不重跑**,
#   产物静默沿用旧字典。实测: 词数 1549->3199 后构建日志仍是 "45 up-to-date",
#   APK 时间戳纹丝不动。加/改一行注释即可强制重跑。
#
#   为什么拆? 原先三个选项共用同一份 49 词字典, 实测 653 个待重命名的自家类里
#   **只有 166 个 (25.4%) 真的拿到字典词**, 其余 487 个 (74.6%) 回退成 R8 默认短名
#   (b0 / w / k91 / du …) —— 短名规律性强, 逆向者一眼就能按"第几个类"归类,
#   等于混淆白做。拆开后类名独享 1549 词, 覆盖率大幅提升。
#
#   成员名反过来要**短**: 成员名数量上万, 用长词只会把 dex 撑大, 而 a/b/c 形式的
#   成员名本来就看不出语义, 没有继续加长的必要。
#
# ⛔ 两份字典的前 49 词**顺序与拼写都不可改动** —— tools/verifyobf.py 的 31 个
#   指纹词全部取自这一段, 动了门禁立刻红。要扩充只能在文件末尾追加。
-classobfuscationdictionary obfuscation-dict-class.txt
-packageobfuscationdictionary obfuscation-dict-class.txt
-obfuscationdictionary obfuscation-dict.txt

# 混淆后的类全部塞进同一个包 → 反编译后满屏 "<字典词>.<字典词>", 包结构信息彻底丢失。
-repackageclasses ''

# 放宽访问修饰符, R8 才能做更多内联 / 裁剪 / 类合并。不配这条, 混淆率和优化率都上不去。
-allowaccessmodification

# 字符串常量里的类名一并混淆 —— 代码里写的 "com.canglan.cyancodex.ui.Foo" 这种
# 字面量会被改写成混淆后的名字, 字符串池不再泄露原始类名。
# ⚠️ 前提: 工程内不能有 Class.forName("自家未 keep 的类名")。
#    已核查: 全仓只有 plugin/PluginManager.kt 一处 forName, 入参来自外部插件 JAR 的
#    META-INF/services, 不是本 App 的类名; 且本 App 被反射的类都已 keep。
-adaptclassstrings

# 保留行号(崩溃栈还能用 retrace 还原), 但抹掉源文件名。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ============================================================
# 必须保留的 (反射 / 序列化 / 插件契约)
# ============================================================

# --- Retrofit 接口 (动态代理 + 注解反射) ---
-keep interface com.canglan.cyancodex.data.ApiService { *; }

# --- Gson / Retrofit 模型 ---
# ⛔ 必须是完整的 -keep。实测把这条换成
#       -keepclassmembers class com.canglan.cyancodex.data.** { <fields>; <init>(); }
#   会出大事: R8 会把**泛型 Signature 属性丢掉** —— 新 dex 里再也找不到
#   `L<ApiResp混淆名><...` 这种签名串。后果是 Retrofit 拿不到 `ApiResp<Announcement>`
#   的类型参数, Gson 把 data 字段当 Object 反序列化成 LinkedTreeMap, 运行到
#   `r.data ?: emptyList()` 就抛 ClassCastException(LinkedTreeMap cannot be cast to Announcement)。
#   `-keepattributes Signature` 和 `-keep interface ApiService { *; }` 都拦不住。
#   官方包同样保留整个 data 包 —— 这是他们踩过的坑, 别自作聪明。
-keep class com.canglan.cyancodex.data.** { *; }

# --- 插件接口: 外部 JAR 实现本 App 的接口, 方法签名必须保持 ---
-keep interface com.canglan.cyancodex.plugin.CyanPlugin { *; }
# 背景扩展接口与背景数据类: 外部插件(含"自定义背景"/插件工坊生成的插件)会实现/构造它们,
# 不能混淆, 否则插件类加载时找不到接口 -> ClassNotFoundException
-keep interface com.canglan.cyancodex.plugin.BackgroundProvider { *; }
-keep interface com.canglan.cyancodex.plugin.SpecPlugin { *; }
-keep class com.canglan.cyancodex.plugin.PluginBackground { *; }

# --- 插件管理器: 通过类名反射实例化, 但实现类在外部 JAR, 仅保留接口调用 ---
-keep class com.canglan.cyancodex.plugin.PluginManager { *; }
# ⚠️ PluginBuilder 会把 "com.canglan.cyancodex.plugin.CyanPlugin" 当**字符串**写进生成的
# 插件源码与 META-INF/services 文件名。R8 不改字符串常量, 所以上面那几条 keep 是它的命根子,
# 一条都不能删 —— 删了插件工坊生成的插件就全部加载失败。

# --- OkHttp/Retrofit 内部反射 (官方规则兜底) ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes *Annotation*

# --- Retrofit 官方 keep 规则: suspend 函数返回类型经 Continuation 泛型传递 ---
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# --- Gson TypeToken 泛型 ---
# ★ 必须带 allowobfuscation —— 这是 Gson 官方发布的 consumer 规则原文。
#   写成不带 allowobfuscation 的 `-keep class * extends ...TypeToken` 会把每个
#   `object : TypeToken<...>() {}` 匿名子类**连同外层类名一起钉死**:
#   实测 Session / CyanCodexIme / FloatWindowService / AiScreenKt 四个外层类 +
#   6 个匿名子类 = 10 个类名白送出去。泛型信息靠 Signature 属性保留, 与类名无关。
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
-keep class com.google.gson.Gson { *; }

# --- Compose ---
# ⛔ 这里**故意不加** `-keepclassmembers class * { @androidx.compose.runtime.Composable *; }`。
#   那条是 Compose 1.0 时代的老建议, 官方文档早已撤掉; 代价是把**每一个 @Composable
#   函数名**都保留下来(本项目 121 个按钮 + 全部页面/组件函数), 等于白送一张函数清单。
#   AGP 8+/R8 对 Compose 有内建支持, 可组合函数的调用全是直接调用, 不需要反射保留。
#   ⚠️ 若出现「界面空白 / 组合崩溃 / 点击无反应」, 第一件事就是把它加回来:
#       -keepclassmembers class * { @androidx.compose.runtime.Composable *; }
-dontwarn androidx.compose.**

# --- 悬浮窗/输入法服务 (系统通过类名/Manifest 引用) ---
-keep class com.canglan.cyancodex.ime.CyanCodexIme { *; }
-keep class com.canglan.cyancodex.overlay.FloatWindowService { *; }

# --- 避免 enum 被精简破坏 when 表达式 ---
-keepclassmembers enum * { *; }

# --- 液态玻璃库 (io.github.kyant0:backdrop) ---
# 原先是 -keep class com.kyant.** { *; } —— 整库原样保留, 既臃肿又暴露。
# 该库是纯 Compose + RuntimeShader, 不用反射, 所以只需压掉警告, 允许 R8 裁剪 + 混淆。
# ⚠️ 如果玻璃外观出问题(变平/变白/不折射), 第一件事就是把下面这行换回:
#     -keep class com.kyant.** { *; }
-dontwarn com.kyant.**
