package io.github.ztfang.eye.ui.screens

/**
 * API 设置界面。
 * 提供 Provider 选择（OpenAI/Claude/DeepSeek）、API Key、Base URL、模型名称配置。
 * 切换 Provider 时自动填充默认 URL 和模型。
 * 支持 API 连接测试，验证成功后保存配置。
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ztfang.eye.R
import io.github.ztfang.eye.engine.translation.llm.LLMProvider
import io.github.ztfang.eye.ui.theme.Dimens
import io.github.ztfang.eye.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun ApiSettingsScreen(
    onBack: () -> Unit,
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    val currentProvider by settingsViewModel.llmProvider.collectAsStateWithLifecycle(initialValue = "OPEN_AI")
    val currentOpenAiKey by settingsViewModel.openAiKey.collectAsStateWithLifecycle(initialValue = "")
    val currentClaudeKey by settingsViewModel.claudeKey.collectAsStateWithLifecycle(initialValue = "")
    val currentOpenAiKeyProvider by settingsViewModel.openAiKeyProvider.collectAsStateWithLifecycle(initialValue = "")
    val currentApiUrl by settingsViewModel.llmUrl.collectAsStateWithLifecycle(initialValue = "")
    val currentModelName by settingsViewModel.llmModel.collectAsStateWithLifecycle(initialValue = "")

    var selectedProvider by rememberSaveable {
        mutableStateOf(
            try { LLMProvider.valueOf(currentProvider) } catch (_: Exception) { LLMProvider.OPEN_AI }
        )
    }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var apiUrl by rememberSaveable { mutableStateOf("") }
    var modelName by rememberSaveable { mutableStateOf("") }

    // 根据 provider 读取对应的 API Key（用于初始化和切换 provider 时回显）
    fun keyFor(provider: LLMProvider): String = when (provider) {
        LLMProvider.CLAUDE -> currentClaudeKey
        else -> {
            // 其他 provider 共用 openAiKey，但只在 openAiKeyProvider 匹配时才回显
            if (currentOpenAiKeyProvider == provider.name) currentOpenAiKey else ""
        }
    }

    // 标记是否已从 DataStore 同步过初始值
    var initialized by rememberSaveable { mutableStateOf(false) }

    // 仅首次进入时同步 DataStore 中的值，保存后不重置用户输入
    LaunchedEffect(Unit) {
        if (!initialized) {
            val provider = try { LLMProvider.valueOf(currentProvider) } catch (_: Exception) { LLMProvider.OPEN_AI }
            selectedProvider = provider
            apiUrl = currentApiUrl
            modelName = currentModelName
            apiKey = keyFor(provider)
            initialized = true
        }
    }

    // 切换 provider 时：URL/Model 用默认值（若用户未自定义），Key 回显该 provider 已保存的值
    LaunchedEffect(selectedProvider) {
        // URL：如果当前是任意 provider 的默认值或为空，则切换到新 provider 的默认值
        if (apiUrl.isBlank() || LLMProvider.entries.any { it.defaultBaseUrl == apiUrl }) {
            apiUrl = selectedProvider.defaultBaseUrl
        }
        // Model：如果当前是任意 provider 的默认值或为空，则切换到新 provider 的默认值
        if (modelName.isBlank() || LLMProvider.entries.any { it.defaultModel == modelName }) {
            modelName = selectedProvider.defaultModel
        }
        // Key：回显当前 provider 已保存的值（用户还没开始编辑时）
        if (apiKey.isBlank()) {
            apiKey = keyFor(selectedProvider)
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
        ApiSettingsTopBar(onBack = onBack)
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPaddingH),
            verticalArrangement = Arrangement.spacedBy(Dimens.PersonalizationSectionGap)
        ) {
            // Provider
            ProviderSelector(
                selected = selectedProvider,
                onSelected = { selectedProvider = it }
            )

            ApiInputCard(
                label = stringResource(R.string.api_key_label),
                placeholder = when (selectedProvider) {
                    LLMProvider.CLAUDE -> "sk-ant-..."
                    LLMProvider.DEEP_SEEK -> "sk-..."
                    else -> "sk-..."
                },
                value = apiKey,
                onValueChange = { apiKey = it },
                isPassword = true,
                helperText = if (keyFor(selectedProvider).isNotBlank() && apiKey.isBlank())
                    context.getString(R.string.api_key_already_configured)
                else null
            )
            ApiInputCard(
                label = stringResource(R.string.api_url_label),
                placeholder = selectedProvider.defaultBaseUrl,
                value = apiUrl,
                onValueChange = { apiUrl = it }
            )
            ApiInputCard(
                label = stringResource(R.string.api_model_label),
                placeholder = selectedProvider.defaultModel,
                value = modelName,
                onValueChange = { modelName = it }
            )

            if (status.isNotEmpty()) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (status.contains(context.getString(R.string.api_saved).replace(" ✓", "")) || status.contains("成功")) Color(0xFF2EB89A) else Color(0xFFE53935),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Button(
                onClick = {
                    if (apiKey.isBlank()) { status = context.getString(R.string.api_enter_key); return@Button }
                    if (apiUrl.isBlank()) { status = context.getString(R.string.api_enter_url); return@Button }
                    if (modelName.isBlank()) { status = context.getString(R.string.api_enter_model); return@Button }
                    isTesting = true; status = context.getString(R.string.api_testing)
                    scope.launch {
                        val err = testApi(
                            apiUrl.trimEnd('/') + selectedProvider.chatPath,
                            apiKey, modelName, selectedProvider
                        )
                        isTesting = false
                        if (err == null) {
                            when (selectedProvider) {
                                LLMProvider.CLAUDE -> settingsViewModel.setClaudeKey(apiKey.trim())
                                else -> {
                                    settingsViewModel.setOpenAiKey(apiKey.trim())
                                    settingsViewModel.setOpenAiKeyProvider(selectedProvider.name)
                                }
                            }
                            settingsViewModel.setLlmUrl(apiUrl.trimEnd('/'))
                            settingsViewModel.setLlmModel(modelName)
                            settingsViewModel.setLlmProvider(selectedProvider.name)
                            status = context.getString(R.string.api_saved)
                        } else {
                            status = context.getString(R.string.api_verify_failed, err)
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
                        stringResource(R.string.api_save),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // 清除配置按钮：重新配置时使用
            // API Key 持久化在 DataStore，永久留存（除非卸载或清数据）。
            // 用户想重新配置只需覆盖输入并保存；此按钮一键清空当前 provider 的 Key+URL+Model。
            Spacer(modifier = Modifier.height(Dimens.SpaceSm))
            Text(
                text = stringResource(R.string.api_config_tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.SpaceXs)
            )
            OutlinedButton(
                onClick = {
                    when (selectedProvider) {
                        LLMProvider.CLAUDE -> settingsViewModel.setClaudeKey("")
                        else -> settingsViewModel.setOpenAiKey("")
                    }
                    settingsViewModel.setOpenAiKeyProvider("")
                    settingsViewModel.setLlmUrl("")
                    settingsViewModel.setLlmModel("")
                    settingsViewModel.setLlmProvider("")
                    apiKey = ""
                    apiUrl = ""
                    modelName = ""
                    status = context.getString(R.string.api_cleared)
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(Dimens.CornerLg)
            ) {
                Text(
                    text = stringResource(R.string.api_clear_config),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFE53935)
                )
            }
        }
    }
}

/** Provider 下拉选择框 */
@Composable
private fun ProviderSelector(
    selected: LLMProvider,
    onSelected: (LLMProvider) -> Unit
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
        // 触发器行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(horizontal = Dimens.SettingsRowPaddingH, vertical = Dimens.SpaceMd),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.api_provider, selected.displayName),
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

        // 下拉菜单
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            LLMProvider.entries.forEach { provider ->
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)
                        ) {
                            Text(
                                text = provider.displayName,
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
                if (provider != LLMProvider.entries.last()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }
            }
        }
    }
}

