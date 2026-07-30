package io.github.ztfang.eye.ui.screens

/**
 * 云端翻译设置界面。
 *
 * - 顶部提示语："各云端翻译支持语言以官方文档为准"
 * - 服务商选择：Papago / 百度 / DeepL / Azure
 * - 凭证输入：按服务商显示不同字段
 *   - Papago: Client ID + Client Secret（两栏）
 *   - 百度:   App ID + Secret Key（两栏）
 *   - DeepL:  Auth Key（单栏）
 *   - Azure:  Subscription Key + Region（两栏，Region 可选）
 * - 保存/清除配置（与 ApiSettingsScreen 形式一致）
 *
 * 内部仍以单字符串 cloudTranslationApiKey 持久化：
 *   - Papago/百度/Azure：保存为 "id:secret"（Azure region 可空时为 "key:"）
 *   - DeepL：保存为完整 AuthKey
 *
 * 切换服务商时清空输入（不同服务商字段不同）。
 */

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ztfang.eye.R
import io.github.ztfang.eye.domain.model.CloudTranslationProvider
import io.github.ztfang.eye.ui.theme.Dimens
import io.github.ztfang.eye.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest

@Composable
fun CloudTranslationSettingsScreen(
    onBack: () -> Unit,
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    val currentProvider by settingsViewModel.cloudTranslationProvider
        .collectAsStateWithLifecycle(initialValue = CloudTranslationProvider.PAPAGO)
    val currentApiKey by settingsViewModel.cloudTranslationApiKey
        .collectAsStateWithLifecycle(initialValue = "")

    var selectedProvider by rememberSaveable { mutableStateOf(currentProvider) }
    var keyField by rememberSaveable { mutableStateOf("") }
    var secretField by rememberSaveable { mutableStateOf("") }
    var initialized by rememberSaveable { mutableStateOf(false) }

    // 首次进入同步 DataStore（按 provider 拆分显示）
    LaunchedEffect(Unit) {
        if (!initialized) {
            selectedProvider = currentProvider
            val (k, s) = splitCredential(currentProvider, currentApiKey)
            keyField = k
            secretField = s
            initialized = true
        }
    }

    // 切换服务商：清空输入（不同服务商字段不同）+ 同步已保存值
    LaunchedEffect(selectedProvider) {
        if (initialized) {
            val saved = if (selectedProvider == currentProvider) currentApiKey else ""
            val (k, s) = splitCredential(selectedProvider, saved)
            keyField = k
            secretField = s
        }
    }

    var isTesting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(Dimens.PersonalizationSectionGap)
    ) {
        CloudSettingsTopBar(onBack = onBack)

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPaddingH),
            verticalArrangement = Arrangement.spacedBy(Dimens.PersonalizationSectionGap)
        ) {
            // 顶部提示语卡片
            CloudTipCard()

            // Provider 下拉
            CloudProviderSelector(
                selected = selectedProvider,
                onSelected = { selectedProvider = it }
            )

            // 凭证输入区
            CloudCredentialInputs(
                provider = selectedProvider,
                keyField = keyField,
                secretField = secretField,
                onKeyChange = { keyField = it },
                onSecretChange = { secretField = it },
                alreadyConfigured = currentApiKey.isNotBlank() && keyField.isBlank() && secretField.isBlank()
            )

            if (status.isNotEmpty()) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (status.contains(context.getString(R.string.cloud_saved).replace(" ✓", ""))
                        || status.contains("成功")) Color(0xFF2EB89A) else Color(0xFFE53935),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // 保存按钮（含连通性验证）
            Button(
                onClick = {
                    if (keyField.isBlank() && selectedProvider != CloudTranslationProvider.DEEPL) {
                        status = context.getString(R.string.cloud_enter_key)
                        return@Button
                    }
                    if (keyField.isBlank()) {
                        status = context.getString(R.string.cloud_enter_key)
                        return@Button
                    }
                    val merged = mergeCredential(selectedProvider, keyField.trim(), secretField.trim())
                    isTesting = true
                    status = context.getString(R.string.cloud_testing)
                    scope.launch {
                        val err = testCloudApi(selectedProvider, merged)
                        isTesting = false
                        if (err == null) {
                            settingsViewModel.setCloudTranslationProvider(selectedProvider)
                            settingsViewModel.setCloudTranslationApiKey(merged)
                            status = context.getString(R.string.cloud_saved)
                        } else {
                            status = context.getString(R.string.cloud_verify_failed, err)
                        }
                    }
                },
                enabled = !isTesting,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(Dimens.CornerLg),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1A73E8), contentColor = Color.White
                )
            ) {
                if (isTesting) {
                    CircularProgressIndicator(
                        color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp)
                    )
                } else {
                    Text(
                        stringResource(R.string.cloud_save),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // 清除配置
            Spacer(modifier = Modifier.height(Dimens.SpaceSm))
            Text(
                text = stringResource(R.string.cloud_config_tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.SpaceXs)
            )
            OutlinedButton(
                onClick = {
                    settingsViewModel.setCloudTranslationApiKey("")
                    keyField = ""
                    secretField = ""
                    status = context.getString(R.string.cloud_cleared)
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(Dimens.CornerLg)
            ) {
                Text(
                    text = stringResource(R.string.cloud_clear_config),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFE53935)
                )
            }
        }
    }
}

