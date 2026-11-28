package top.wkbin.zaomeng.feature.importbook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import io.github.suqi8.coui.kmp.basic.CircularProgressIndicator
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.theme.COUITheme

@Composable
fun NovelCharacterDiscovery(
    busy: Boolean,
    error: String,
    message: String,
    enabled: Boolean,
    onDiscover: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
                Text("正在识别主要人物…", style = COUITheme.textStyles.bodyM)
            } else {
                TextButton(text = "识别小说人物", onClick = onDiscover, enabled = enabled, minWidth = 0.dp)
            }
        }
        if (error.isNotBlank()) {
            Text(error, style = COUITheme.textStyles.bodyXS, color = COUITheme.colorScheme.error)
        } else if (message.isNotBlank()) {
            Text(message, style = COUITheme.textStyles.bodyXS, color = COUITheme.colorScheme.labelSecondary)
        }
    }
}
