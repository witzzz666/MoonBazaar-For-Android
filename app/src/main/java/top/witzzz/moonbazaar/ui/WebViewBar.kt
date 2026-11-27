package top.witzzz.moonbazaar.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 所有内嵌 WebView 共用的顶栏：退出 + 当前地址 + 刷新。
 */
@Composable
internal fun WebViewTopBar(
    title: String,
    loading: Boolean,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    closeText: String = "← 返回",
    background: Color = Color.Unspecified
) {
    Surface(tonalElevation = 3.dp, color = if (background == Color.Unspecified) MaterialTheme.colorScheme.surface else background) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onClose) { Text(closeText) }
            Spacer(Modifier.width(4.dp))
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            if (loading) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
            }
            TextButton(onClick = onRefresh) { Text("刷新") }
        }
    }
}
