package io.github.ztfang.eye.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.widget.Toast
import io.github.ztfang.eye.R
import io.github.ztfang.eye.domain.model.DisplayMode
import io.github.ztfang.eye.ui.theme.Dimens
import io.github.ztfang.eye.viewmodel.SettingsViewModel
import io.github.ztfang.eye.viewmodel.SubtitleManager

/**
 * 个性化设置详情页。
 *
 * 4 个分区(自上而下):
 *  1. 侧边栏颜色 — 6 个色块,带对勾高亮（持久化：accent_color_index）
 *  2. 背景透明度 — 滑动条 + 浮标式百分比提示（持久化：background_transparency）
 *  3. 字体大小 — 3 档(小/中/大)（持久化：font_size_index）
 *  4. 结果显示模式 — 原文 / 译文 / 双语,带图标 + 描述 + 单选圆点（持久化：display_mode）
 *
 * 全部状态通过 SettingsViewModel 持久化到 DataStore，重启后恢复。
 */
@Composable
fun PersonalizationScreen(
    onBack: () -> Unit,
    subtitleManager: SubtitleManager? = null,
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    // 个性化 3 项全部从 DataStore 读取，初始值与 DataStore 默认值一致
    val persistedColorIndex by settingsViewModel.accentColorIndex.collectAsStateWithLifecycle(initialValue = 1)
    val persistedTransparency by settingsViewModel.backgroundTransparency.collectAsStateWithLifecycle(initialValue = 0.75f)
    val persistedFontSize by settingsViewModel.fontSize.collectAsStateWithLifecycle(initialValue = 18f)

    // 本地 remember 缓存当前编辑值，避免 Flow 抖动；初始值取 DataStore
    var selectedColorIndex by remember(persistedColorIndex) { mutableIntStateOf(persistedColorIndex) }
    var transparency by remember(persistedTransparency) { mutableFloatStateOf(persistedTransparency) }
    var fontSize by remember(persistedFontSize) { mutableFloatStateOf(persistedFontSize) }

    val currentDisplayMode by settingsViewModel.displayMode.collectAsStateWithLifecycle(initialValue = DisplayMode.BILINGUAL)

    val displayModeIndex = remember(currentDisplayMode) {
        when (currentDisplayMode) {
            DisplayMode.SOURCE_ONLY -> 0
            DisplayMode.TRANSLATION_ONLY -> 1
            DisplayMode.BILINGUAL -> 2
        }
    }

    fun setDisplayMode(index: Int) {
        val mode = when (index) {
            0 -> DisplayMode.SOURCE_ONLY
            1 -> DisplayMode.TRANSLATION_ONLY
            else -> DisplayMode.BILINGUAL
        }
        settingsViewModel.setDisplayMode(mode)
    }

    // MediaProjection 授权启动器 — 选择"应用内声音"音频源时触发
    // 注意：不直接调用 getMediaProjection()，因为 Android 14+ 要求必须在前台服务中调用，
    //       否则抛出 SecurityException。这里只保存 token，服务启动后自行创建实例。
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            // 授权成功，保存 token（不立即创建 MediaProjection 实例）
            subtitleManager?.saveMediaProjectionToken(result.resultCode, result.data)
            // 保存音频源为"应用内声音"
            settingsViewModel.setAudioSource(1)
            Toast.makeText(context, "屏幕录制授权成功", Toast.LENGTH_SHORT).show()
        } else {
            // 用户拒绝授权，不切换音频源
            Toast.makeText(context, "未授权屏幕录制，无法使用应用内声音", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 切换音频源到"应用内声音"：
     * - Android 10 以下不支持，直接提示并回退
     * - Android 10+ 发起 MediaProjection 授权，用户同意后才保存设置
     */
    fun requestInternalAudioSource() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(context, "当前系统版本不支持应用内声音录制", Toast.LENGTH_LONG).show()
            return
        }
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(bottom = Dimens.SpaceMd),
        verticalArrangement = Arrangement.spacedBy(Dimens.PersonalizationSectionGap)
    ) {
        PersonalizationTopBar(onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPaddingH),
            verticalArrangement = Arrangement.spacedBy(Dimens.PersonalizationSectionGap)
        ) {
            // 1. 侧边栏颜色
            PersonalizationSection(stringResource(R.string.personalization_section_color)) {
                PersonalizationCard {
                    ColorSwatchRow(
                        selectedIndex = selectedColorIndex,
                        onSelect = {
                            selectedColorIndex = it
                            settingsViewModel.setAccentColorIndex(it)
                        }
                    )
                }
            }

            // 2. 背景透明度
            PersonalizationSection(stringResource(R.string.personalization_section_transparency)) {
                PersonalizationCard {
                    TransparencySlider(
                        value = transparency,
                        onValueChange = {
                            transparency = it
                            settingsViewModel.setBackgroundTransparency(it)
                        }
                    )
                }
            }

            // 3. 字体大小
            PersonalizationSection(stringResource(R.string.personalization_section_font_size)) {
                PersonalizationCard {
                    FontSizeSlider(
                        value = fontSize,
                        onValueChange = {
                            fontSize = it
                            settingsViewModel.setFontSize(it)
                        }
                    )
                }
            }

            // 4. 结果显示模式
            PersonalizationSection(stringResource(R.string.personalization_section_display_mode)) {
                PersonalizationCard {
                    DisplayModeItem(
                        icon = Icons.Filled.Subtitles,
                        title = stringResource(R.string.personalization_mode_source),
                        subtitle = stringResource(R.string.personalization_mode_source_desc),
                        selected = displayModeIndex == 0,
                        onClick = { setDisplayMode(0) }
                    )
                    PersonalizationHairline()
                    DisplayModeItem(
                        icon = Icons.Filled.Translate,
                        title = stringResource(R.string.personalization_mode_target),
                        subtitle = stringResource(R.string.personalization_mode_target_desc),
                        selected = displayModeIndex == 1,
                        onClick = { setDisplayMode(1) }
                    )
                    PersonalizationHairline()
                    DisplayModeItem(
                        icon = Icons.AutoMirrored.Filled.CompareArrows,
                        title = stringResource(R.string.personalization_mode_dual),
                        subtitle = stringResource(R.string.personalization_mode_dual_desc),
                        selected = displayModeIndex == 2,
                        onClick = { setDisplayMode(2) }
                    )
                }
            }

            // 5. 音频输入源（麦克风 / 应用内声音）
            val audioSource by settingsViewModel.audioSource.collectAsStateWithLifecycle(initialValue = 0)
            PersonalizationSection(stringResource(R.string.personalization_section_audio_source)) {
                PersonalizationCard {
                    AudioSourceItem(
                        icon = Icons.Filled.Mic,
                        title = stringResource(R.string.personalization_audio_source_mic),
                        subtitle = stringResource(R.string.personalization_audio_source_mic_desc),
                        selected = audioSource == 0,
                        onClick = { settingsViewModel.setAudioSource(0) }
                    )
                    PersonalizationHairline()
                    AudioSourceItem(
                        icon = Icons.Filled.HeadsetMic,
                        title = stringResource(R.string.personalization_audio_source_internal),
                        subtitle = stringResource(R.string.personalization_audio_source_internal_desc),
                        selected = audioSource == 1,
                        onClick = {
                            // 切换到应用内声音：先请求屏幕录制权限，授权成功后才保存
                            if (audioSource == 1) {
                                // 已经是应用内声音，不用操作
                            } else {
                                requestInternalAudioSource()
                            }
                        }
                    )
                }
            }

        }
    }
}

