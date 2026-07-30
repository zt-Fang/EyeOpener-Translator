# domain 模块消费者混淆规则
# 所有依赖 domain 模块的模块都会自动应用这些规则
# 防止跨模块 R8 优化时误删 domain 层模型类

# ==============================
# 模型层数据类（Room/JSON 序列化依赖名称匹配，必须保留
# ==============================
-keep class com.example.eye.domain.model.** { *; }
-keep enum com.example.eye.domain.model.** {
    <fields>;
    public *;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 保留 data class 的 componentN、copy、toString 等方法（避免 R8 可能误移除）
-keepclassmembers class com.example.eye.domain.model.** {
    public ** component1();
    public ** component2();
    public ** component3();
    public ** component4();
    public ** component5();
    public ** component6();
    public ** component7();
    public ** copy(...);
}