/** 顶部提示卡片 */
@Composable
private fun CloudTipCard() {
    val shape = RoundedCornerShape(Dimens.SettingsCardCorner)
    Box(
        modifier = Modifier.fillMaxWidth()
            .shadow(Dimens.GlassShadowElevation, shape = shape,
                ambientColor = Color(0xFF1A73E8).copy(alpha = 0.10f),
                spotColor = Color(0xFF1A73E8).copy(alpha = 0.12f))
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
            .border(BorderStroke(1.dp, Brush.verticalGradient(
                listOf(Color.White.copy(alpha = Dimens.GlassHighlightAlpha), Color.White.copy(alpha = 0.15f))
            )), shape = shape)
            .padding(horizontal = Dimens.SettingsRowPaddingH, vertical = Dimens.SpaceMd)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = Color(0xFF1A73E8),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(Dimens.SpaceSm))
            Text(
                text = stringResource(R.string.cloud_tip),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/** Provider 下拉选择 */
@Composable
private fun CloudProviderSelector(
    selected: CloudTranslationProvider,
    onSelected: (CloudTranslationProvider) -> Unit
) {
    val shape = RoundedCornerShape(Dimens.SettingsCardCorner)
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxWidth()
            .shadow(Dimens.GlassShadowElevation, shape = shape,
                ambientColor = Color(0xFF1A73E8).copy(alpha = 0.10f),
                spotColor = Color(0xFF1A73E8).copy(alpha = 0.12f))
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
            .border(BorderStroke(1.dp, Brush.verticalGradient(
                listOf(Color.White.copy(alpha = Dimens.GlassHighlightAlpha), Color.White.copy(alpha = 0.15f))
            )), shape = shape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(horizontal = Dimens.SettingsRowPaddingH, vertical = Dimens.SpaceMd),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.cloud_provider_format, stringResource(selected.displayNameRes())),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp
                else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            CloudTranslationProvider.entries.forEach { provider ->
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)
                        ) {
                            Text(
                                text = stringResource(provider.displayNameRes()),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (provider == selected) Color(0xFF1A73E8)
                                else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (provider == selected) FontWeight.SemiBold
                                else FontWeight.Normal
                            )
                            if (provider == selected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color(0xFF1A73E8),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    onClick = {
                        onSelected(provider)
                        expanded = false
                    }
                )
                if (provider != CloudTranslationProvider.entries.last()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }
            }
        }
    }
}

/**
 * 凭证输入区：按 provider 渲染不同字段。
 * - Papago: Client ID + Client Secret
 * - 百度:   App ID + Secret Key
 * - DeepL:  仅 Auth Key
 * - Azure:  Subscription Key + Region（可选）
 */
