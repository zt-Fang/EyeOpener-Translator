package io.github.ztfang.eye.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ztfang.eye.R
import io.github.ztfang.eye.domain.model.HistoryRecord
import io.github.ztfang.eye.domain.repository.HistoryRepository
import io.github.ztfang.eye.ui.theme.Dimens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun HistoryScreen(
    historyRepository: HistoryRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val records by historyRepository.getAllRecords().collectAsState(initial = emptyList())
    var filterFavorite by remember { mutableStateOf(false) }
    // 导出确认弹窗状态：null=不显示，非null=待导出的记录（单条）
    var pendingExportSingle by remember { mutableStateOf<HistoryRecord?>(null) }
    // 导出确认弹窗状态：是否待导出全部
    var pendingExportAll by remember { mutableStateOf(false) }

    val filteredRecords = if (filterFavorite) {
        records.filter { it.isFavorite }
    } else {
        records
    }

    BackHandler { onBack() }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // ========== 顶部标题栏（与其他设置页风格一致） ==========
        HistoryTopBar(
            onBack = onBack,
            onClearAll = {
                CoroutineScope(Dispatchers.IO).launch {
                    historyRepository.deleteAllRecords()
                }
            },
            recordCount = filteredRecords.size
        )

        // ========== 记录列表区（含筛选/导出工具栏） ==========
        if (filteredRecords.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.CopyAll,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                    Spacer(modifier = Modifier.height(Dimens.SpaceMd))
                    Text(
                        text = stringResource(R.string.history_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = Dimens.ScreenPaddingH,
                    vertical = Dimens.SpaceSm
                )
            ) {
                // 筛选/导出工具栏放在记录区第一条
                item {
                    HistoryToolbar(
                        filterFavorite = filterFavorite,
                        onFilterChange = { filterFavorite = it },
                        onExportAll = { pendingExportAll = true },
                        recordCount = filteredRecords.size
                    )
                    Spacer(modifier = Modifier.height(Dimens.SpaceSm))
                }

                items(filteredRecords) { record ->
                    HistoryItem(
                        record = record,
                        onFavorite = {
                            CoroutineScope(Dispatchers.IO).launch {
                                historyRepository.updateRecord(record.copy(isFavorite = !record.isFavorite))
                            }
                        },
                        onCopy = {
                            copyToClipboard(context, record.sourceText)
                        },
                        onExport = {
                            pendingExportSingle = record
                        },
                        onDelete = {
                            CoroutineScope(Dispatchers.IO).launch {
                                historyRepository.deleteRecord(record)
                            }
                        }
                    )
                }
            }
        }
    }

    // 导出确认弹窗 — 单条记录
    pendingExportSingle?.let { record ->
        val fileName = "translation_${record.timestamp}.txt"
        val exportDir = context.getExternalFilesDir(null)
        AlertDialog(
            onDismissRequest = { pendingExportSingle = null },
            title = { Text("Export Record") },
            text = { Text("Export to:\n${exportDir?.absolutePath}/$fileName") },
            confirmButton = {
                TextButton(onClick = {
                    exportRecord(context, record)
                    pendingExportSingle = null
                }) { Text("Export") }
            },
            dismissButton = {
                TextButton(onClick = { pendingExportSingle = null }) { Text("Cancel") }
            }
        )
    }

    // 导出确认弹窗 — 全部记录
    if (pendingExportAll) {
        val fileName = "all_translations_${System.currentTimeMillis()}.txt"
        val exportDir = context.getExternalFilesDir(null)
        AlertDialog(
            onDismissRequest = { pendingExportAll = false },
            title = { Text("Export All Records") },
            text = { Text("Export ${filteredRecords.size} records to:\n${exportDir?.absolutePath}/$fileName") },
            confirmButton = {
                TextButton(onClick = {
                    exportAllRecords(context, filteredRecords)
                    pendingExportAll = false
                }) { Text("Export") }
            },
            dismissButton = {
                TextButton(onClick = { pendingExportAll = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * 历史记录页顶部栏：返回按钮 + 标题 + 清除全部按钮。
 * 与个性化设置页等其他设置页风格一致。
 */
@Composable
private fun HistoryTopBar(onBack: () -> Unit, onClearAll: () -> Unit, recordCount: Int) {
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
                contentDescription = stringResource(R.string.history_back_cd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(Dimens.SpaceSm))
        Text(
            text = stringResource(R.string.history_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        if (recordCount > 0) {
            Box(
                modifier = Modifier
                    .size(Dimens.TopAppBarIconBox)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
                    .clickable(onClick = onClearAll),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Clear all",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/**
 * 历史记录工具栏：全部/收藏筛选 + 导出全部按钮。
 * 放在记录列表区内，直接使用页面背景，不使用白板卡片。
 */
@Composable
private fun HistoryToolbar(
    filterFavorite: Boolean,
    onFilterChange: (Boolean) -> Unit,
    onExportAll: () -> Unit,
    recordCount: Int
) {
    Column(modifier = Modifier.fillMaxWidth().padding(Dimens.SpaceSm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$recordCount records",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(Dimens.SpaceSm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { onFilterChange(false) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (!filterFavorite)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    stringResource(R.string.history_filter_all),
                    color = if (!filterFavorite)
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                )
            }
            Button(
                onClick = { onFilterChange(true) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (filterFavorite)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    stringResource(R.string.history_filter_favorite),
                    color = if (filterFavorite)
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                )
            }
            Button(
                onClick = onExportAll,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(stringResource(R.string.history_export), color = MaterialTheme.colorScheme.onPrimary, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun HistoryItem(
    record: HistoryRecord,
    onFavorite: () -> Unit,
    onCopy: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(Dimens.SpaceMd)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatTime(record.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = onFavorite,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        if (record.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = stringResource(R.string.history_favorite),
                        modifier = Modifier.size(18.dp),
                        tint = if (record.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(Dimens.SpaceSm))

            // 原文
            Text(
                text = record.sourceText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            // 译文
            if (record.translatedText.isNotBlank()) {
                Spacer(modifier = Modifier.height(Dimens.SpaceSm))
                Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
                Spacer(modifier = Modifier.height(Dimens.SpaceSm))

                Text(
                    text = record.translatedText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(Dimens.SpaceSm))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.CopyAll, contentDescription = stringResource(R.string.history_copy), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onExport, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.history_export), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.history_delete), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return sdf.format(timestamp)
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("translation", text))
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}

private fun exportRecord(context: Context, record: HistoryRecord) {
    val text = """Source: ${record.sourceText}
Translation: ${record.translatedText}
Time: ${formatTime(record.timestamp)}
"""
    val fileName = "translation_${record.timestamp}.txt"
    val file = File(context.getExternalFilesDir(null), fileName)
    FileOutputStream(file).use { it.write(text.toByteArray()) }
    Toast.makeText(context, "Exported: $fileName", Toast.LENGTH_LONG).show()

    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.fromFile(file), "text/plain")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Open file"))
}

private fun exportAllRecords(context: Context, records: List<HistoryRecord>) {
    val sb = StringBuilder()
    records.forEachIndexed { index, record ->
        sb.append("=== Record ${index + 1} ===\n")
        sb.append("Source: ${record.sourceText}\n")
        sb.append("Translation: ${record.translatedText}\n")
        sb.append("Time: ${formatTime(record.timestamp)}\n")
        sb.append("Favorite: ${if (record.isFavorite) "Yes" else "No"}\n")
        sb.append("\n")
    }
    val fileName = "all_translations_${System.currentTimeMillis()}.txt"
    val file = File(context.getExternalFilesDir(null), fileName)
    FileOutputStream(file).use { it.write(sb.toString().toByteArray()) }
    Toast.makeText(context, "Exported ${records.size} records: $fileName", Toast.LENGTH_LONG).show()

    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.fromFile(file), "text/plain")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Open file"))
}