private suspend fun testApi(
    url: String, key: String, model: String, provider: LLMProvider
): String? = withContext(Dispatchers.IO) {
    try {
        val body = """{"model":"$model","messages":[{"role":"user","content":"hi"}],"max_tokens":5}"""
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"; conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        when (provider) {
            LLMProvider.CLAUDE -> {
                conn.setRequestProperty("x-api-key", key)
                conn.setRequestProperty("anthropic-version", "2023-06-01")
            }
            else -> conn.setRequestProperty("Authorization", "Bearer $key")
        }
        conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
        val code = conn.responseCode
        if (code == 200) null else "HTTP $code"
    } catch (e: Exception) { e.message ?: "连接失败" }
}

@Composable
private fun ApiInputCard(
    label: String, placeholder: String, value: String,
    onValueChange: (String) -> Unit, isPassword: Boolean = false,
    helperText: String? = null
) {
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
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = value, onValueChange = onValueChange,
                placeholder = {
                    Text(placeholder, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f))
                },
                visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
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
            if (helperText != null) {
                Text(
                    text = helperText,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF2EB89A),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun ApiSettingsTopBar(onBack: () -> Unit) {
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
            Icon(Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.api_back_cd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(Dimens.SpaceSm))
        Text(stringResource(R.string.api_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground)
    }
}
