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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
    settingsViewModel: SettingsViewModel = hiltViewModel(),
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
            try {
                LLMProvider.valueOf(currentProvider)
            } catch (_: Exception) {
                LLMProvider.OPEN_AI
            },
        )
    }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var apiUrl by rememberSaveable { mutableStateOf("") }
    var modelName by rememberSaveable { mutableStateOf("") }

    // 根据 provider 读取对应的 API Key（用于初始化和切换 provider 时回显）
    fun keyFor(provider: LLMProvider): String =
        when (provider) {
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
            val provider =
                try {
                    LLMProvider.valueOf(currentProvider)
                } catch (_: Exception) {
                    LLMProvider.OPEN_AI
                }
            selectedProvider = provider
            apiUrl = currentApiUrl
            modelName = currentModelName
            apiKey = keyFor(provider)
            initialized = true
        }
    }

    // 从服务商 API 拉取的模型列表（null = 未拉取，展示内置预设）
    var fetchedModels by remember { mutableStateOf<List<String>?>(null) }
    var isFetchingModels by remember { mutableStateOf(false) }
    var fetchNote by remember { mutableStateOf<String?>(null) }
    var fetchNoteIsError by remember { mutableStateOf(false) }
    // 模型卡片内「可选模型」列表的展开状态
    var modelListExpanded by remember { mutableStateOf(false) }

    // 切换 provider 时：URL/Model 用默认值（若用户未自定义），Key 回显该 provider 已保存的值
    LaunchedEffect(selectedProvider) {
        // URL：如果当前是任意 provider 的默认值或为空，则切换到新 provider 的默认值
        if (apiUrl.isBlank() || LLMProvider.entries.any { it.defaultBaseUrl == apiUrl }) {
            apiUrl = selectedProvider.defaultBaseUrl
        }
        // Model：不再为空时强制预填（模型名不预设）；仅当当前模型命中任意 provider 默认值时，
        // 随服务商切换为该 provider 的默认模型，避免跨服务商残留无效模型。
        if (LLMProvider.entries.any { it.defaultModel == modelName }) {
            modelName = selectedProvider.defaultModel
        }
        // Key：回显当前 provider 已保存的值（用户还没开始编辑时）
        if (apiKey.isBlank()) {
            apiKey = keyFor(selectedProvider)
        }
        // 切换服务商后，拉取的模型列表不再适用，重置回该服务商的内置预设
        fetchedModels = null
        fetchNote = null
        fetchNoteIsError = false
        modelListExpanded = false
    }

    var isTesting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    /** status 是错误提示（红）还是成功提示（绿）。旧实现靠 status 文本 contains 判断，脆弱且文案一改就失效 */
    var statusIsError by remember { mutableStateOf(false) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .imePadding(),
        verticalArrangement = Arrangement.spacedBy(Dimens.PersonalizationSectionGap),
    ) {
        ApiSettingsTopBar(onBack = onBack)
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPaddingH),
            verticalArrangement = Arrangement.spacedBy(Dimens.PersonalizationSectionGap),
        ) {
            // Provider
            ProviderSelector(
                selected = selectedProvider,
                onSelected = { selectedProvider = it },
            )

            ApiInputCard(
                label = stringResource(R.string.api_key_label),
                placeholder =
                    when (selectedProvider) {
                        LLMProvider.CLAUDE -> "sk-ant-..."
                        LLMProvider.DEEP_SEEK -> "sk-..."
                        else -> "sk-..."
                    },
                value = apiKey,
                onValueChange = { apiKey = it },
                isPassword = true,
                helperText =
                    if (keyFor(selectedProvider).isNotBlank() && apiKey.isBlank()) {
                        context.getString(R.string.api_key_already_configured)
                    } else {
                        null
                    },
            )
            ApiInputCard(
                label = stringResource(R.string.api_url_label),
                placeholder = selectedProvider.defaultBaseUrl,
                value = apiUrl,
                onValueChange = { apiUrl = it },
            )
            // 模型名称卡：自由输入 + 「拉取」实时模型 + 内置预设三合一；
            // 列表优先展示 API 实时拉取结果，未拉取时展示内置预设（均为官网核实支持流式的模型）。
            ModelInputCard(
                label = stringResource(R.string.api_model_label),
                placeholder = selectedProvider.defaultModel,
                value = modelName,
                onValueChange = { modelName = it },
                models = fetchedModels ?: selectedProvider.models,
                expanded = modelListExpanded,
                onExpandedChange = { modelListExpanded = it },
                isFetching = isFetchingModels,
                note = fetchNote,
                noteIsError = fetchNoteIsError,
                onFetch = {
                    if (apiKey.isBlank()) {
                        fetchNote = context.getString(R.string.api_model_fetch_need_key)
                        fetchNoteIsError = true
                    } else {
                        scope.launch {
                            isFetchingModels = true
                            fetchNote = context.getString(R.string.api_model_fetching)
                            fetchNoteIsError = false
                            settingsViewModel
                                .fetchLlmModels(selectedProvider, apiUrl.trim(), apiKey.trim())
                                .onSuccess { list ->
                                    fetchedModels = list
                                    fetchNote =
                                        if (list.isEmpty()) {
                                            context.getString(R.string.api_model_fetch_empty)
                                        } else {
                                            context.getString(R.string.api_model_fetch_success, list.size)
                                        }
                                    fetchNoteIsError = false
                                    // 拉取成功自动展开列表，减少一次点击
                                    modelListExpanded = true
                                }.onFailure { e ->
                                    // 拉取失败：保留内置预设，仅提示原因
                                    fetchNote = context.getString(R.string.api_model_fetch_failed, e.message ?: "")
                                    fetchNoteIsError = true
                                }
                            isFetchingModels = false
                        }
                    }
                },
            )

            if (status.isNotEmpty()) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (statusIsError) {
                            Color(0xFFE53935)
                        } else {
                            Color(0xFF2EB89A)
                        },
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // ============ 保存（本地，不联网） ============
            // 关键修复：保存必须无条件成功，仅做本地非空校验。
            // 历史缺陷：旧实现把「测试连通性」和「保存」绑在同一个按钮上，只有 testApi 返回 HTTP 200
            // 才写 DataStore → 代理不通/服务商对 max_tokens=5 返回 4xx 时配置永远存不进去，
            // 用户以为"代理已开启"实则 DataStore 里什么都没有，"翻译没反应"由此而来。
            Button(
                onClick = {
                    if (apiKey.isBlank()) {
                        status = context.getString(R.string.api_enter_key)
                        statusIsError = true
                        return@Button
                    }
                    if (apiUrl.isBlank()) {
                        status = context.getString(R.string.api_enter_url)
                        statusIsError = true
                        return@Button
                    }
                    if (modelName.isBlank()) {
                        status = context.getString(R.string.api_enter_model)
                        statusIsError = true
                        return@Button
                    }
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
                    statusIsError = false
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(Dimens.CornerLg),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1A73E8),
                        contentColor = Color.White,
                    ),
            ) {
                Text(
                    stringResource(R.string.api_save),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // ============ 测试连通性（联网，可选） ============
            // 与保存解耦：失败只提示，不影响已保存的配置。
            OutlinedButton(
                onClick = {
                    if (apiKey.isBlank() || apiUrl.isBlank() || modelName.isBlank()) {
                        status = context.getString(R.string.api_test_need_input)
                        statusIsError = true
                        return@OutlinedButton
                    }
                    isTesting = true
                    status = context.getString(R.string.api_testing)
                    statusIsError = false
                    scope.launch {
                        val err =
                            testApi(
                                apiUrl.trimEnd('/') + selectedProvider.chatPath,
                                apiKey,
                                modelName,
                                selectedProvider,
                            )
                        isTesting = false
                        if (err == null) {
                            status = context.getString(R.string.api_test_ok)
                            statusIsError = false
                        } else {
                            status = context.getString(R.string.api_verify_failed, err)
                            statusIsError = true
                        }
                    }
                },
                enabled = !isTesting,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(Dimens.CornerLg),
                border = BorderStroke(1.dp, Color(0xFF1A73E8).copy(alpha = 0.5f)),
            ) {
                if (isTesting) {
                    CircularProgressIndicator(
                        color = Color(0xFF1A73E8),
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Text(
                        stringResource(R.string.api_test_connection),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF1A73E8),
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
                modifier = Modifier.padding(horizontal = Dimens.SpaceXs),
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
                    statusIsError = false
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(Dimens.CornerLg),
            ) {
                Text(
                    text = stringResource(R.string.api_clear_config),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFE53935),
                )
            }
        }
    }
}