@Composable
private fun CloudCredentialInputs(
    provider: CloudTranslationProvider,
    keyField: String,
    secretField: String,
    onKeyChange: (String) -> Unit,
    onSecretChange: (String) -> Unit,
    alreadyConfigured: Boolean
) {
    val (keyLabel, keyPlaceholder, secretLabel, secretPlaceholder, secretOptional) = when (provider) {
        CloudTranslationProvider.PAPAGO -> Quintuple(
            "Client ID", "Papago Client ID",
            "Client Secret", "Papago Client Secret", false
        )
        CloudTranslationProvider.BAIDU -> Quintuple(
            "App ID", "百度 App ID",
            "Secret Key", "百度 Secret Key", false
        )
        CloudTranslationProvider.AZURE -> Quintuple(
            "Subscription Key", "Azure Subscription Key",
            "Region", "例如 eastasia（可选）", true
        )
        CloudTranslationProvider.DEEPL -> Quintuple(
            "Auth Key", "DeepL AuthKey（Free 以 :fx 结尾）",
            "", "", false
        )
        CloudTranslationProvider.GOOGLE -> Quintuple(
            stringResource(R.string.cloud_api_key_label), stringResource(R.string.cloud_api_key_hint_google),
            "", "", false
        )
    }

    val shape = RoundedCornerShape(Dimens.SettingsCardCorner)
    Box(
        modifier = Modifier.fillMaxWidth()
            .shadow(Dimens.GlassShadowElevation, shape = shape,
                ambientColor = Color(0xFF1A73E8).copy(alpha = 0.10f),
                spotColor = Color(0xFF1A73E8).copy(alpha = 0.12f))
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
            .border(BorderStroke(1.dp, Brush.verticalGradient(
                listOf(Color.White.copy(alpha = Dimens.GlassHighlightAlpha), Color.White.copy(alpha = 0.15f))
            )), shape = shape)
            .padding(horizontal = Dimens.SettingsRowPaddingH, vertical = Dimens.SpaceMd)
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
            // 主键输入
            CloudLabeledTextField(
                label = keyLabel,
                placeholder = keyPlaceholder,
                value = keyField,
                onValueChange = onKeyChange
            )
            // 副字段（DeepL 无）
            if (secretLabel.isNotEmpty()) {
                CloudLabeledTextField(
                    label = if (secretOptional) "$secretLabel（可选）" else secretLabel,
                    placeholder = secretPlaceholder,
                    value = secretField,
                    onValueChange = onSecretChange
                )
            }
            if (alreadyConfigured) {
                Text(
                    text = stringResource(R.string.cloud_api_key_already_configured),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF2EB89A),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/** 单个带标签的密码输入框 */
@Composable
private fun CloudLabeledTextField(
    label: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXxs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                )
            },
            visualTransformation = PasswordVisualTransformation(),
            shape = RoundedCornerShape(Dimens.CornerMd),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF1A73E8),
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                cursorColor = Color(0xFF1A73E8),
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent
            ),
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 顶部栏（仅返回按钮 + 标题，无图标） */
@Composable
private fun CloudSettingsTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(Dimens.PersonalizationTopBarHeight)
            .padding(horizontal = Dimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(Dimens.TopAppBarIconBox).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.cloud_back_cd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(Dimens.SpaceSm))
        Text(
            text = stringResource(R.string.cloud_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

/** Provider 显示名称资源 ID */
private fun CloudTranslationProvider.displayNameRes(): Int = when (this) {
    CloudTranslationProvider.PAPAGO -> R.string.cloud_provider_papago
    CloudTranslationProvider.BAIDU -> R.string.cloud_provider_baidu
    CloudTranslationProvider.DEEPL -> R.string.cloud_provider_deepl
    CloudTranslationProvider.AZURE -> R.string.cloud_provider_azure
    CloudTranslationProvider.GOOGLE -> R.string.cloud_provider_google
}

/**
 * 将持久化的合并字符串拆分为 (key, secret)。
 * - Papago/百度/Azure：格式 "key:secret"，secret 可空
 * - DeepL：整串即为 AuthKey，secret 为空
 */
private fun splitCredential(
    provider: CloudTranslationProvider,
    raw: String
): Pair<String, String> {
    if (raw.isBlank()) return "" to ""
    // DeepL 和 Google：整串即为 API Key，无 secret
    if (provider == CloudTranslationProvider.DEEPL || provider == CloudTranslationProvider.GOOGLE) return raw to ""
    val idx = raw.indexOf(':')
    return if (idx <= 0) raw to "" else raw.substring(0, idx) to raw.substring(idx + 1)
}

/**
 * 将 (key, secret) 合并为持久化字符串。
 * - Papago/百度/Azure：合并为 "key:secret"
 * - DeepL：仅 key（AuthKey）
 */
private fun mergeCredential(
    provider: CloudTranslationProvider,
    key: String,
    secret: String
): String = when (provider) {
    CloudTranslationProvider.DEEPL, CloudTranslationProvider.GOOGLE -> key
    else -> if (secret.isBlank()) key else "$key:$secret"
}

/** 五元组数据载体（避免新增长度参数） */
private data class Quintuple(
    val a: String, val b: String, val c: String, val d: String, val e: Boolean
)

private operator fun Quintuple.component1() = a
private operator fun Quintuple.component2() = b
private operator fun Quintuple.component3() = c
private operator fun Quintuple.component4() = d
private operator fun Quintuple.component5() = e

/**
 * 云端 API 连通性验证。
 * 各 provider 发起一次最小请求，验证凭证是否有效。
 *
 * @param merged 已合并的凭证字符串（与持久化格式一致）
 * @return null 表示验证成功，非 null 为错误描述
 */
private suspend fun testCloudApi(
    provider: CloudTranslationProvider,
    merged: String
): String? = withContext(Dispatchers.IO) {
    try {
        val client = OkHttpClient.Builder().build()
        when (provider) {
            CloudTranslationProvider.PAPAGO -> {
                val (id, secret) = splitCredential(provider, merged)
                if (id.isBlank() || secret.isBlank())
                    return@withContext "Papago 需要 Client ID 和 Client Secret"
                val form = FormBody.Builder()
                    .add("source", "en").add("target", "ko").add("text", "hi")
                    .build()
                val req = Request.Builder()
                    .url("https://openapi.naver.com/v1/papago/n2mt")
                    .addHeader("X-Naver-Client-Id", id)
                    .addHeader("X-Naver-Client-Secret", secret)
                    .post(form).build()
                client.newCall(req).execute().use { res ->
                    if (res.isSuccessful) null else "HTTP ${res.code}"
                }
            }
            CloudTranslationProvider.BAIDU -> {
                val (appid, secret) = splitCredential(provider, merged)
                if (appid.isBlank() || secret.isBlank())
                    return@withContext "百度需要 App ID 和 Secret Key"
                val salt = System.currentTimeMillis().toString()
                val sign = md5("$appid" + "hi" + salt + secret)
                val url = "https://fanyi-api.baidu.com/api/trans/vip/translate" +
                    "?q=hi&from=en&to=zh&appid=$appid&salt=$salt&sign=$sign"
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { res ->
                    val body = res.body?.string() ?: ""
                    val json = JSONObject(body)
                    if (json.has("error_code")) {
                        "${json.optString("error_code")}: ${json.optString("error_msg")}"
                    } else null
                }
            }
            CloudTranslationProvider.DEEPL -> {
                val form = FormBody.Builder()
                    .add("text", "hi").add("source_lang", "EN").add("target_lang", "DE")
                    .build()
                val baseUrl = if (merged.endsWith(":fx"))
                    "https://api-free.deepl.com/v2/translate"
                else "https://api.deepl.com/v2/translate"
                val req = Request.Builder()
                    .url(baseUrl)
                    .addHeader("Authorization", "DeepL-Auth-Key $merged")
                    .post(form).build()
                client.newCall(req).execute().use { res ->
                    if (res.isSuccessful) null else "HTTP ${res.code}"
                }
            }
            CloudTranslationProvider.AZURE -> {
                val (key, region) = splitCredential(provider, merged)
                if (key.isBlank()) return@withContext "Azure 需要 Subscription Key"
                val body = "[{\"Text\":\"hi\"}]".toRequestBody(JSON_MEDIA)
                val url = "https://api.cognitive.microsofttranslator.com/translate" +
                    "?api-version=3.0&from=en&to=zh-Hans"
                val req = Request.Builder()
                    .url(url)
                    .addHeader("Ocp-Apim-Subscription-Key", key)
                    .addHeader("Content-Type", "application/json; charset=UTF-8")
                    .apply {
                        if (region.isNotEmpty())
                            addHeader("Ocp-Apim-Subscription-Region", region)
                    }
                    .post(body).build()
                client.newCall(req).execute().use { res ->
                    if (res.isSuccessful) null else "HTTP ${res.code}"
                }
            }
            CloudTranslationProvider.GOOGLE -> {
                if (merged.isBlank())
                    return@withContext "Google Cloud 需要 API Key"
                val body = """{"q":"hi","source":"en","target":"zh","format":"text"}"""
                    .toRequestBody(JSON_MEDIA)
                val req = Request.Builder()
                    .url("https://translation.googleapis.com/language/translate/v2?key=$merged")
                    .post(body).build()
                client.newCall(req).execute().use { res ->
                    if (res.isSuccessful) null else "HTTP ${res.code}"
                }
            }
        }
    } catch (e: Exception) {
        e.message ?: "连接失败"
    }
}

/** MD5 哈希 */
private fun md5(input: String): String {
    val md = MessageDigest.getInstance("MD5")
    val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

/** Azure 验证用的 JSON MediaType 常量 */
private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
