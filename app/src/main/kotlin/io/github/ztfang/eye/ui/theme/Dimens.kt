package io.github.ztfang.eye.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 间距/尺寸 Token。
 *
 * 遵循 AGENTS.md「字体与间距必须使用系统 Token(禁止写死像素)」：
 * Composable 内禁止直接写 `16.dp`、`56.dp` 这类裸数值,统一引用本文件。
 * 后续若需要适配平板/折叠屏,只需在此集中调整。
 */
object Dimens {
    // 通用间距(对应 M3 4dp 栅格)
    val SpaceXxs: Dp = 2.dp
    val SpaceXs: Dp = 4.dp
    val SpaceSm: Dp = 8.dp
    val SpaceMd: Dp = 16.dp
    val SpaceLg: Dp = 24.dp
    val SpaceXl: Dp = 32.dp

    // 业务尺寸
    val OverlayPreviewHeight: Dp = 200.dp
    val VoiceButtonHeight: Dp = 56.dp
    val LanguageListMinHeight: Dp = 200.dp
    val CardElevation: Dp = 4.dp

    // ---- 新主屏(glassmorphism)新增 ----
    // 圆角
    val CornerSm: Dp = 12.dp
    val CornerMd: Dp = 16.dp
    val CornerLg: Dp = 20.dp
    val CornerXl: Dp = 24.dp

    // 卡片内边距
    val CardPadding: Dp = 20.dp
    val ScreenPaddingH: Dp = 20.dp
    val ScreenPaddingTop: Dp = 12.dp
    val SectionGap: Dp = 16.dp

    // 顶部 AppBar
    val TopAppBarHeight: Dp = 64.dp
    val TopAppBarIconBox: Dp = 40.dp

    // 悬浮字幕开关卡片
    val OverlayToggleHeight: Dp = 96.dp
    val OverlayToggleIconBox: Dp = 48.dp
    val OverlayToggleIcon: Dp = 24.dp

    // 引擎三列卡片
    val EngineCardMinHeight: Dp = 132.dp
    val EngineCardIconBox: Dp = 40.dp
    val EngineCardIcon: Dp = 22.dp

    // 语言切换
    val LanguageCardMinHeight: Dp = 92.dp
    val LanguageCardFlagSize: Dp = 28.dp
    val SwapButtonSize: Dp = 44.dp
    val SwapButtonIcon: Dp = 22.dp

    // 阴影
    val GlassShadowElevation: Dp = 8.dp

    // 玻璃面 alpha
    const val GlassSurfaceAlpha: Float = 0.55f
    const val GlassBorderAlpha: Float = 0.55f
    const val GlassHighlightAlpha: Float = 0.85f

    // ---- 助手屏(AI chat)新增 ----
    // 气泡
    val ChatAvatarSize: Dp = 32.dp
    val ChatAvatarIcon: Dp = 18.dp
    val ChatBubbleMaxWidth: Dp = 300.dp
    val ChatBubbleCornerLg: Dp = 22.dp
    val ChatBubbleCornerSm: Dp = 8.dp
    val MessageSpacing: Dp = 12.dp
    val MessageMetaSpacing: Dp = 4.dp
    val MessageMetaIcon: Dp = 12.dp
    val OnlineDotSize: Dp = 6.dp
    val ReceiptIconExtra: Dp = 2.dp
    // 输入栏
    val InputBarHeight: Dp = 60.dp
    val InputBarIconSize: Dp = 24.dp
    val InputBarSendBox: Dp = 44.dp
    // 顶部栏
    val AssistantTopBarHeight: Dp = 64.dp
    val AssistantTopBarActionPad: Dp = 12.dp

    // ---- 设置屏新增 ----
    val SettingsTopBarHeight: Dp = 64.dp
    val SettingsCardCorner: Dp = 20.dp
    val SettingsCardPadding: Dp = 4.dp      // 卡片内边距(行有自己的 padding)
    val SettingsRowHeight: Dp = 60.dp
    val SettingsRowPaddingH: Dp = 16.dp
    val SettingsRowPaddingV: Dp = 12.dp
    val SettingsIconBox: Dp = 40.dp
    val SettingsIconSize: Dp = 22.dp
    val SettingsChevronSize: Dp = 20.dp
    val SettingsRowSpacing: Dp = 0.dp       // 行间距(由卡片内 spacing 提供)
    val SettingsSectionGap: Dp = 24.dp      // 区段之间的间距
    val SettingsSectionTitleGap: Dp = 8.dp  // 标题与卡片之间的间距
    val SettingsRowInternalGap: Dp = 14.dp  // 行内图标与文字间距
    val SettingsValueTextGap: Dp = 8.dp     // 文字与右侧值的间距
    val SettingsDividerHairline: Dp = 0.5.dp // 行间分割线粗细

    // ---- 个性化设置详情页 ----
    val PersonalizationTopBarHeight: Dp = 64.dp           // 顶部栏高度
    val PersonalizationColorSwatch: Dp = 48.dp            // 颜色方块边长
    val PersonalizationSwatchSelectedBorder: Dp = 3.dp    // 选中态外圈描边
    val PersonalizationPercentBadgeWidth: Dp = 44.dp      // 百分比浮标宽度
    val PersonalizationPercentBadgeHeight: Dp = 24.dp     // 百分比浮标高度
    val PersonalizationRadioSize: Dp = 22.dp              // 单选圆点外径
    val PersonalizationRadioSelectedBorder: Dp = 6.dp     // 选中态圆点填充
    val PersonalizationRadioUnselectedBorder: Dp = 2.dp   // 未选中态圆点描边
    val PersonalizationDisplayItemPaddingV: Dp = 14.dp    // 显示模式行垂直内边距
    val PersonalizationSectionGap: Dp = 20.dp             // 区段之间的间距
    val PersonalizationSectionTitleGap: Dp = 8.dp         // 标题与卡片之间的间距
    val PersonalizationSliderVPadding: Dp = 16.dp         // 滑块卡片垂直内边距
    val PersonalizationFontSizeLabelGap: Dp = 8.dp        // 字体大小当前值与滑块的间距
    val PersonalizationFontSizeScaleGap: Dp = 4.dp        // 滑块与底部刻度行的间距
}