// ----------------------------------------------------------------------------
// 顶部栏
// ----------------------------------------------------------------------------

/**
 * 个性化详情页顶部:返回按钮 + 标题。
 * 64dp 高,圆形玻璃返回按钮(40dp)。
 */
@Composable
private fun PersonalizationTopBar(onBack: () -> Unit) {
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
            text = stringResource(R.string.personalization_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

// ----------------------------------------------------------------------------
// 区段 + 卡片容器
// ----------------------------------------------------------------------------

/** 小标题 + 卡片容器。 */
@Composable
private fun PersonalizationSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.PersonalizationSectionTitleGap)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = Dimens.SpaceXxs)
        )
        content()
    }
}

/** 玻璃卡(与设置页 SettingsCard 风格一致)。 */
@Composable
private fun PersonalizationCard(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(Dimens.SettingsCardCorner)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = Dimens.GlassShadowElevation,
                shape = shape,
                ambientColor = Color(0xFF1A73E8).copy(alpha = 0.10f),
                spotColor = Color(0xFF1A73E8).copy(alpha = 0.12f),
            )
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
            .border(
                BorderStroke(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = Dimens.GlassHighlightAlpha),
                            Color.White.copy(alpha = 0.15f),
                        )
                    )
                ),
                shape = shape
            )
            .padding(Dimens.SettingsCardPadding)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

/** 行间分割线(浅色)。 */
@Composable
private fun PersonalizationHairline() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(horizontal = Dimens.SettingsRowPaddingH),
        thickness = Dimens.SettingsDividerHairline,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f)
    )
}

// ----------------------------------------------------------------------------
// 1. 字体颜色（横向滚动色块，方块样式，可滑动，方便后续扩展更多颜色）
// ----------------------------------------------------------------------------

