package io.github.ztfang.eye.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.ztfang.eye.R
import io.github.ztfang.eye.ui.theme.Dimens
import kotlinx.coroutines.launch

/**
 * 首次使用引导页（纯展示，无业务逻辑）。
 *
 * 3 个页面（HorizontalPager 左右滑动）：
 *  1. 欢迎页 — 介绍应用定位和核心特性
 *  2. 语言选择介绍 — 说明源语言/目标语言，提示 Google 网络和国内大模型备选
 *  3. 悬浮字幕介绍 — 操作说明和功能介绍
 *
 *  注意：此页面只做 UI 展示，不依赖 ViewModel，不写入任何设置。
 *  完成引导的状态由调用方负责。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()

    fun nextPage() {
        if (pagerState.currentPage < 2) {
            coroutineScope.launch {
                pagerState.animateScrollToPage(pagerState.currentPage + 1)
            }
        } else {
            onFinished()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFE8F1FF),
                        Color(0xFFF4F8FF),
                        Color(0xFFFAFCFF),
                    )
                )
            )
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> WelcomePage()
                1 -> LanguageIntroPage()
                2 -> SubtitleIntroPage()
            }
        }

        // 底部指示器 + 按钮（使用 Box 替代 Button，避免默认 elevation/shadow 被导航栏裁剪）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = Dimens.ScreenPaddingH)
                .padding(top = 16.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 页面指示器
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(3) { index ->
                    val isSelected = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .size(if (isSelected) 12.dp else 8.dp)
                            .clip(CircleShape)
                            .then(
                                if (isSelected) {
                                    Modifier.background(
                                        Brush.horizontalGradient(
                                            colors = listOf(Color(0xFF667EEA), Color(0xFF764BA2))
                                        )
                                    )
                                } else {
                                    Modifier.background(
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                    )
                                }
                            )
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            // 主按钮（渐变背景，无默认阴影，避免底部被裁剪）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color(0xFF667EEA), Color(0xFF764BA2), Color(0xFFF093FB))
                        )
                    )
                    .clickable(onClick = { nextPage() }, role = Role.Button),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (pagerState.currentPage) {
                        0 -> stringResource(R.string.onboarding_get_started)
                        1 -> stringResource(R.string.onboarding_next)
                        else -> stringResource(R.string.onboarding_start_using)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
            // 跳过按钮（最后一页不显示）
            if (pagerState.currentPage < 2) {
                TextButton(onClick = onFinished) {
                    Text(
                        text = stringResource(R.string.onboarding_skip),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 第 1 页：欢迎页
// ---------------------------------------------------------------------------

@Composable
private fun WelcomePage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.ScreenPaddingH)
            .padding(top = 48.dp, bottom = 240.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 大图标：使用桌面图标（蓝紫渐变+白色眼睛）
        Image(
            painter = painterResource(id = R.mipmap.ic_launcher),
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            contentScale = ContentScale.Crop
        )
        Spacer(modifier = Modifier.height(28.dp))
        Text(
            text = stringResource(R.string.onboarding_welcome_title, stringResource(R.string.app_name)),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.onboarding_welcome_subtitle),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(36.dp))
        // 特性列表（三张独立卡片）
        FeatureCard(
            icon = Icons.Filled.Subtitles,
            title = stringResource(R.string.onboarding_feature_subtitles_title),
            desc = stringResource(R.string.onboarding_feature_subtitles_desc)
        )
        Spacer(modifier = Modifier.height(12.dp))
        FeatureCard(
            icon = Icons.Filled.AutoAwesome,
            title = stringResource(R.string.onboarding_feature_engines_title),
            desc = stringResource(R.string.onboarding_feature_engines_desc)
        )
        Spacer(modifier = Modifier.height(12.dp))
        FeatureCard(
            icon = Icons.Filled.Language,
            title = stringResource(R.string.onboarding_feature_langs_title),
            desc = stringResource(R.string.onboarding_feature_langs_desc)
        )
    }
}

/**
 * 特性卡片：简洁风格，白色背景 + 圆角 + 细描边。
 */
@Composable
private fun FeatureCard(icon: ImageVector, title: String, desc: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color(0xFF667EEA), Color(0xFF764BA2))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 第 2 页：语言选择介绍
// ---------------------------------------------------------------------------

/** 语言项（纯展示用） */
private data class LangItem(val displayName: String, val nativeName: String)

