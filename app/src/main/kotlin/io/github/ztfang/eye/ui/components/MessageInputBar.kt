package io.github.ztfang.eye.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.ztfang.eye.R
import io.github.ztfang.eye.ui.theme.Dimens

/**
 * 助手界面输入栏 — 输入框和麦克风合并为单个圆角容器。
 *
 * - 左侧输入框 + 右侧麦克风图标，同一背景
 * - 输入框随内容行数自动增高，无最大行数限制
 * - 发送通过软键盘 IME Action
 * - 麦克风点击切换开始/停止
 */
@Composable
fun MessageInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onMicClick: () -> Unit,
    isListening: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPaddingH, vertical = Dimens.SpaceXs)
            .clip(RoundedCornerShape(Dimens.CornerLg))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .heightIn(min = Dimens.InputBarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 输入框
        TextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = {
                Text(
                    text = if (isListening) "正在聆听..."
                           else stringResource(R.string.assistant_input_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isListening) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            },
            singleLine = false,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = if (isListening) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.weight(1f)
        )

        // 麦克风按钮（内嵌）
        Box(
            modifier = Modifier
                .padding(end = Dimens.SpaceSm)
                .size(Dimens.InputBarIconSize + 8.dp)
                .clip(RoundedCornerShape(Dimens.CornerMd))
                .clickable(onClick = onMicClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isListening) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = stringResource(
                    if (isListening) R.string.assistant_stop_cd else R.string.assistant_voice_cd
                ),
                tint = if (isListening) MaterialTheme.colorScheme.error
                       else Color(0xFF8B7FD8),
                modifier = Modifier.size(Dimens.InputBarIconSize),
            )
        }
    }
}
