package io.github.ztfang.eye.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ztfang.eye.R
import io.github.ztfang.eye.domain.model.ModelCatalog
import io.github.ztfang.eye.domain.model.ModelState
import io.github.ztfang.eye.domain.model.ModelStatus
import io.github.ztfang.eye.domain.model.SherpaOnnxModel
import io.github.ztfang.eye.domain.model.VoskLanguage
import io.github.ztfang.eye.ui.theme.Dimens
import io.github.ztfang.eye.viewmodel.SettingsViewModel
import java.io.File
import kotlin.math.abs

/** Sherpa-ONNX 覆盖的语言代码集合（Vosk 下载列表中不再显示）。
 *
 *  包含：
 *  - X-ASR 覆盖：zh, en（Nemotron 列表中已含）
 *  - Nemotron 3.5 覆盖：ready + broad 共 28 种语言
 *  - BN Vosk 覆盖：bn
 *
 *  注意：en-in（印度英语）是 Vosk 独有变体，Sherpa-ONNX 无对应模型，保留在 Vosk 列表。
 *  使用精确匹配（非 substringBefore），避免误过滤 en-in 等变体。
 */
private val SHERPA_COVERED_LANGS: Set<String> by lazy {
    buildSet {
        // Nemotron ready + broad（含 zh, en）
        addAll(SherpaOnnxModel.NEMOTRON_READY_LANGUAGES)
        addAll(SherpaOnnxModel.NEMOTRON_BROAD_LANGUAGES)
        // BN Vosk
        add("bn")
    }
}

/** LocalModelsScreen UI 层日志标签 */
private const val TAG_UI = "LocalModelsScreen"

/**
 * 模型下载页面。
 *
 * 仅展示可下载的语言识别模型，无小标题/提示卡片。
 * 顺序：X-ASR（中英文）→ Nemotron 3.5（多语种）→ BN Vosk（孟加拉语）→ 各 Vosk 语种。
 */