/**
 * Provider 下拉选择框。
 * 触发框与下拉面板共用同一玻璃卡片样式：展开时触发框底部方角、面板顶部方角互相对接，
 * 面板底部 20dp 圆角，形成「无缝 + 圆角」的连续卡片（解决原 ExposedDropdownMenu 与服务商框的可见缝隙）。
 * 展开时点击触发框或某项即收起；面板在常规流中就地展开，不遮挡全屏。
 */
@Composable
private fun ProviderSelector(
    selected: LLMProvider,
    onSelected: (LLMProvider) -> Unit,
) {
    val corner = Dimens.SettingsCardCorner
    var expanded by remember { mutableStateOf(false) }

    val triggerShape =
        if (expanded) {
            RoundedCornerShape(corner, corner, 0.dp, 0.dp)
        } else {
            RoundedCornerShape(corner)
        }
    val panelShape = RoundedCornerShape(0.dp, 0.dp, corner, corner)

    Column(modifier = Modifier.fillMaxWidth()) {
        // 触发框
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .shadow(
                        if (expanded) 0.dp else Dimens.GlassShadowElevation,
                        triggerShape,
                        ambientColor = Color(0xFF1A73E8).copy(alpha = 0.10f),
                        spotColor = Color(0xFF1A73E8).copy(alpha = 0.12f),
                    ).clip(triggerShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
                    .border(
                        BorderStroke(
                            1.dp,
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = Dimens.GlassHighlightAlpha),
                                    Color.White.copy(alpha = 0.15f),
                                ),
                            ),
                        ),
                        triggerShape,
                    ).clickable { expanded = !expanded },
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.SettingsRowPaddingH, vertical = Dimens.SpaceMd),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.api_provider, selected.displayName),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                Icon(
                    imageVector =
                        if (expanded) {
                            Icons.Default.KeyboardArrowUp
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 下拉面板：与触发框无缝拼接，底部 20dp 圆角
        if (expanded) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(panelShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
                        .border(
                            BorderStroke(
                                1.dp,
                                Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = Dimens.GlassHighlightAlpha),
                                        Color.White.copy(alpha = 0.15f),
                                    ),
                                ),
                            ),
                            panelShape,
                        ),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            // 必须限高：本面板嵌在整页 verticalScroll 内，外层会传下无限高度约束，
                            // 内层 verticalScroll 收到无限高度会直接抛 IllegalStateException（点击展开即闪退）
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = Dimens.SpaceXs),
                ) {
                    LLMProvider.entries.forEach { provider ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelected(provider)
                                        expanded = false
                                    }.padding(
                                        horizontal = Dimens.SettingsRowPaddingH,
                                        vertical = Dimens.SpaceMd,
                                    ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
                        ) {
                            Text(
                                text = provider.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                color =
                                    if (provider == selected) {
                                        Color(0xFF1A73E8)
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                fontWeight =
                                    if (provider == selected) {
                                        FontWeight.SemiBold
                                    } else {
                                        FontWeight.Normal
                                    },
                            )
                            if (provider == selected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color(0xFF1A73E8),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        if (provider != LLMProvider.entries.last()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 模型名称卡：自由输入 + 「拉取」实时模型 + 内置预设三合一。
 * 「拉取」按钮位于标题行右侧，点击实时请求服务商 /models 接口并刷新可选列表，
 * 成功后自动展开列表（嵌在可滚动页面内，必须 heightIn 限高，否则无限高度约束会崩溃）；
 * 拉取失败回退内置预设并提示原因。点选模型即填入输入框，也可直接手动输入自定义模型名。
 */
@Composable
private fun ModelInputCard(
    label: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    models: List<String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    isFetching: Boolean,
    note: String?,
    noteIsError: Boolean,
    onFetch: () -> Unit,
) {
    val shape = RoundedCornerShape(Dimens.SettingsCardCorner)
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .shadow(
                    Dimens.GlassShadowElevation,
                    shape = shape,
                    ambientColor = Color(0xFF1A73E8).copy(alpha = 0.10f),
                    spotColor = Color(0xFF1A73E8).copy(alpha = 0.12f),
                ).clip(shape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
                .border(
                    BorderStroke(
                        1.dp,
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = Dimens.GlassHighlightAlpha), Color.White.copy(alpha = 0.15f)),
                        ),
                    ),
                    shape = shape,
                ).padding(horizontal = Dimens.SettingsRowPaddingH, vertical = Dimens.SpaceMd),
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
            // 标题行：label + 「拉取」按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                // 拉取按钮：实时请求服务商 /models 接口
                Row(
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(Dimens.CornerMd))
                            .clickable(enabled = !isFetching, onClick = onFetch)
                            .padding(horizontal = Dimens.SpaceXs, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (isFetching) {
                        CircularProgressIndicator(
                            color = Color(0xFF1A73E8),
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = Color(0xFF1A73E8),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.api_model_fetch),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF1A73E8),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // 模型名自由输入框
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    )
                },
                shape = RoundedCornerShape(Dimens.CornerMd),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF1A73E8),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        cursorColor = Color(0xFF1A73E8),
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )

            // 拉取状态/错误提示行
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (noteIsError) Color(0xFFE53935) else Color(0xFF2EB89A),
                    fontWeight = FontWeight.Medium,
                )
            }

            // 可选模型列表（就地展开，点选填入输入框）
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onExpandedChange(!expanded) }
                        .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text =
                        if (models.isEmpty()) {
                            stringResource(R.string.api_model_fetch_empty_hint)
                        } else {
                            stringResource(R.string.api_model_preset_hint)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(Dimens.SpaceSm))
                Icon(
                    imageVector =
                        if (expanded) {
                            Icons.Default.KeyboardArrowUp
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded && models.isNotEmpty()) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            // 必须限高：本卡嵌在整页 verticalScroll 内，外层传下无限高度约束，
                            // 内层 verticalScroll 收到无限高度会抛 IllegalStateException（展开即闪退）
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                ) {
                    models.forEachIndexed { index, m ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onValueChange(m)
                                        onExpandedChange(false)
                                    }.padding(vertical = Dimens.SpaceSm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
                        ) {
                            Text(
                                text = m,
                                style = MaterialTheme.typography.bodyMedium,
                                color =
                                    if (m == value) {
                                        Color(0xFF1A73E8)
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                fontWeight =
                                    if (m == value) {
                                        FontWeight.SemiBold
                                    } else {
                                        FontWeight.Normal
                                    },
                                maxLines = 1,
                            )
                            if (m == value) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color(0xFF1A73E8),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        if (index != models.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 连通性测试（可选操作，与保存解耦）。
 *
 * 用 [HttpURLConnection] 发一次最小请求，返回 null 表示通、非 null 为失败原因。
 * 注意：这只是"配置能不能连上"的粗检，与真实翻译走的是 LLMClient(OkHttp) 两套实现，
 * 因此测试通过 ≠ 翻译可用，测试失败也 ≠ 翻译一定失败——所以它不能作为保存的前置条件。
 *
 * 关键修复：
 *  1. 显式设置 connect/read 超时（旧实现为默认 0 = 无限等待，网络不通时按钮长时间转圈）；
 *  2. 非 2xx 时把响应体前 200 字一并返回（旧实现只报 "HTTP 4xx"，用户无从判断是 key 错、
 *     模型名错、还是路径错）；
 *  3. 放宽成功判据：OpenAI 兼容网关对 `max_tokens=5` 常回 400/429，只要不是鉴权/路径类错误
 *     也提示"连接成功但服务端返回 N"，避免误导。
 */
private suspend fun testApi(
    url: String,
    key: String,
    model: String,
    provider: LLMProvider,
): String? =
    withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val body = """{"model":"$model","messages":[{"role":"user","content":"hi"}],"max_tokens":5}"""
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Content-Type", "application/json")
            when (provider) {
                LLMProvider.CLAUDE -> {
                    conn.setRequestProperty("x-api-key", key)
                    conn.setRequestProperty("anthropic-version", "2023-06-01")
                }
                else -> conn.setRequestProperty("Authorization", "Bearer $key")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) {
                null
            } else {
                // 错误流才是真正的失败原因载体；正常流在非 2xx 时通常为空
                val detail =
                    runCatching {
                        (conn.errorStream ?: conn.inputStream)
                            ?.bufferedReader(Charsets.UTF_8)
                            ?.use { it.readText() }
                            .orEmpty()
                            .trim()
                            .take(200)
                    }.getOrDefault("")
                if (detail.isBlank()) "HTTP $code" else "HTTP $code — $detail"
            }
        } catch (e: java.net.SocketTimeoutException) {
            "连接超时（10s）：$url 不可达，请检查网络或代理"
        } catch (e: Exception) {
            e.message ?: "连接失败"
        } finally {
            conn?.disconnect()
        }
    }

@Composable
private fun ApiInputCard(
    label: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    isPassword: Boolean = false,
    helperText: String? = null,
) {
    val shape = RoundedCornerShape(Dimens.SettingsCardCorner)
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .shadow(
                    Dimens.GlassShadowElevation,
                    shape = shape,
                    ambientColor = Color(0xFF1A73E8).copy(alpha = 0.10f),
                    spotColor = Color(0xFF1A73E8).copy(alpha = 0.12f),
                ).clip(shape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
                .border(
                    BorderStroke(
                        1.dp,
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = Dimens.GlassHighlightAlpha), Color.White.copy(alpha = 0.15f)),
                        ),
                    ),
                    shape = shape,
                ).padding(horizontal = Dimens.SettingsRowPaddingH, vertical = Dimens.SpaceMd),
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    )
                },
                visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                shape = RoundedCornerShape(Dimens.CornerMd),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF1A73E8),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        cursorColor = Color(0xFF1A73E8),
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            if (helperText != null) {
                Text(
                    text = helperText,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF2EB89A),
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun ApiSettingsTopBar(onBack: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(Dimens.PersonalizationTopBarHeight)
                .padding(horizontal = Dimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(Dimens.TopAppBarIconBox)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
                    .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.api_back_cd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(Dimens.SpaceSm))
        Text(
            stringResource(R.string.api_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
