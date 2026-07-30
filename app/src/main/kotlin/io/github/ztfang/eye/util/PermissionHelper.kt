package io.github.ztfang.eye.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.ztfang.eye.R

/**
 * 悬浮字幕运行时权限策略工具类。
 * 
 * Activity 层负责通过 Activity Result API 实际请求权限；本工具类仅检查状态并生成标准请求列表。
 * 
 * 无法在运行时授予且需要跳转设置页面的权限（如 SYSTEM_ALERT_WINDOW、MANAGE_EXTERNAL_STORAGE）
 * 不在此处理，直接调用 [Settings.canDrawOverlays] 等方法。
 */
object PermissionHelper {

    /**
     * 当前 Android 版本下启动悬浮字幕所需的最小运行时权限列表。
     *
     * - POST_NOTIFICATIONS: Android 13+ (API 33)。没有它前台 Service 通知会静默失败，
     *   Service 可能在几秒内被系统杀死。
     * - FOREGROUND_SERVICE_MICROPHONE: Android 14+ (API 34)。技术上是安装时自动授予的
     *   normal 权限，但放在此处记录以便与其他权限一起管理。
     * - RECORD_AUDIO: 所有 Android 版本。
     */
    fun requiredRuntimePermissions(): List<String> {
        val perms = mutableListOf<String>()
        perms += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        return perms
    }

    /** 获取尚未授予的权限列表 */
    fun missingPermissions(context: Context): List<String> =
        requiredRuntimePermissions().filter { perm ->
            ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
        }

    /**
     * 权限说明字符串资源 ID，与 [requiredRuntimePermissions] 返回顺序一致，
     * 用于在请求权限前向用户展示说明。
     */
    fun rationaleResIdFor(permission: String): Int = when (permission) {
        Manifest.permission.RECORD_AUDIO ->
            R.string.permission_rationale_record_audio
        Manifest.permission.POST_NOTIFICATIONS ->
            R.string.permission_rationale_post_notifications
        else -> R.string.permission_rationale_generic
    }

    /**
     * 权限说明字符串，与 [requiredRuntimePermissions] 返回顺序一致，
     * 用于在请求权限前向用户展示说明。
     */
    fun rationaleFor(context: Context, permission: String): String =
        context.getString(rationaleResIdFor(permission))
}
