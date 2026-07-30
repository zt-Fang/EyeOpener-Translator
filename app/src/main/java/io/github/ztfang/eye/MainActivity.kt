package io.github.ztfang.eye

/**
 * 主界面 Activity，应用的入口点
 * 
 * 功能包含：
 * - 应用导航管理（NavHost + NavController）
 * - 底部导航栏（字幕、助手、设置三个 Tab）
 * - 权限处理（录音权限、悬浮窗权限）
 * - 悬浮字幕开关控制
 * - 语言选择与切换
 * - 翻译引擎选择（本地翻译 / 云端翻译 / AI 翻译）
 */
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

import android.content.Context
import android.content.Intent
import android.Manifest
import android.app.DownloadManager
import android.net.Uri
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import android.widget.Toast
import io.github.ztfang.eye.ui.components.AccentTone
import io.github.ztfang.eye.util.PermissionHelper
import io.github.ztfang.eye.ui.components.AssistantTopBar
import io.github.ztfang.eye.ui.components.ChatBubble
import io.github.ztfang.eye.ui.components.EngineAccent
import io.github.ztfang.eye.ui.components.EngineCard
import io.github.ztfang.eye.ui.components.GlassCard
import io.github.ztfang.eye.ui.components.GradientBackground
import io.github.ztfang.eye.ui.components.LanguageSwitcher
import io.github.ztfang.eye.ui.components.MessageInputBar
import io.github.ztfang.eye.ui.components.OverlayToggleCard
import io.github.ztfang.eye.ui.components.SettingsCard
import io.github.ztfang.eye.ui.screen.HistoryScreen
import io.github.ztfang.eye.ui.components.SettingsRow
import io.github.ztfang.eye.ui.components.TopAppBar
import io.github.ztfang.eye.ui.screens.ApiSettingsScreen
import io.github.ztfang.eye.ui.screens.CloudTranslationSettingsScreen
import io.github.ztfang.eye.ui.screens.LocalModelsScreen
import io.github.ztfang.eye.ui.screens.OnboardingScreen
import io.github.ztfang.eye.ui.screens.PersonalizationScreen
import io.github.ztfang.eye.ui.theme.Dimens

import io.github.ztfang.eye.domain.model.TranslationEngine
import io.github.ztfang.eye.ui.theme.EyeTheme
import io.github.ztfang.eye.util.LocaleHelper
import io.github.ztfang.eye.viewmodel.SubtitleManager

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var subtitleManager: SubtitleManager
    @Inject lateinit var historyRepository: io.github.ztfang.eye.domain.repository.HistoryRepository
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 获取外部跳转参数，支持从其他地方直接跳转到指定页面
        val navigateTo = intent.getStringExtra("navigate_to")
        setContent {
            EyeTheme {
                GradientBackground {
                    EyeOpenerApp(initialRoute = navigateTo, subtitleManager = subtitleManager, historyRepository = historyRepository)
                }
            }
        }
    }
}

/**
 * 导航屏幕定义，使用 sealed class 确保类型安全
 * 每个 Screen 包含路由路径、标题资源ID和图标
 */
sealed class Screen(val route: String, val title: Int, val icon: ImageVector) {
    object Subtitle : Screen("subtitle", R.string.tab_subtitle, Icons.Filled.Subtitles)     // 字幕主屏
    object Assistant : Screen("assistant", R.string.tab_assistant, Icons.Filled.Star)       // AI助手屏
    object Settings : Screen("settings", R.string.tab_settings, Icons.Filled.Settings)     // 设置屏
    object LocalModels : Screen("local_models", R.string.settings_row_local, Icons.Filled.Subtitles)    // 本地模型设置
    object ApiSettings : Screen("api_settings", R.string.settings_row_api, Icons.Filled.Settings)        // API设置
    object CloudTranslation : Screen("cloud_translation", R.string.settings_row_cloud, Icons.Filled.Language) // 云端翻译设置
    object Personalization : Screen("personalization", R.string.personalization_entry, Icons.Filled.Person) // 个性化设置
    object History : Screen("history", R.string.settings_row_history, Icons.Filled.CopyAll) // 历史记录
}

/**
 * 应用主入口 Composable，负责全局导航架构
 * @param initialRoute 初始路由，支持外部跳转
 * @param subtitleManager 字幕管理 ViewModel，用于控制悬浮字幕状态
 */
