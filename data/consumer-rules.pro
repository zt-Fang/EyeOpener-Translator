# data 模块消费者混淆规则
# 此文件自动应用到依赖 data 模块的模块

# ==============================
# Room - 数据库实体/DAO/数据库类不能混淆
# ==============================
-keep class io.github.ztfang.eye.data.local.entity.** { *; }
-keep class io.github.ztfang.eye.data.local.dao.** { *; }
-keep class io.github.ztfang.eye.data.local.database.** { *; }

# ==============================
# DataStore - 无需特殊 keep（编译期安全）
# ==============================