@Composable
fun LocalModelsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val allModels by settingsViewModel.allModels.collectAsStateWithLifecycle(emptyList())
    val downloadProgressMap by settingsViewModel.downloadProgressMap.collectAsStateWithLifecycle(emptyMap())

    // 监听变化打日志（UI层可观测性，方便定位按钮不显示问题）
    androidx.compose.runtime.LaunchedEffect(allModels, downloadProgressMap) {
        val summary = allModels.joinToString { "[${it.modelName}=${it.status.name}@${it.progress} localPathExists=${it.localPath?.let { p -> java.io.File(p).exists() }}]" }
        Log.d(TAG_UI, "[UI_STATE] allModels=${allModels.size}: $summary")
        Log.d(TAG_UI, "[UI_STATE] downloadProgressMap=${downloadProgressMap.size}: ${downloadProgressMap.keys.joinToString { "$it=${downloadProgressMap[it]?.fraction}" }}")
    }

    // Vosk 列表仅保留 Sherpa-ONNX 未覆盖的语言（精确匹配，保留 en-in 等变体）
    val voskLanguages = remember {
        VoskLanguage.getAll().filter { it.code !in SHERPA_COVERED_LANGS }
    }
    // 模型顺序：中英文 → 多语种 → 孟加拉语
    val sherpaOnnxModels = remember {
        settingsViewModel.getSherpaOnnxModels().sortedWith(compareBy {
            when (it) {
                SherpaOnnxModel.X_ASR_ZH_EN_960MS -> 0
                SherpaOnnxModel.NEMOTRON_3_5_320MS_INT8 -> 1
                SherpaOnnxModel.BN_VOSK_2026_02_09 -> 2
                else -> 99
            }
        })
    }

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // 顶部栏：圆形玻璃返回按钮 + 标题
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimens.PersonalizationTopBarHeight)
                .padding(horizontal = Dimens.SpaceXs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(Dimens.TopAppBarIconBox)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.personalization_back_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(Dimens.SpaceSm))
            Text(
                text = stringResource(R.string.settings_row_local),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Spacer(modifier = Modifier.height(Dimens.SpaceSm))

        // 网络提示：多语种模型需访问 HuggingFace
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPaddingH),
            shape = RoundedCornerShape(Dimens.CornerMd),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        ) {
            Text(
                text = stringResource(R.string.local_model_huggingface_tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = Dimens.SpaceMd, vertical = Dimens.SpaceSm)
            )
        }

        Spacer(modifier = Modifier.height(Dimens.SpaceSm))

        // 直接列出所有模型
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPaddingH)
                .weight(1f),
            contentPadding = PaddingValues(bottom = Dimens.SpaceLg)
        ) {
            // Sherpa-ONNX 系列模型
            items(sherpaOnnxModels) { model ->
                val modelName = ModelCatalog.sherpaOnnxModelName(model.modelId)
                val modelState = allModels.find { it.modelName == modelName }
                val isDownloaded = modelState.isAvailable()
                val isDownloading = modelState.isInProgress() || downloadProgressMap.containsKey(modelName)
                val progress = downloadProgressMap[modelName]?.fraction ?: 0f

                // 关键UI判断日志（SideEffect：每次组合成功后执行）
                androidx.compose.runtime.SideEffect {
                    Log.d(TAG_UI, "[UI_SHERPA] ${model.modelId.take(30)}...: modelName=$modelName")
                    Log.d(TAG_UI, "    allModels中是否存在=${modelState != null}" +
                            " status=${modelState?.status?.name} progress=${modelState?.progress}" +
                            " localPath=${modelState?.localPath}" +
                            " localPath.exists=${modelState?.localPath?.let { File(it).exists() }}")
                    Log.d(TAG_UI, "    计算结果: isDownloaded=$isDownloaded  isDownloading=$isDownloading  progress=$progress")
                }

                // 每个模型独立配色：X-ASR 青绿、Nemotron NVIDIA绿、BN 橙黄
                val (accentStart, accentEnd) = when (model) {
                    SherpaOnnxModel.X_ASR_ZH_EN_960MS ->
                        Color(0xFF4DD0E1) to Color(0xFF81C784)
                    SherpaOnnxModel.NEMOTRON_3_5_320MS_INT8 ->
                        Color(0xFF76B900) to Color(0xFF4CAF50)
                    SherpaOnnxModel.BN_VOSK_2026_02_09 ->
                        Color(0xFFFFB74D) to Color(0xFFFF8A65)
                    else ->
                        Color(0xFF4DD0E1) to Color(0xFF81C784)
                }
                // 标题国际化：X-ASR / Nemotron 走 strings.xml，BN 用原生孟加拉文
                val modelTitle = when (model) {
                    SherpaOnnxModel.X_ASR_ZH_EN_960MS ->
                        stringResource(R.string.local_model_x_asr_name)
                    SherpaOnnxModel.NEMOTRON_3_5_320MS_INT8 ->
                        stringResource(R.string.local_model_nemotron_name)
                    else -> model.displayName
                }
                ModelRowItem(
                    title = modelTitle,
                    size = "${(model.sizeBytes / 1024 / 1024)} MB",
                    accentStart = accentStart,
                    accentEnd = accentEnd,
                    downloaded = isDownloaded,
                    downloading = isDownloading,
                    progress = progress,
                    onDownload = { settingsViewModel.downloadSherpaOnnxModel(model.modelId) },
                    onDelete = { settingsViewModel.deleteModel(modelName) }
                )
            }

            // Vosk 系列
            items(voskLanguages) { lang ->
                val modelName = ModelCatalog.voskModelName(lang.code)
                val modelState = allModels.find { it.modelName == modelName }
                val isDownloaded = modelState.isAvailable()
                val isDownloading = modelState.isInProgress() || downloadProgressMap.containsKey(modelName)
                val progress = downloadProgressMap[modelName]?.fraction ?: 0f

                // Vosk UI判断日志（SideEffect：每次组合成功后执行）
                androidx.compose.runtime.SideEffect {
                    Log.d(TAG_UI, "[UI_VOSK] ${lang.code}: modelName=$modelName")
                    Log.d(TAG_UI, "    allModels中是否存在=${modelState != null}" +
                            " status=${modelState?.status?.name} progress=${modelState?.progress}" +
                            " localPath=${modelState?.localPath}" +
                            " localPath.exists=${modelState?.localPath?.let { File(it).exists() }}")
                    Log.d(TAG_UI, "    计算结果: isDownloaded=$isDownloaded  isDownloading=$isDownloading  progress=$progress")
                }

                ModelRowItem(
                    title = lang.displayName,
                    size = "${(lang.sizeBytes / 1024 / 1024)} MB",
                    accentStart = voskAccentStart(lang.code),
                    accentEnd = voskAccentEnd(lang.code),
                    downloaded = isDownloaded,
                    downloading = isDownloading,
                    progress = progress,
                    onDownload = { settingsViewModel.downloadVoskModel(lang.code) },
                    onDelete = { settingsViewModel.deleteModel(modelName) }
                )
            }
        }
    }
}