/** 示例源语言（ASR 识别） */
private val SampleSourceLangs = listOf(
    LangItem("中文", "中文"),
    LangItem("英语", "English"),
    LangItem("日语", "日本語"),
    LangItem("韩语", "한국어")
)

/** 示例目标语言（翻译输出） */
private val SampleTargetLangs = listOf(
    LangItem("中文", "中文"),
    LangItem("英语", "English"),
    LangItem("日语", "日本語"),
    LangItem("韩语", "한국어")
)

@Composable
private fun LanguageIntroPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.ScreenPaddingH)
            .padding(top = 48.dp, bottom = 240.dp)
    ) {
        Text(
            text = stringResource(R.string.onboarding_lang_title),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_lang_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(28.dp))

        // 源语言展示
        Text(
            text = stringResource(R.string.onboarding_lang_source),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(12.dp))
        LanguageGrid(
            languages = SampleSourceLangs,
            selectedIndex = 0
        )

        Spacer(modifier = Modifier.height(28.dp))

        // 目标语言展示
        Text(
            text = stringResource(R.string.onboarding_lang_target),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(12.dp))
        LanguageGrid(
            languages = SampleTargetLangs,
            selectedIndex = 0
        )

        Spacer(modifier = Modifier.height(28.dp))

        // 使用提示卡片（渐变背景）
        GradientTipCard(
            gradient = Brush.horizontalGradient(
                colors = listOf(Color(0xFF667EEA), Color(0xFF764BA2))
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.onboarding_lang_tip_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "• ${stringResource(R.string.onboarding_lang_tip_local)}\n\n" +
                                    "• ${stringResource(R.string.onboarding_lang_tip_cloud)}\n\n" +
                                    "• ${stringResource(R.string.onboarding_lang_tip_ai)}\n\n" +
                                    "• ${stringResource(R.string.onboarding_lang_tip_asr)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }
    }
}

/** 语言选择网格（纯展示，2 列） */
@Composable
private fun LanguageGrid(
    languages: List<LangItem>,
    selectedIndex: Int
) {
    val rows = languages.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEachIndexed { rowIdx, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { colIdx, lang ->
                    val index = rowIdx * 2 + colIdx
                    val isSelected = index == selectedIndex
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isSelected) Color.White
                                else Color.White.copy(alpha = 0.6f)
                            )
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) Color(0xFF667EEA)
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(14.dp)
                            )
                            .padding(vertical = 14.dp, horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = Color(0xFF667EEA),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Text(
                                text = lang.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = lang.nativeName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 第 3 页：悬浮字幕介绍
// ---------------------------------------------------------------------------

@Composable
private fun SubtitleIntroPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.ScreenPaddingH)
            .padding(top = 48.dp, bottom = 240.dp)
    ) {
        Text(
            text = stringResource(R.string.onboarding_overlay_title),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_overlay_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(28.dp))

        // 操作说明（四张独立卡片）
        StepCard(
            step = "01",
            title = stringResource(R.string.onboarding_overlay_step1_title),
            desc = stringResource(R.string.onboarding_overlay_step1_desc)
        )
        Spacer(modifier = Modifier.height(12.dp))
        StepCard(
            step = "02",
            title = stringResource(R.string.onboarding_overlay_step2_title),
            desc = stringResource(R.string.onboarding_overlay_step2_desc)
        )
        Spacer(modifier = Modifier.height(12.dp))
        StepCard(
            step = "03",
            title = stringResource(R.string.onboarding_overlay_step3_title),
            desc = stringResource(R.string.onboarding_overlay_step3_desc)
        )
        Spacer(modifier = Modifier.height(12.dp))
        StepCard(
            step = "04",
            title = stringResource(R.string.onboarding_overlay_step4_title),
            desc = stringResource(R.string.onboarding_overlay_step4_desc)
        )
        Spacer(modifier = Modifier.height(28.dp))

        // 底部提示（渐变背景）
        GradientTipCard(
            gradient = Brush.horizontalGradient(
                colors = listOf(Color(0xFFF093FB), Color(0xFF764BA2))
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Filled.Subtitles,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.onboarding_overlay_cta_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.onboarding_overlay_cta_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

/**
 * 步骤卡片：简洁风格，白色背景 + 圆角 + 细描边。
 */
@Composable
private fun StepCard(step: String, title: String, desc: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color(0xFF667EEA), Color(0xFF764BA2))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = step,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 渐变提示卡片：渐变背景 + 圆角，无阴影。
 */
@Composable
private fun GradientTipCard(
    gradient: Brush,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(gradient)
    ) {
        content()
    }
}