private data class SwatchItem(val color: Color, val labelRes: Int)

/** 字体颜色候选色（黑色已替换为白色）。放在横向滚动容器中，方便后续添加更多颜色。 */
private val SwatchPalette = listOf(
    SwatchItem(Color(0xFF8B7FD8), R.string.personalization_color_purple),
    SwatchItem(Color(0xFF1A73E8), R.string.personalization_color_blue),
    SwatchItem(Color(0xFF2EB89A), R.string.personalization_color_green),
    SwatchItem(Color(0xFFFF8F00), R.string.personalization_color_orange),
    SwatchItem(Color(0xFFE53935), R.string.personalization_color_red),
    SwatchItem(Color(0xFFFFFFFF), R.string.personalization_color_black), // 黑色替换为白色
)

/**
 * 字体颜色选择器。
 *
 * 使用横向滚动 Row 承载方块色块，优点：
 * - 色块大小完全一致（统一 44dp 方块）
 * - 颜色多时可横向滑动，不挤占空间
 * - 后续新增颜色只需在 SwatchPalette 中添加，UI 自动适配
 * - 选中态：白色描边 + 对勾
 */
@Composable
private fun ColorSwatchRow(
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    val scrollState = androidx.compose.foundation.rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = Dimens.SettingsRowPaddingH,
                vertical = Dimens.SettingsRowPaddingV
            ),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)
    ) {
        // 当前选中色名
        Text(
            text = stringResource(SwatchPalette[selectedIndex.coerceIn(0, SwatchPalette.lastIndex)].labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = Dimens.SpaceXxs)
        )
        // 横向滚动色块容器
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SwatchPalette.forEachIndexed { index, item ->
                ColorSwatch(
                    color = item.color,
                    label = stringResource(item.labelRes),
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) }
                )
            }
            // 末尾留白，方便滑到最后一个
            Spacer(Modifier.width(Dimens.SpaceXs))
        }
    }
}

/** 单个方块色块;选中态叠加白色描边 + 白色对勾。 */
@Composable
private fun ColorSwatch(
    color: Color,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val size = 44.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(Dimens.CornerSm))
            .background(color)
            .clickable(onClick = onClick)
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) Color.White else Color.Transparent,
                shape = RoundedCornerShape(Dimens.CornerSm)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = stringResource(
                    R.string.personalization_color_selected_cd, label
                ),
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

// ----------------------------------------------------------------------------
// 2. 透明度滑动条(浮标式百分比)
// ----------------------------------------------------------------------------

/**
 * 透明度滑块。
 *
 * 上方有一个跟随 thumb 横向移动的「百分比浮标」,用 BoxWithConstraints 取得轨道宽度,
 * 减去 thumb 半径后,按 value 比例定位浮标中心,再减去半个浮标宽度得到 offset。
 * 浮标带阴影 + 圆角 8dp,科技蓝填充。
 */
@Composable
private fun TransparencySlider(
    value: Float,
    onValueChange: (Float) -> Unit
) {
    val percent = (value * 100).toInt()
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = Dimens.SettingsRowPaddingH,
                vertical = Dimens.PersonalizationSliderVPadding
            )
    ) {
        val trackWidth = maxWidth
        val labelWidth = Dimens.PersonalizationPercentBadgeWidth
        // M3 Slider 默认 thumb 直径 20dp,半径 10dp;轨道起点/终点各内缩一个半径。
        val thumbRadius = 10.dp
        val thumbCenter = thumbRadius + (value * (trackWidth.value - thumbRadius.value * 2)).dp
        val labelOffset = (thumbCenter - labelWidth / 2)
            .coerceIn(0.dp, trackWidth - labelWidth)

        Column {
            // 百分比浮标
            Box(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .offset(x = labelOffset)
                        .width(labelWidth)
                        .height(Dimens.PersonalizationPercentBadgeHeight)
                        .shadow(
                            elevation = 2.dp,
                            shape = RoundedCornerShape(8.dp),
                            ambientColor = Color(0xFF1A73E8).copy(alpha = 0.25f)
                        )
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1A73E8)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.personalization_percent_value, percent),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(Modifier.height(Dimens.SpaceSm))
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color(0xFF1A73E8),
                    inactiveTrackColor = Color(0xFFE3EAF3),
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent
                )
            )
        }
    }
}

// ----------------------------------------------------------------------------
// 3. 字体大小（连续滑动，12sp~32sp）
// ----------------------------------------------------------------------------

/**
 * 字体大小滑块，连续值 12sp~32sp。
 *
 * 上方浮标显示当前 sp 值，中部 Slider 自由滑动（无刻度），底部标注「小 / 大」两端。
 * 样式与 TransparencySlider 一致：浮标 + 科技蓝轨道。
 */
