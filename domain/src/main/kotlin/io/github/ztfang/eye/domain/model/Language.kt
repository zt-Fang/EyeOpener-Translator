package io.github.ztfang.eye.domain.model

/**
 * 语言定义数据类。
 * 包含语言代码、本地显示名称和英文名称，用于语言选择器和翻译引擎配置。
 */
data class Language(
    val code: String,
    val displayName: String,
    val englishName: String
)

object Languages {
    val ALL = listOf(
        Language("af", "Afrikaans", "Afrikaans"),
        Language("ar", "العربية", "Arabic"),
        Language("be", "Беларуская", "Belarusian"),
        Language("bg", "Български", "Bulgarian"),
        Language("bn", "বাংলা", "Bengali"),
        Language("ca", "Català", "Catalan"),
        Language("cs", "Čeština", "Czech"),
        Language("cy", "Cymraeg", "Welsh"),
        Language("da", "Dansk", "Danish"),
        Language("de", "Deutsch", "German"),
        Language("el", "Ελληνικά", "Greek"),
        Language("en", "English", "English"),
        Language("es", "Español", "Spanish"),
        Language("et", "Eesti", "Estonian"),
        Language("fa", "فارسی", "Persian"),
        Language("fi", "Suomi", "Finnish"),
        Language("fr", "Français", "French"),
        Language("gl", "Galego", "Galician"),
        Language("gu", "ગુજરાતી", "Gujarati"),
        Language("he", "עברית", "Hebrew"),
        Language("hi", "हिन्दी", "Hindi"),
        Language("hr", "Hrvatski", "Croatian"),
        Language("ht", "Kreyòl Ayisyen", "Haitian Creole"),
        Language("hu", "Magyar", "Hungarian"),
        Language("id", "Indonesia", "Indonesian"),
        Language("is", "Íslenska", "Icelandic"),
        Language("it", "Italiano", "Italian"),
        Language("ja", "日本語", "Japanese"),
        Language("ka", "ქართული", "Georgian"),
        Language("kn", "ಕನ್ನಡ", "Kannada"),
        Language("ko", "한국어", "Korean"),
        Language("lt", "Lietuvių", "Lithuanian"),
        Language("lv", "Latviešu", "Latvian"),
        Language("mk", "Македонски", "Macedonian"),
        Language("mr", "मराठी", "Marathi"),
        Language("ms", "Bahasa Melayu", "Malay"),
        Language("mt", "Malti", "Maltese"),
        Language("nl", "Nederlands", "Dutch"),
        Language("no", "Norsk", "Norwegian"),
        Language("pl", "Polski", "Polish"),
        Language("pt", "Português", "Portuguese"),
        Language("ro", "Română", "Romanian"),
        Language("ru", "Русский", "Russian"),
        Language("sk", "Slovenčina", "Slovak"),
        Language("sl", "Slovenščina", "Slovenian"),
        Language("sq", "Shqip", "Albanian"),
        Language("sv", "Svenska", "Swedish"),
        Language("sw", "Kiswahili", "Swahili"),
        Language("ta", "தமிழ்", "Tamil"),
        Language("te", "తెలుగు", "Telugu"),
        Language("th", "ไทย", "Thai"),
        Language("tl", "Tagalog", "Tagalog"),
        Language("tr", "Türkçe", "Turkish"),
        Language("uk", "Українська", "Ukrainian"),
        Language("ur", "اردو", "Urdu"),
        Language("vi", "Tiếng Việt", "Vietnamese"),
        Language("zh", "中文", "Chinese")
    )
    val ALL_CODES: Set<String> = ALL.map { it.code }.toSet()
}
