package de.lwp2070809.speculonic.ui.screens.player.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.lwp2070809.speculonic.R

/**
 * 正在播放页面的通用无状态顶部工具栏。
 * 包含下拉折叠箭头、播放来源/队列标题、播放队列列表入口按钮。
 * 统一采用 primary 主题色，支持在手机竖屏与平板双栏模式下复用。
 */
@Composable
fun NowPlayingTopBar(
    title: String?,
    onCollapse: () -> Unit,
    onShowQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(onClick = onCollapse) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(R.string.close),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            text = title ?: stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(horizontal = 8.dp)
        )
        IconButton(onClick = onShowQueue) {
            Icon(
                painterResource(id = R.drawable.ic_symbol_playlist_play),
                contentDescription = stringResource(R.string.queue),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