@Composable
private fun FontSizeSlider(
    value: Float,
    onValueChange: (Float) -> Unit
) {
    val labelText = "%.1f".format(value)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = Dimens.SettingsRowPaddingH,
                vertical = Dimens.PersonalizationSliderVPadding
            )
    ) {
        val trackWidth = maxWidth
        val labelWidth = Dimens.PersonalizationPercentBadgeWidth
        // M3 Slider 默认 thumb 直径 20dp，半径 10dp
        val thumbRadius = 10.dp
        val progress = (value - 12f) / (32f - 12f)
        val thumbCenter = thumbRadius + (progress * (trackWidth.value - thumbRadius.value * 2)).dp
        val labelOffset = (thumbCenter - labelWidth / 2)
            .coerceIn(0.dp, trackWidth - labelWidth)

        Column {
            // 当前数值浮标
            Box(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .offset(x = labelOffset)
                        .width(labelWidth)
                        .height(Dimens.PersonalizationPercentBadgeHeight)
                        .shadow(
                            elevation = 2.dp,
                            shape = RoundedCornerShape(8.dp),
                            ambientColor = Color(0xFF1A73E8).copy(alpha = 0.25f)
                        )
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1A73E8)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = labelText,
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(Modifier.height(Dimens.SpaceSm))
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = 12f..32f,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color(0xFF1A73E8),
                    inactiveTrackColor = Color(0xFFE3EAF3),
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent
                )
            )
            // 底部刻度：小 / 大
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Dimens.PersonalizationFontSizeScaleGap),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.personalization_font_size_small),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.personalization_font_size_large),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ----------------------------------------------------------------------------
// 4. 结果显示模式(原文 / 译文 / 双语)
// ----------------------------------------------------------------------------

/**
 * 单条显示模式选项。
 *
 * 视觉:左侧 40dp 圆角图标盒(选中填充科技蓝,未选中灰色),中间标题 + 描述,
 * 右侧 22dp 圆形单选点(选中 6dp 内填蓝,未选中 2dp 灰描边)。
 * 选中行整体覆盖一层 6% 蓝色 tint 作为高亮。
 */
@Composable
private fun DisplayModeItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val accent = Color(0xFF1A73E8)
    val iconBoxColor = if (selected) accent
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f)
    val iconTint = if (selected) Color.White
    else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) accent.copy(alpha = 0.06f) else Color.Transparent)
            .padding(
                horizontal = Dimens.SettingsRowPaddingH,
                vertical = Dimens.PersonalizationDisplayItemPaddingV
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图标盒
        Box(
            modifier = Modifier
                .size(Dimens.SettingsIconBox)
                .clip(RoundedCornerShape(Dimens.CornerSm))
                .background(iconBoxColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(Dimens.SettingsIconSize)
            )
        }
        Spacer(Modifier.width(Dimens.SettingsRowInternalGap))
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 单选圆点
        Box(
            modifier = Modifier
                .size(Dimens.PersonalizationRadioSize)
                .clip(CircleShape)
                .border(
                    width = if (selected) Dimens.PersonalizationRadioSelectedBorder
                    else Dimens.PersonalizationRadioUnselectedBorder,
                    color = if (selected) accent
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    shape = CircleShape
                )
        )
    }
}

// ----------------------------------------------------------------------------
// 6. 音频输入源
// ----------------------------------------------------------------------------

/**
 * 音频输入源单选项。
 * 样式与 DisplayModeItem 一致：左侧图标盒 + 中间标题描述 + 右侧单选点。
 */
@Composable
private fun AudioSourceItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val accent = Color(0xFF1A73E8)
    val iconBoxColor = if (selected) accent
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f)
    val iconTint = if (selected) Color.White
    else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) accent.copy(alpha = 0.06f) else Color.Transparent)
            .padding(
                horizontal = Dimens.SettingsRowPaddingH,
                vertical = Dimens.PersonalizationDisplayItemPaddingV
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图标盒
        Box(
            modifier = Modifier
                .size(Dimens.SettingsIconBox)
                .clip(RoundedCornerShape(Dimens.CornerSm))
                .background(iconBoxColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(Dimens.SettingsIconSize)
            )
        }
        Spacer(Modifier.width(Dimens.SettingsRowInternalGap))
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 单选圆点
        Box(
            modifier = Modifier
                .size(Dimens.PersonalizationRadioSize)
                .clip(CircleShape)
                .border(
                    width = if (selected) Dimens.PersonalizationRadioSelectedBorder
                    else Dimens.PersonalizationRadioUnselectedBorder,
                    color = if (selected) accent
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    shape = CircleShape
                )
        )
    }
}