@Composable
fun EyeOpenerApp(
    initialRoute: String? = null,
    subtitleManager: SubtitleManager,
    historyRepository: io.github.ztfang.eye.domain.repository.HistoryRepository,
    settingsViewModel: io.github.ztfang.eye.viewmodel.SettingsViewModel = hiltViewModel()
) {
    // 创建导航控制器，管理应用内页面跳转
    val navController = rememberNavController()
    val context = LocalContext.current

    // 首次引导：全屏显示引导页，覆盖所有内容
    val showOnboarding by settingsViewModel.showOnboarding.collectAsState(initial = true)
    if (showOnboarding) {
        OnboardingScreen(
            onFinished = { settingsViewModel.setShowOnboarding(false) }
        )
        return
    }

    // 根据初始路由参数，在应用启动时跳转到指定页面
    LaunchedEffect(initialRoute) {
        when (initialRoute) {
            "personalization" -> navController.navigate(Screen.Personalization.route)
            "api_settings" -> navController.navigate(Screen.ApiSettings.route)
            "local_models" -> navController.navigate(Screen.LocalModels.route)
        }
    }

    // Scaffold 提供应用基本结构，包含底部导航栏
    Scaffold(
        bottomBar = {
            Column(
                modifier = Modifier.imePadding()
            ) {
                BottomNavigationBar(navController = navController)
            }
        },
        containerColor = Color.Transparent
    ) { innerPadding ->
        // 导航主机，定义所有页面路由和对应的 Composable
        NavHost(
            navController = navController,
            startDestination = Screen.Subtitle.route,  // 默认启动页为字幕主屏
            modifier = Modifier.padding(innerPadding)
        ) {
            // 三个主 Tab 页面
            composable(Screen.Subtitle.route) {
                SubtitleScreen(
                    subtitleManager = subtitleManager,
                    onNavigateToLocal = { navController.navigate(Screen.LocalModels.route) },
                    onNavigateToApi = { navController.navigate(Screen.ApiSettings.route) },
                    onNavigateToCloud = { navController.navigate(Screen.CloudTranslation.route) }
                )
            }
            composable(Screen.Assistant.route) { AssistantScreen() }
            composable(Screen.Settings.route) {
                val context = LocalContext.current
                SettingsScreen(
                    onLocalClick = { navController.navigate(Screen.LocalModels.route) },
                    onApiClick = { navController.navigate(Screen.ApiSettings.route) },
                    onCloudClick = { navController.navigate(Screen.CloudTranslation.route) },
                    onPersonalizationClick = { navController.navigate(Screen.Personalization.route) },
                    onHistoryClick = { navController.navigate(Screen.History.route) },
                    onFeedbackClick = {
                        val feedbackIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://v.wjx.cn/vm/Ort5ZBZ.aspx#"))
                        context.startActivity(feedbackIntent)
                    },
                    onShareClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TITLE, context.getString(R.string.app_name))
                            putExtra(
                                Intent.EXTRA_TEXT,
                                context.getString(R.string.share_intro, context.getString(R.string.app_name)) +
                                        "${context.getString(R.string.app_tagline)}\n" +
                                        "${context.getString(R.string.share_desc)}\n\n" +
                                        "${context.getString(R.string.share_github)}：https://github.com/zt-Fang/EyeOpener-Translator/releases\n" +
                                        "${context.getString(R.string.share_lanzou)}" +
                                        "${context.getString(R.string.share_link)}https://eyeopener.lanzoul.com/b01d72jymf\n" +
                                        "${context.getString(R.string.share_password)}7856"
                            )
                        }
                        val chooser = Intent.createChooser(shareIntent, context.getString(R.string.share_title))
                        context.startActivity(chooser)
                    }
                )
            }
            // 设置子页面，通过 popBackStack 返回上一级
            composable(Screen.LocalModels.route) {
                LocalModelsScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.ApiSettings.route) {
                ApiSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.CloudTranslation.route) {
                CloudTranslationSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.Personalization.route) {
                PersonalizationScreen(
                    onBack = { navController.popBackStack() },
                    subtitleManager = subtitleManager
                )
            }
            composable(Screen.History.route) {
                HistoryScreen(
                    historyRepository = historyRepository,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

/**
 * 底部导航栏组件，管理三个主 Tab（字幕、助手、设置）的切换
 * @param navController 导航控制器，用于执行页面跳转
 */
@Composable
fun BottomNavigationBar(navController: NavController) {
    // 底部导航只显示三个主页面
    val items = listOf(Screen.Subtitle, Screen.Assistant, Screen.Settings)
    // 获取当前导航栈顶条目，用于判断当前选中的页面
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    NavigationBar(containerColor = Color.Transparent) {
        items.forEach { screen ->
            val selected = currentRoute == screen.route
            NavigationBarItem(
                icon = {
                    Icon(
                        screen.icon,
                        contentDescription = stringResource(screen.title),
                        modifier = Modifier.size(Dimens.SpaceLg)
                    )
                },
                label = { Text(stringResource(screen.title)) },
                selected = selected,
                onClick = {
                    if (currentRoute != screen.route) {
                        // 导航到目标页面，配置动画和状态恢复
                        navController.navigate(screen.route) {
                            // 返回栈顶，保持导航栈简洁
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            // 防止重复创建相同页面实例
                            launchSingleTop = true
                            // 恢复之前保存的页面状态
                            restoreState = true
                        }
                    }
                }
            )
        }
    }
}

// ============================== 字幕主屏 ==============================


data class MlKitLanguage(
    val code: String,
    val displayName: String,
    val nativeName: String,
    val chineseName: String
)

object MlKitLanguages {
    val AFRIKAANS = MlKitLanguage("af", "Afrikaans", "Afrikaans", "南非语")
    val ALBANIAN = MlKitLanguage("sq", "Albanian", "Shqip", "阿尔巴尼亚语")
    val AMHARIC = MlKitLanguage("am", "Amharic", "አማርኛ", "阿姆哈拉语")
    val ARABIC = MlKitLanguage("ar", "Arabic", "العربية", "阿拉伯语")
    val ARMENIAN = MlKitLanguage("hy", "Armenian", "Հայերեն", "亚美尼亚语")
    val AZERBAIJANI = MlKitLanguage("az", "Azerbaijani", "Azərbaycanlı", "阿塞拜疆语")
    val BASQUE = MlKitLanguage("eu", "Basque", "Euskal", "巴斯克语")
    val BELARUSIAN = MlKitLanguage("be", "Belarusian", "Беларуская", "白俄罗斯语")
    val BENGALI = MlKitLanguage("bn", "Bengali", "বাংলা", "孟加拉语")
    val BOSNIAN = MlKitLanguage("bs", "Bosnian", "Bosanski", "波斯尼亚语")
    val BRETON = MlKitLanguage("br", "Breton", "Brezhoneg", "布列塔尼语")
    val BULGARIAN = MlKitLanguage("bg", "Bulgarian", "Български", "保加利亚语")
    val CATALAN = MlKitLanguage("ca", "Catalan", "Català", "加泰罗尼亚语")
    val CHINESE = MlKitLanguage("zh", "Chinese (Simplified)", "中文(简体)", "中文(简体)")
    val CROATIAN = MlKitLanguage("hr", "Croatian", "Hrvatski", "克罗地亚语")
    val CZECH = MlKitLanguage("cs", "Czech", "Čeština", "捷克语")
    val DANISH = MlKitLanguage("da", "Danish", "Dansk", "丹麦语")
    val DUTCH = MlKitLanguage("nl", "Dutch", "Nederlands", "荷兰语")
    val ENGLISH = MlKitLanguage("en", "English", "English", "英语")
    val ESTONIAN = MlKitLanguage("et", "Estonian", "Eesti", "爱沙尼亚语")
    val FINNISH = MlKitLanguage("fi", "Finnish", "Suomi", "芬兰语")
    val FRENCH = MlKitLanguage("fr", "French", "Français", "法语")
    val GALICIAN = MlKitLanguage("gl", "Galician", "Galego", "加利西亚语")
    val GEORGIAN = MlKitLanguage("ka", "Georgian", "ქართული", "格鲁吉亚语")
    val GERMAN = MlKitLanguage("de", "German", "Deutsch", "德语")
    val GREEK = MlKitLanguage("el", "Greek", "Ελληνικά", "希腊语")
    val GUJARATI = MlKitLanguage("gu", "Gujarati", "ગુજરાતી", "古吉拉特语")
    val HAITIAN_CREOLE = MlKitLanguage("ht", "Haitian Creole", "Kreyòl Ayisyen", "海地克里奥尔语")
    val HEBREW = MlKitLanguage("he", "Hebrew", "עברית", "希伯来语")
    val HINDI = MlKitLanguage("hi", "Hindi", "हिन्दी", "印地语")
    val HUNGARIAN = MlKitLanguage("hu", "Hungarian", "Magyar", "匈牙利语")
    val ICELANDIC = MlKitLanguage("is", "Icelandic", "Íslenska", "冰岛语")
    val INDONESIAN = MlKitLanguage("id", "Indonesian", "Indonesia", "印度尼西亚语")
    val IRISH = MlKitLanguage("ga", "Irish", "Gaeilge", "爱尔兰语")
    val ITALIAN = MlKitLanguage("it", "Italian", "Italiano", "意大利语")
    val JAPANESE = MlKitLanguage("ja", "Japanese", "日本語", "日语")
    val KANNADA = MlKitLanguage("kn", "Kannada", "ಕನ್ನಡ", "卡纳达语")
    val KAZAKH = MlKitLanguage("kk", "Kazakh", "Қазақша", "哈萨克语")
    val KHMER = MlKitLanguage("km", "Khmer", "ភាសាខ្មែរ", "高棉语")
    val KOREAN = MlKitLanguage("ko", "Korean", "한국어", "韩语")
    val KYRGYZ = MlKitLanguage("ky", "Kyrgyz", "Кыргызча", "吉尔吉斯语")
    val LATVIAN = MlKitLanguage("lv", "Latvian", "Latviešu", "拉脱维亚语")
    val LITHUANIAN = MlKitLanguage("lt", "Lithuanian", "Lietuvių", "立陶宛语")
    val MACEDONIAN = MlKitLanguage("mk", "Macedonian", "Македонски", "马其顿语")
    val MALAY = MlKitLanguage("ms", "Malay", "Bahasa Melayu", "马来语")
    val MALTESE = MlKitLanguage("mt", "Maltese", "Malti", "马耳他语")
    val MARATHI = MlKitLanguage("mr", "Marathi", "मराठी", "马拉地语")
    val NORWEGIAN = MlKitLanguage("no", "Norwegian", "Norsk", "挪威语")
    val PERSIAN = MlKitLanguage("fa", "Persian", "فارسی", "波斯语")
    val POLISH = MlKitLanguage("pl", "Polish", "Polski", "波兰语")
    val PORTUGUESE = MlKitLanguage("pt", "Portuguese", "Português", "葡萄牙语")
    val PUNJABI = MlKitLanguage("pa", "Punjabi", "ਪੰਜਾਬੀ", "旁遮普语")
    val ROMANIAN = MlKitLanguage("ro", "Romanian", "Română", "罗马尼亚语")
    val RUSSIAN = MlKitLanguage("ru", "Russian", "Русский", "俄语")
    val SLOVAK = MlKitLanguage("sk", "Slovak", "Slovenčina", "斯洛伐克语")
    val SPANISH = MlKitLanguage("es", "Spanish", "Español", "西班牙语")
    val SWAHILI = MlKitLanguage("sw", "Swahili", "Kiswahili", "斯瓦希里语")
    val SWEDISH = MlKitLanguage("sv", "Swedish", "Svenska", "瑞典语")
    val TAGALOG = MlKitLanguage("tl", "Filipino", "Filipino", "菲律宾语")
    val TAMIL = MlKitLanguage("ta", "Tamil", "தமிழ்", "泰米尔语")
    val TELUGU = MlKitLanguage("te", "Telugu", "తెలుగు", "泰卢固语")
    val THAI = MlKitLanguage("th", "Thai", "ไทย", "泰语")
    val TURKISH = MlKitLanguage("tr", "Turkish", "Türkçe", "土耳其语")
    val UKRAINIAN = MlKitLanguage("uk", "Ukrainian", "Українська", "乌克兰语")
    val URDU = MlKitLanguage("ur", "Urdu", "اردو", "乌尔都语")
    val UZBEK = MlKitLanguage("uz", "Uzbek", "O'zbekcha", "乌兹别克语")
    val VIETNAMESE = MlKitLanguage("vi", "Vietnamese", "Tiếng Việt", "越南语")
    val WELSH = MlKitLanguage("cy", "Welsh", "Cymraeg", "威尔士语")

    // === Vosk ASR 额外支持的语言（与 VoskLanguage 枚举对齐） ===
    val INDIA_ENGLISH = MlKitLanguage("en-in", "English (India)", "English (India)", "印度英语")
    val ESPERANTO = MlKitLanguage("eo", "Esperanto", "Esperanto", "世界语")
    val TAJIK = MlKitLanguage("tg", "Tajik", "Тоҷикӣ", "塔吉克语")
    val FILIPINO = MlKitLanguage("fil", "Filipino", "Filipino", "菲律宾语")

    // === Nemotron 3.5 新增支持的语言（Vosk 不支持） ===
    // 这些语言已在 MlKitLanguage 上面定义(BULGARIAN/CROATIAN/DANISH/ESTONIAN/FINNISH/HUNGARIAN/ROMANIAN/SLOVAK)
    // 仅新增 NORWEGIAN_BOKMAL(代码 "nb",区别于 MlKit 的 "no" 挪威语)
    val NORWEGIAN_BOKMAL = MlKitLanguage("nb", "Norwegian (Bokmål)", "Norsk (Bokmål)", "挪威语(博克马尔)")

    /**
     * 源语言列表：Vosk + Nemotron-only 语言全集。
     *
     * - 前 32 种：Vosk 支持的语言
     * - 后 9 种：仅 Nemotron 3.5 支持的语言（无 Sherpa-ONNX/Vosk 模型时不可识别）
     *
     * ASR 引擎由源语言自动决定（resolveAsrEngine），不再依赖翻译模式。
     */
    val VOSK_SOURCE_LANGUAGES: List<MlKitLanguage> = listOf(
        // Vosk 支持的语言（顺序与 VoskLanguage 枚举一致）
        CHINESE, ENGLISH, INDIA_ENGLISH, GERMAN, FRENCH, SPANISH, PORTUGUESE,
        RUSSIAN, TURKISH, VIETNAMESE, ITALIAN, DUTCH, CATALAN, ARABIC, GREEK,
        PERSIAN, FILIPINO, UKRAINIAN, KAZAKH, SWEDISH, JAPANESE, ESPERANTO,
        HINDI, CZECH, POLISH, UZBEK, KOREAN, TAJIK, KYRGYZ, GEORGIAN,
        BRETON, GUJARATI, TELUGU,
        // 孟加拉语：BN Vosk 模型(若已下载), 否则不可识别
        BENGALI,
        // 仅 Nemotron 3.5 支持的语言（Vosk 不支持）
        DANISH, NORWEGIAN_BOKMAL, BULGARIAN, FINNISH, CROATIAN, SLOVAK,
        HUNGARIAN, ROMANIAN, ESTONIAN
    )

    /**
     * 目标语言列表：LOCAL/CLOUD/AI 引擎共用的统一目标语言集合。
     *
     * - 包含高棉语（km）等 ML Kit 不支持但云端/AI 支持的语种
     * - ML Kit / DeepL / Papago 遇到不支持的语种时 supportsLanguage 返回 false，由 UseCase 静默跳过
     * - 百度 / Azure / AI 支持几乎所有语种
     */
    val MLKIT_TARGET_LANGUAGES: List<MlKitLanguage> = listOf(
        AFRIKAANS, ALBANIAN, AMHARIC, ARABIC, ARMENIAN, AZERBAIJANI,
        BASQUE, BELARUSIAN, BENGALI, BOSNIAN, BULGARIAN, CATALAN,
        CHINESE, CROATIAN, CZECH, DANISH, DUTCH, ENGLISH,
        ESTONIAN, FINNISH, FRENCH, GALICIAN, GEORGIAN, GERMAN, GREEK,
        GUJARATI, HAITIAN_CREOLE, HEBREW, HINDI, HUNGARIAN, ICELANDIC,
        INDONESIAN, IRISH, ITALIAN, JAPANESE, KANNADA, KAZAKH, KHMER, KOREAN,
        KYRGYZ, LATVIAN, LITHUANIAN, MACEDONIAN, MALAY, MALTESE,
        MARATHI, NORWEGIAN, PERSIAN, POLISH, PORTUGUESE, PUNJABI,
        ROMANIAN, RUSSIAN, SLOVAK, SPANISH, SWAHILI, SWEDISH, TAGALOG,
        TAMIL, TELUGU, THAI, TURKISH, UKRAINIAN, URDU, UZBEK,
        VIETNAMESE, WELSH
    )
}

@Composable
fun LanguagePickerDialog(
    title: String,
    selected: MlKitLanguage,
    languages: List<MlKitLanguage>,
    onSelect: (MlKitLanguage) -> Unit,
    onDismiss: () -> Unit
) {
    val appLocales = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
    val isChineseLocale = appLocales.isEmpty || appLocales[0]?.language == "zh"
    // 搜索关键词（顶部固定搜索框，支持模糊搜索 nativeName / english displayName / chineseName / language code）
    var keyword by remember { mutableStateOf("") }
    val filtered = remember(keyword, languages) {
        val k = keyword.trim().lowercase()
        if (k.isEmpty()) languages else languages.filter { l ->
            l.code.lowercase().contains(k) ||
                    l.displayName.lowercase().contains(k) ||
                    l.nativeName.lowercase().contains(k) ||
                    l.chineseName.lowercase().contains(k)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 顶部固定搜索框（根据project memory原设计：固定顶部搜索框+模糊搜索）
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    modifier = Modifier.fillMaxWidth().padding(bottom = Dimens.SpaceSm),
                    placeholder = {
                        Text(
                            text = stringResource(R.string.language_search_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(Dimens.SpaceMd)
                        )
                    },
                    trailingIcon = {
                        if (keyword.isNotEmpty()) {
                            IconButton(onClick = { keyword = "" }) {
                                Icon(
                                    imageVector = Icons.Filled.Clear,
                                    contentDescription = stringResource(R.string.common_cancel),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(Dimens.SpaceMd)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(Dimens.CornerMd),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(400.dp),
                    verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXxs)
                ) {
                    items(filtered, key = { it.code }) { lang ->
                        val isSelected = lang.code == selected.code
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Dimens.CornerMd))
                                .clickable { onSelect(lang) }
                                .padding(horizontal = Dimens.SpaceSm, vertical = Dimens.SpaceSm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 仅显示语言文字名称（不显示语言代码徽标），勾选图标在右侧
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isChineseLocale) lang.chineseName else lang.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = if (isChineseLocale) "${lang.nativeName} · ${lang.displayName}" else lang.nativeName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LocalContentColor.current.copy(alpha = 0.6f)
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(Dimens.SpaceLg)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

/**
 * 字幕主屏，应用核心功能页面
 * 
 * 主要功能：
 * - 悬浮字幕开关控制
 * - 翻译引擎选择（极速/高质量/AI）
 * - 源语言和目标语言选择
 * - 权限处理（悬浮窗权限、录音权限）
 * - ML Kit 离线模型下载管理
 * 
 * @param subtitleManager 字幕管理 ViewModel，负责控制悬浮字幕状态和模型加载
 */
@Composable
fun SubtitleScreen(
    subtitleManager: SubtitleManager,
    onNavigateToLocal: () -> Unit = {},
    onNavigateToApi: () -> Unit = {},
    onNavigateToCloud: () -> Unit = {},
    settingsViewModel: io.github.ztfang.eye.viewmodel.SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    // 悬浮字幕开关状态
    var isOverlayOn by remember { mutableStateOf(false) }
    // 从 SubtitleManager 读取字幕状态（翻译引擎、源语言、目标语言）
    val subtitleState by subtitleManager.subtitleState.collectAsState()
    val selectedEngine = subtitleState.engine
    // 根据语言代码查找对应的 MlKitLanguage
    val sourceLang = remember(subtitleState.sourceLanguage) {
        MlKitLanguages.VOSK_SOURCE_LANGUAGES.find { it.code == subtitleState.sourceLanguage } ?: MlKitLanguages.ENGLISH
    }
    val targetLang = remember(subtitleState.targetLanguage) {
        MlKitLanguages.MLKIT_TARGET_LANGUAGES.find { it.code == subtitleState.targetLanguage } ?: MlKitLanguages.CHINESE
    }
    // 语言选择器弹窗状态
    var showSourcePicker by remember { mutableStateOf(false) }
    var showTargetPicker by remember { mutableStateOf(false) }
    // ASR 模型下载请求（源语言切换时触发）
    val asrDownloadRequest by subtitleManager.asrDownloadRequest.collectAsState()
    // LLM 配置是否就绪（AI 模式点击时检查）
    val isLlmReady by subtitleManager.isLlmConfigReady.collectAsState()
    // AI 模式未配置提示弹窗
    var showLlmConfigAlert by remember { mutableStateOf(false) }
    // 云端翻译 API Key 是否已配置（CLOUD 模式点击时检查）
    val cloudApiKey by settingsViewModel.cloudTranslationApiKey.collectAsState(initial = "")
    var showCloudConfigAlert by remember { mutableStateOf(false) }

    // 与 ViewModel 同步悬浮字幕状态，防止 Service 被系统杀死后界面状态不一致
    val active by subtitleManager.isOverlayActive.collectAsState()
    val runtimeError by subtitleManager.runtimeError.collectAsState()
    // 音频输入源（0=麦克风, 1=应用内声音）
    val audioSource by subtitleManager.audioSource.collectAsState()
    LaunchedEffect(active) {
        if (!active && isOverlayOn) {
            isOverlayOn = false
            // 如果 Service 被外部杀死，确保本地也停止 Service
            context.stopService(Intent(context, FloatingSubtitleService::class.java))
        }
    }
    // 监听运行时错误，弹出提示并清除错误状态
    LaunchedEffect(runtimeError) {
        runtimeError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            subtitleManager.clearRuntimeError()
        }
    }


    /**
     * 批量请求悬浮字幕所需的所有运行时权限（主要是录音权限）
     * 如果权限已全部授予，直接启动 Service；否则在用户拒绝后引导到系统设置
     */
    val overlayPermissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val micGranted = results[Manifest.permission.RECORD_AUDIO] == true
        if (micGranted) {
            // 权限获取成功，开启悬浮字幕
            isOverlayOn = true
            subtitleManager.setOverlayActive(true)
            context.startService(Intent(context, FloatingSubtitleService::class.java))
        } else {
            // 用户拒绝权限，引导到应用设置页面手动授权
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
            context.startActivity(intent)
            Toast.makeText(
                context,
                context.getString(R.string.tip_mic_permission_required),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * 助手界面麦克风权限请求启动器（单独申请 RECORD_AUDIO）
     * 用户从助手界面长按麦克风按钮时，如果没有权限则触发此请求
     */
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(context, context.getString(R.string.tip_mic_permission_granted), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, context.getString(R.string.tip_mic_permission_denied), Toast.LENGTH_LONG).show()
        }
    }


    /**
     * 启动悬浮字幕 Service（权限与音频源均已就绪）
     */
    fun startOverlayService() {
        isOverlayOn = true
        subtitleManager.setOverlayActive(true)
        context.startService(Intent(context, FloatingSubtitleService::class.java))
        subtitleManager.ensureModelsLoaded()
    }

    /**
     * MediaProjection 授权启动器
     * 用户授权后保存 token 到 SubtitleManager，再启动悬浮字幕服务。
     * 注意：不直接调用 getMediaProjection()，因为 Android 14+ 要求必须在前台服务中调用，
     *       否则抛出 SecurityException。Service 成为 FGS 后会自行创建实例。
     * 应用内声音模式下：授权成功才启动 Service，拒绝则不开启悬浮窗
     */
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // MediaProjection 授权回调诊断：确认回调是否触发（vivo 杀进程会导致回调丢失）
        Log.i("MediaProjection", "授权回调触发: resultCode=${result.resultCode}, hasData=${result.data != null}")
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            // 授权成功，保存 token 并启动 Service
            Log.i("MediaProjection", "授权成功: resultCode=${result.resultCode}")
            subtitleManager.saveMediaProjectionToken(result.resultCode, result.data)
            Log.i("MediaProjection", "saveMediaProjectionToken 完成, hasToken=${subtitleManager.hasMediaProjectionToken()}")
            startOverlayService()
        } else {
            // 用户拒绝授权，不开启悬浮窗，不降级到麦克风
            Log.i("MediaProjection", "用户拒绝授权，取消开启悬浮窗")
            isOverlayOn = false
            subtitleManager.setOverlayActive(false)
            Toast.makeText(context, context.getString(R.string.tip_screen_record_denied), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 发起 MediaProjection 授权请求（开启悬浮字幕时拦截授权）。
     * 授权成功后由 mediaProjectionLauncher 回调启动 Service。
     * 系统不支持时也不降级，直接提示无法开启。
     */
    fun requestMediaProjectionForOverlay() {
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as? MediaProjectionManager
        if (projectionManager == null) {
            // 系统不支持 MediaProjection，不降级到麦克风，直接拒绝开启
            Log.i("MediaProjection", "系统不支持 MediaProjection，取消开启")
            isOverlayOn = false
            subtitleManager.setOverlayActive(false)
            Toast.makeText(context, context.getString(R.string.tip_screen_record_unsupported), Toast.LENGTH_LONG).show()
            return
        }
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    /**
     * 检查麦克风权限并启动悬浮字幕服务
     * 权限顺序：悬浮窗 → 麦克风 → 音频源（应用内声音时需 MediaProjection 授权）
     * 如果缺少权限，先显示权限说明再发起请求
     * 应用内声音模式：必须先授权屏幕录制才能开启，未授权则拒绝开启，不降级到麦克风
     */
    fun checkMicAndStart(ctx: Context) {
        val missing = PermissionHelper.missingPermissions(ctx)
        if (missing.isEmpty()) {
            // 麦克风权限齐全，检查音频输入源
            val needMediaProjection = subtitleManager.audioSource.value == 1 &&
                !subtitleManager.hasMediaProjectionToken()
            if (needMediaProjection) {
                // 应用内声音模式但未授权 MediaProjection
                // 先请求授权，授权成功后再启动 Service；拒绝则不开启
                Log.i("Overlay", "audioSource=1 且无 MediaProjection token，先请求屏幕录制授权")
                requestMediaProjectionForOverlay()
            } else {
                // 权限齐全且音频源就绪（麦克风模式 或 应用内声音已授权），直接启动
                startOverlayService()
            }
        } else {
            // 显示权限说明，让用户理解为什么需要这些权限
            val rationale = missing.joinToString("\n") { PermissionHelper.rationaleFor(ctx, it) }
            Toast.makeText(ctx, rationale, Toast.LENGTH_LONG).show()
            overlayPermissionsLauncher.launch(missing.toTypedArray())
        }
    }

    /**
     * 悬浮窗权限请求启动器
     * 用户从系统设置返回后，检查权限是否已授予
     */
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(context)) {
            // 悬浮窗权限已授予，继续检查麦克风权限
            checkMicAndStart(context)
        }
    }

    /**
     * 悬浮字幕开关逻辑
     * 开启时：先检查悬浮窗权限 → 再检查录音权限 → 启动 Service
     * 关闭时：停止 Service 并更新状态
     */
    fun toggleOverlay(enable: Boolean) {
        if (enable) {
            // 开启流程：先检查悬浮窗权限
            if (!Settings.canDrawOverlays(context)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                overlayPermissionLauncher.launch(intent)
            } else {
                // 悬浮窗权限已存在，检查麦克风权限
                checkMicAndStart(context)
            }
        } else {
            // 关闭流程：更新状态并停止 Service
            isOverlayOn = false
            subtitleManager.setOverlayActive(false)
            context.stopService(Intent(context, FloatingSubtitleService::class.java))
        }
    }

    // UI 布局：顶部标题栏 + 悬浮字幕开关 + 引擎选择 + 语言切换
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = Dimens.ScreenPaddingTop),
            verticalArrangement = Arrangement.spacedBy(Dimens.SectionGap),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 顶部标题栏（右上角：问号图标，点击重新显示引导页）
            item {
                TopAppBar(
                    rightIcon = Icons.Filled.HelpOutline,
                    onRightClick = {
                        settingsViewModel.setShowOnboarding(true)
                    }
                )
            }

            // 悬浮字幕开关卡片
            item {
                OverlayToggleCard(
                    running = isOverlayOn,
                    onToggle = { toggleOverlay(it) },
                    modifier = Modifier.padding(horizontal = Dimens.ScreenPaddingH)
                )
            }

            // 翻译引擎选择区域
            item {
                EngineSection(
                    selected = selectedEngine,
                    onSelect = { engine ->
                        // AI 引擎：未配置 API 时弹提示框，引导去设置页面
                        if (engine == TranslationEngine.AI && !isLlmReady) {
                            showLlmConfigAlert = true
                            return@EngineSection
                        }
                        // 云端翻译：未配置 API Key 时弹提示框，引导去云端翻译设置
                        if (engine == TranslationEngine.CLOUD && cloudApiKey.isBlank()) {
                            showCloudConfigAlert = true
                            return@EngineSection
                        }
                        // 更新到 SettingsRepository（DataStore），作为唯一数据源
                        subtitleManager.updateTranslationEngine(engine)
                        // 主动触发模型准备
                        subtitleManager.ensureModelsLoaded()
                    },
                    modifier = Modifier.padding(horizontal = Dimens.ScreenPaddingH)
                )
            }

            // 语言切换器（源语言和目标语言）
            item {
                Column(
                    modifier = Modifier.padding(horizontal = Dimens.ScreenPaddingH)
                ) {
                    val appLocales = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
                    val isChineseLocale = appLocales.isEmpty || appLocales[0]?.language == "zh"
                    LanguageSwitcher(
                        sourceLabel = stringResource(R.string.language_source_label),
                        sourceSubtitle = if (isChineseLocale) sourceLang.chineseName else sourceLang.displayName,
                        targetLabel = stringResource(R.string.language_target_label),
                        targetSubtitle = if (isChineseLocale) targetLang.chineseName else targetLang.displayName,
                        onSourceClick = { showSourcePicker = true },
                        onTargetClick = { showTargetPicker = true },
                        onSwapClick = {
                            val tgt = subtitleState.targetLanguage
                            val canSwap = MlKitLanguages.VOSK_SOURCE_LANGUAGES.any { it.code == tgt }
                            if (!canSwap) {
                                val langName = if (isChineseLocale) targetLang.chineseName else targetLang.displayName
                                Toast.makeText(
                                    context,
                                    "Target language ($langName) does not support speech recognition. Cannot swap.",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@LanguageSwitcher
                            }
                            val src = subtitleState.sourceLanguage
                            subtitleManager.updateSourceLanguage(tgt)
                            subtitleManager.updateTargetLanguage(src)
                        }
                    )
                    // 语言选择区下方温馨提示框
                    Spacer(modifier = Modifier.height(Dimens.SpaceSm))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        ),
                        border = BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.common_tips_title),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(4.dp)
                                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                            .clip(CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.language_tip_network),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(4.dp)
                                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                            .clip(CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.language_tip_fast_mode),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(4.dp)
                                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                            .clip(CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.language_tip_audio_permission),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(Dimens.SpaceMd)) }
        }
    }

    // 源语言选择弹窗
    if (showSourcePicker) {
        LanguagePickerDialog(
            title = stringResource(R.string.language_source_label),
            selected = sourceLang,
            languages = MlKitLanguages.VOSK_SOURCE_LANGUAGES,
            onSelect = {
                // 更新到 SettingsRepository（DataStore），通过 Flow 自动同步到 UI
                subtitleManager.updateSourceLanguage(it.code)
                // 源语言切换：触发 ASR 模型检查（未下载则弹窗提示用户前往下载）
                val locales = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
                val isZh = locales.isEmpty || locales[0]?.language == "zh"
                subtitleManager.checkAsrModel(it.code, if (isZh) it.chineseName else it.displayName)
                showSourcePicker = false
            },
            onDismiss = { showSourcePicker = false }
        )
    }
    // 目标语言选择弹窗
    if (showTargetPicker) {
        LanguagePickerDialog(
            title = stringResource(R.string.language_target_label),
            selected = targetLang,
            languages = MlKitLanguages.MLKIT_TARGET_LANGUAGES,
            onSelect = {
                // 更新到 SettingsRepository（DataStore），通过 Flow 自动同步到 UI
                subtitleManager.updateTargetLanguage(it.code)
                showTargetPicker = false
            },
            onDismiss = { showTargetPicker = false }
        )
    }

    // AI 引擎未配置 API 提醒对话框 — 点击跳转 API 设置页
    if (showLlmConfigAlert) {
        AlertDialog(
            onDismissRequest = { showLlmConfigAlert = false },
            title = { Text(text = stringResource(R.string.api_not_configured_title)) },
            text = { Text(text = stringResource(R.string.api_not_configured_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showLlmConfigAlert = false
                    onNavigateToApi()
                }) {
                    Text(text = stringResource(R.string.api_go_config))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLlmConfigAlert = false }) {
                    Text(text = stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // 云端翻译未配置提醒对话框 — 点击跳转云端翻译设置页
    if (showCloudConfigAlert) {
        AlertDialog(
            onDismissRequest = { showCloudConfigAlert = false },
            title = { Text(text = stringResource(R.string.cloud_not_configured_title)) },
            text = { Text(text = stringResource(R.string.cloud_not_configured_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showCloudConfigAlert = false
                    onNavigateToCloud()
                }) {
                    Text(text = stringResource(R.string.cloud_go_config))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloudConfigAlert = false }) {
                    Text(text = stringResource(R.string.cloud_cancel))
                }
            }
        )
    }

    // ASR 模型下载提示弹窗 — 源语言切换时模型未下载则弹出
    // 统一引导用户前往模型下载界面（不显示具体模型名）
    asrDownloadRequest?.let { req ->
        AlertDialog(
            onDismissRequest = { subtitleManager.dismissAsrDownload() },
            title = { Text(text = stringResource(R.string.asr_model_not_downloaded_title)) },
            text = {
                Column {
                    Text(text = stringResource(R.string.asr_model_not_downloaded_msg))
                }
            },
            dismissButton = {
                TextButton(onClick = { subtitleManager.dismissAsrDownload() }) {
                    Text(text = stringResource(R.string.cloud_cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    subtitleManager.dismissAsrDownload()
                    onNavigateToLocal()
                }) {
                    Text(text = stringResource(R.string.asr_model_go_download))
                }
            }
        )
    }
}

/**
 * 翻译引擎选择区域，展示三种引擎
 * - 本地翻译（ML Kit）：基于 Google ML Kit 离线模型，响应最快
 * - 云端翻译：Papago / 百度 / DeepL / Azure（在设置中选择具体服务商）
 * - AI 翻译：LLM API，上下文理解最强
 *
 * 注：ASR 引擎选择与翻译引擎解耦，仅由源语言决定。
 */
@Composable
private fun EngineSection(
    selected: TranslationEngine,
    onSelect: (TranslationEngine) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // 区域标题
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.engine_section_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        Spacer(modifier = Modifier.height(Dimens.SpaceMd))
        // 三个引擎卡片横向排列
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
            modifier = Modifier.fillMaxWidth()
        ) {
            // 本地翻译
            EngineCard(
                title = stringResource(R.string.engine_local_title),
                subtitle = stringResource(R.string.engine_local_subtitle),
                icon = Icons.Filled.FlashOn,
                selected = selected == TranslationEngine.LOCAL,
                onClick = { onSelect(TranslationEngine.LOCAL) },
                accent = EngineAccent.Blue,
                modifier = Modifier.weight(1f)
            )
            // 云端翻译
            EngineCard(
                title = stringResource(R.string.engine_cloud_title),
                subtitle = stringResource(R.string.engine_cloud_subtitle),
                icon = Icons.Filled.Cloud,
                selected = selected == TranslationEngine.CLOUD,
                onClick = { onSelect(TranslationEngine.CLOUD) },
                accent = EngineAccent.Orange,
                modifier = Modifier.weight(1f)
            )
            // AI 翻译
            EngineCard(
                title = stringResource(R.string.engine_ai_title),
                subtitle = stringResource(R.string.engine_ai_subtitle),
                icon = Icons.Filled.AutoAwesome,
                selected = selected == TranslationEngine.AI,
                onClick = { onSelect(TranslationEngine.AI) },
                accent = EngineAccent.Purple,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ============================== 助手屏 ==============================

/**
 * 聊天消息数据类
 * @param text 消息内容
 * @param isFromUser 是否来自用户
 * @param timestamp 时间戳
 */
data class ChatMessage(
    val text: String,
    val isFromUser: Boolean,
    val timestamp: String = "10:30"
)

/**
 * AI 助手页面，展示聊天界面和示例对话
 *
 * 功能：
 * - 接入大模型纯对话（无系统提示词，多轮上下文）
 * - 输入框跟随软键盘上移（imePadding）
 * - 麦克风按钮长按触发安卓自带 SpeechRecognizer，松开把识别结果填入输入框
 * - 识别期间输入框显示声波动画
 */
@Composable
fun AssistantScreen() {
    val viewModel: io.github.ztfang.eye.viewmodel.AssistantViewModel = hiltViewModel()
    val subtitleManager = viewModel.subtitleManager
    val context = LocalContext.current
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var inputText by remember { mutableStateOf("") }
    // 麦克风识别状态：true=正在听写
    var isListening by remember { mutableStateOf(false) }
    // 协程作用域
    val coroutineScope = rememberCoroutineScope()

    // 助手界面麦克风权限请求启动器
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(context, context.getString(R.string.tip_mic_permission_granted), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, context.getString(R.string.tip_mic_permission_denied), Toast.LENGTH_LONG).show()
        }
    }

    // 自动滚动到底部
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // 顶部栏（含清除按钮）
        AssistantTopBar(onClearClick = { viewModel.clearMessages() })

        // 消息列表
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            reverseLayout = false,
            verticalArrangement = Arrangement.spacedBy(Dimens.MessageSpacing)
        ) {
            items(messages) { msg ->
                ChatBubble(
                    text = msg.text,
                    timestamp = msg.timestamp,
                    isFromAi = !msg.isFromUser,
                    isRead = msg.isFromUser
                )
            }
            // 加载中提示
            if (isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Dimens.SpaceMd),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // 订阅语音输入实时文本（Vosk 本地识别）
        val voiceText by subtitleManager.voiceInputText.collectAsState()
        // 输入框显示逻辑：正在识别时显示语音识别文本，否则显示用户输入
        val displayText = if (isListening) voiceText else inputText

        MessageInputBar(
            text = displayText,
            onTextChange = {
                // 用户手动编辑时，切换到手动输入模式
                isListening = false
                inputText = it
            },
            onSend = {
                val textToSend = if (isListening) voiceText else inputText
                if (textToSend.isNotBlank()) {
                    viewModel.sendUserMessage(textToSend)
                    inputText = ""
                    // 停止语音识别并清空
                    if (isListening) {
                        subtitleManager.stopVoiceInput()
                        isListening = false
                    }
                }
            },
            isListening = isListening,
            onMicClick = {
                if (isListening) {
                    // 停止识别，直接把识别结果填入输入框（不润色）
                    val rawResult = subtitleManager.stopVoiceInput()
                    isListening = false
                    if (rawResult.isNotBlank()) {
                        val currentText = inputText
                        inputText = if (currentText.isBlank()) rawResult
                                    else "$currentText $rawResult"
                    }
                } else {
                    val hasPerm = androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.RECORD_AUDIO
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (!hasPerm) {
                        Toast.makeText(context, context.getString(R.string.tip_mic_permission_please), Toast.LENGTH_SHORT).show()
                        micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    } else {
                        isListening = true
                        subtitleManager.startVoiceInput()
                    }
                }
            },
            modifier = Modifier
        )
    }
}

// ============================== 设置屏 ==============================

/**
 * 设置页面，包含多个设置项：
 * - 界面语言
 * - 本地模型管理
 * - API 设置
 * - 个性化设置
 * - 分享、反馈、检查更新
 */
@Composable
fun SettingsScreen(
    onLocalClick: () -> Unit = {},      // 点击本地模型设置
    onApiClick: () -> Unit = {},        // 点击 API 设置
    onCloudClick: () -> Unit = {},      // 点击云端翻译设置
    onPersonalizationClick: () -> Unit = {},  // 点击个性化设置
    onHistoryClick: () -> Unit = {},    // 点击历史记录
    onFeedbackClick: () -> Unit = {},   // 点击意见反馈
    onShareClick: () -> Unit = {},      // 点击分享给朋友
    settingsViewModel: io.github.ztfang.eye.viewmodel.SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // 检查更新状态：null=未检查, "checking"=检查中, "latest"=已最新, "new=版本号"=有新版, "error"=失败
    var updateState by remember { mutableStateOf<String?>(null) }
    var updateUrl by remember { mutableStateOf<String?>(null) }
    var latestVersion by remember { mutableStateOf<String?>(null) }
    // Release 更新说明（GitHub release body）
    var releaseNotes by remember { mutableStateOf<String?>(null) }
    // 更新弹窗是否显示
    var showUpdateDialog by remember { mutableStateOf(false) }
    // 下载状态：null=未下载, "downloading"=下载中(进度%), "downloaded"=已下载待安装, "dl_error"=下载失败
    var downloadState by remember { mutableStateOf<String?>(null) }
    var downloadId by remember { mutableStateOf<Long?>(null) }
    // 界面语言选择弹窗
    var showLanguagePicker by remember { mutableStateOf(false) }
    val currentLanguage by settingsViewModel.interfaceLanguage.collectAsState(initial = "zh")

    /**
     * 语义化版本比较
     * @return remote 比 current 新返回 true
     */
    fun isNewerVersion(current: String, remote: String): Boolean {
        val cur = current.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val rem = remote.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(cur.size, rem.size)
        for (i in 0 until maxLen) {
            val c = cur.getOrElse(i) { 0 }
            val r = rem.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    /** 异步检查 GitHub 最新 release */
    fun doCheckUpdate() {
        updateState = "checking"
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val url = java.net.URL("https://api.github.com/repos/zt-Fang/EyeOpener-Translator/releases/latest")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val json = org.json.JSONObject(response)
                val latest = json.optString("tag_name", json.optString("name", ""))
                // 提取 release 更新说明（body 字段）
                val notes = json.optString("body", "").ifBlank { context.getString(R.string.update_no_notes) }
                val apkAsset = json.getJSONArray("assets").let { arr ->
                    (0 until arr.length()).firstNotNullOfOrNull { i ->
                        val obj = arr.getJSONObject(i)
                        if (obj.getString("name").endsWith(".apk")) obj.getString("browser_download_url") else null
                    }
                } ?: json.getString("html_url")
                // 从 BuildConfig 读取当前版本号，替换硬编码
                val current = BuildConfig.VERSION_NAME
                if (isNewerVersion(current, latest)) {
                    updateState = "new=$latest"
                    latestVersion = latest
                    releaseNotes = notes
                    updateUrl = apkAsset
                    // 有新版本 → 弹窗询问
                    showUpdateDialog = true
                } else {
                    updateState = "latest"
                    // 已是最新 → Toast 提示
                    coroutineScope.launch(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.update_already_latest), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                updateState = "error"
                coroutineScope.launch(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.update_check_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 应用内下载 APK，完成后调起安装 */
    fun startDownloadApk() {
        val apkUrl = updateUrl ?: return
        downloadState = "downloading=0"
        // 在 IO 线程执行下载和进度查询
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val request = DownloadManager.Request(android.net.Uri.parse(apkUrl)).apply {
                    setTitle(context.getString(R.string.update_notification_title))
                    setDescription(context.getString(R.string.update_notification_desc))
                    // 下载到应用专属目录，无需存储权限
                    setDestinationInExternalFilesDir(
                        context, Environment.DIRECTORY_DOWNLOADS, "EyeOpener-update.apk"
                    )
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    setAllowedOverMetered(true)
                    setAllowedOverRoaming(true)
                }
                val id = dm.enqueue(request)
                downloadId = id

                // 轮询查询下载进度
                var lastProgress = -1
                while (true) {
                    val query = DownloadManager.Query().setFilterById(id)
                    val cursor = dm.query(query)
                    if (cursor != null && cursor.moveToFirst()) {
                        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        cursor.close()

                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                downloadState = "downloaded"
                                break
                            }
                            DownloadManager.STATUS_FAILED -> {
                                downloadState = "dl_error"
                                break
                            }
                            DownloadManager.STATUS_RUNNING -> {
                                val progress = if (total > 0) (downloaded * 100 / total).toInt() else 0
                                if (progress != lastProgress) {
                                    downloadState = "downloading=$progress"
                                    lastProgress = progress
                                }
                            }
                        }
                    } else {
                        cursor?.close()
                    }
                    delay(500) // 每 500ms 查询一次进度
                }
            } catch (e: Exception) {
                downloadState = "dl_error"
            }
        }
    }

    /** 调起系统安装器安装已下载的 APK */
    fun installApk() {
        try {
            val apkFile = java.io.File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "EyeOpener-update.apk"
            )
            if (!apkFile.exists()) {
                downloadState = "dl_error"
                return
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // 安装失败，回退到浏览器下载
            val url = updateUrl ?: return
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            context.startActivity(intent)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 页面标题
        Text(
            text = stringResource(R.string.settings_screen_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(
                start = Dimens.ScreenPaddingH,
                top = Dimens.ScreenPaddingTop + Dimens.SpaceSm,
                end = Dimens.ScreenPaddingH
            )
        )
        Spacer(modifier = Modifier.height(Dimens.SpaceMd))

        // 设置列表
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(Dimens.SettingsSectionGap),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 第一组设置卡片
            item {
                SettingsCard(
                    modifier = Modifier.padding(horizontal = Dimens.ScreenPaddingH)
                ) {
                    SettingsRow(
                        icon = Icons.Filled.Language,
                        label = stringResource(R.string.settings_row_interface_language),
                        accent = AccentTone.Blue,
                        value = LocaleHelper.getDisplayName(currentLanguage),
                        onClick = { showLanguagePicker = true }
                    )
                    SettingsRow(
                        icon = Icons.Filled.Subtitles,
                        label = stringResource(R.string.settings_row_local),
                        accent = AccentTone.Purple,
                        onClick = onLocalClick
                    )
                    SettingsRow(
                        icon = Icons.Filled.Settings,
                        label = stringResource(R.string.settings_row_api),
                        accent = AccentTone.Mint,
                        onClick = onApiClick
                    )
                    SettingsRow(
                        icon = Icons.Filled.Language,
                        label = stringResource(R.string.settings_row_cloud),
                        accent = AccentTone.Sky,
                        onClick = onCloudClick
                    )
                    SettingsRow(
                        icon = Icons.Filled.Person,
                        label = stringResource(R.string.personalization_entry),
                        accent = AccentTone.Pink,
                        onClick = onPersonalizationClick
                    )
                    SettingsRow(
                        icon = Icons.Filled.CopyAll,
                        label = stringResource(R.string.settings_row_history),
                        accent = AccentTone.Coral,
                        onClick = onHistoryClick
                    )
                }
            }

            // 第二组设置卡片
            item {
                SettingsCard(
                    modifier = Modifier.padding(horizontal = Dimens.ScreenPaddingH)
                ) {
                    SettingsRow(
                        icon = Icons.Filled.Share,
                        label = stringResource(R.string.settings_row_share),
                        accent = AccentTone.Sky,
                        onClick = onShareClick
                    )
                    SettingsRow(
                        icon = Icons.Filled.Star,
                        label = stringResource(R.string.settings_row_feedback),
                        accent = AccentTone.Amber,
                        onClick = onFeedbackClick
                    )
                    val updateValue = when {
                        downloadState?.startsWith("downloading=") == true -> {
                            val pct = downloadState?.removePrefix("downloading=") ?: "0"
                            context.getString(R.string.update_downloading, pct.toIntOrNull() ?: 0)
                        }
                        downloadState == "downloaded" -> context.getString(R.string.update_tap_install)
                        downloadState == "dl_error" -> context.getString(R.string.update_download_failed_retry)
                        updateState == "checking" -> context.getString(R.string.update_checking)
                        updateState == "latest" -> context.getString(R.string.update_latest)
                        updateState == "error" -> context.getString(R.string.update_check_error)
                        updateState?.startsWith("new=") == true ->
                            context.getString(R.string.update_new_version, updateState?.removePrefix("new="))
                        else -> ""
                    }
                    val updateValueColor = when {
                        downloadState == "downloaded" -> Color(0xFF2E7D32)
                        downloadState == "dl_error" -> MaterialTheme.colorScheme.error
                        updateState == "latest" -> Color(0xFF2E7D32)
                        updateState == "error" -> MaterialTheme.colorScheme.error
                        updateState?.startsWith("new=") == true -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    SettingsRow(
                        icon = Icons.Filled.Refresh,
                        label = stringResource(R.string.settings_row_check_update),
                        accent = AccentTone.Coral,
                        value = updateValue,
                        valueColor = updateValueColor,
                        onClick = {
                            when {
                                // 下载中：不响应点击
                                downloadState?.startsWith("downloading=") == true -> {}
                                // 已下载：点击安装
                                downloadState == "downloaded" -> installApk()
                                // 下载失败：重新下载
                                downloadState == "dl_error" -> startDownloadApk()
                                // 检查中：不响应
                                updateState == "checking" -> {}
                                // 已有新版本（弹窗被关了）：重新弹窗
                                updateState?.startsWith("new=") == true -> showUpdateDialog = true
                                // 其他：触发检查
                                else -> {
                                    downloadState = null
                                    doCheckUpdate()
                                }
                            }
                        }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(Dimens.SpaceMd)) }
        }
    }

    // 界面语言选择弹窗
    if (showLanguagePicker) {
        val languages = listOf("zh" to stringResource(R.string.lang_zh), "en" to stringResource(R.string.lang_en))
        AlertDialog(
            onDismissRequest = { showLanguagePicker = false },
            title = { Text(stringResource(R.string.settings_row_interface_language)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
                    languages.forEach { (code, name) ->
                        val selected = code == currentLanguage
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Dimens.CornerMd))
                                .clickable {
                                    if (code != currentLanguage) {
                                            settingsViewModel.setInterfaceLanguage(code)
                                            LocaleHelper.updateAppLocale(code)
                                            (context as? ComponentActivity)?.recreate()
                                        }
                                    showLanguagePicker = false
                                }
                                .padding(Dimens.SpaceSm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onBackground
                            )
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(Dimens.SpaceLg)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguagePicker = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    // ========== 更新弹窗：显示新版本号 + 更新说明 + 更新/取消 ==========
    if (showUpdateDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.update_dialog_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 版本号
                    Text(
                        text = stringResource(R.string.update_dialog_latest, latestVersion ?: ""),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    // 当前版本
                    Text(
                        text = stringResource(R.string.update_dialog_current, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // 更新说明标题
                    Text(
                        text = stringResource(R.string.update_dialog_notes_label),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    // 更新说明正文（可滚动）
                    val notes = releaseNotes ?: stringResource(R.string.update_no_notes)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.4
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUpdateDialog = false
                        startDownloadApk()
                    }
                ) {
                    Text(stringResource(R.string.update_action_update_now))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateDialog = false }) {
                    Text("稍后再说")
                }
            }
        )
    }
}
