# R8 规则（release 构建启用）

# ---------------------------------------------------------------------------
# 只压缩、不混淆：保留全部类名与方法名。
#
# 为什么放弃混淆（体积只差一点点）：
#   1. 这个 App 还没在真机上验证过，一旦闪退，混淆后的堆栈是
#      `m0.s.a(Unknown Source)`，完全没法定位；保留名字则能直接看到
#      `ReaderScreenKt.flipTo` 这种。
#   2. 混淆是 R8 里最容易碰反射/按名加载的地方（Room、Coil、SAF），
#      省下的那点体积不值得冒「只有 release 崩、debug 正常」的风险。
# 真正省体积的是「删掉没用到的代码」（shrinking），那部分保留着。
# 等 App 在真机跑稳了，删掉这行就能拿到额外 15~25% 的收益。
# ---------------------------------------------------------------------------
-dontobfuscate

# ---------------------------------------------------------------------------
# 本工程自己的代码全部保留。
# 自己的代码量很小（19 个文件），全保留可以彻底避免反射类问题。
# ---------------------------------------------------------------------------
-keep class com.mangashelf.reader.** { *; }

# Room：数据库实现类是按名字反射加载的，必须保留
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class *
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# Kotlin 元数据（协程/反射兜底）
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