/**
 * 基于 code hash 生成起始色，保证 30 种语言各有独特色彩。
 * HSV 模型：色相由 hash 决定，饱和度 0.65，明度 0.85。
 */
private fun voskAccentStart(code: String): Color {
    val hue = (abs(code.hashCode()) % 360).toFloat()
    return hsvToColor(hue, 0.65f, 0.85f)
}

/** 基于 code hash 生成结束色，色相偏移 30° 形成渐变。 */
private fun voskAccentEnd(code: String): Color {
    val hue = (abs(code.hashCode()) % 360).toFloat()
    return hsvToColor((hue + 30f) % 360f, 0.7f, 0.7f)
}

/**
 * 判断模型状态是否已下载可用。
 *
 * 用 ordinal 比较替代 name 比较，避免 release 混淆时枚举 name 匹配失效；
 * 并用 localPath 文件存在性兜底，双重保证。
 */
private fun ModelState?.isAvailable(): Boolean {
    if (this == null) return false
    val statusHit = status.ordinal == ModelStatus.AVAILABLE.ordinal
    val pathHit = localPath != null && File(localPath).exists()
    return statusHit || pathHit
}

/**
 * 判断模型状态是否下载中。
 * ordinal 比较避免枚举名混淆导致匹配失败。
 */
private fun ModelState?.isInProgress(): Boolean {
    if (this == null) return false
    return status.ordinal == ModelStatus.DOWNLOADING.ordinal
}

/** HSV → Compose Color 转换 */
private fun hsvToColor(h: Float, s: Float, v: Float): Color {
    val c = v * s
    val hp = h / 60f
    val x = c * (1 - abs(hp % 2 - 1))
    val (r, g, b) = when (hp.toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = v - c
    return Color(r + m, g + m, b + m)
}

@Composable
private fun ModelRowItem(
    title: String,
    size: String,
    accentStart: Color,
    accentEnd: Color,
    downloaded: Boolean,
    downloading: Boolean,
    progress: Float,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.CornerLg))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.85f),
                        Color.White.copy(alpha = 0.15f),
                    )
                ),
                shape = RoundedCornerShape(Dimens.CornerLg)
            )
            .padding(horizontal = Dimens.SpaceMd, vertical = Dimens.SpaceSm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：语言图标 + 名称 + 大小
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(accentStart, accentEnd))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (downloaded) Icons.Filled.Check else Icons.Filled.Download,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(Dimens.SpaceSm))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(Dimens.SpaceXxs))
                // 副标题（模型类型描述），无则显示大小
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(Dimens.SpaceXxs))
                }
                Text(
                    text = size,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (downloaded) accentEnd.copy(alpha = 0.75f)
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 下载中：显示进度条
        if (downloading) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(80.dp)
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(percent = 50)),
                    color = accentEnd,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                )
                Spacer(modifier = Modifier.height(Dimens.SpaceXxs))
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // 右侧：上下排列的按钮（下载 / 删除）
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXxs)
            ) {
                if (!downloaded) {
                    IconButton(
                        onClick = onDownload,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = stringResource(R.string.local_model_action_download),
                            tint = accentEnd,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                if (downloaded) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.local_model_action_delete),
                            tint = Color(0xFFE57373),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
