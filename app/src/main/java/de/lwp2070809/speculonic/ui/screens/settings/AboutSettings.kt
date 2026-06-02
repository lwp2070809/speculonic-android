package de.lwp2070809.speculonic.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.ui.components.TopBarState


@Composable
fun AboutSettings(topBarState: TopBarState, onBackClick: () -> Unit) {
    val context = LocalContext.current
    val title = stringResource(R.string.about)
    val screenToken = remember { java.util.UUID.randomUUID().toString() }

    LaunchedEffect(Unit) {
        topBarState.update(
            title = title,
            actions = {},
            showSearch = false,
            showBack = true,
            token = screenToken
        )
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            topBarState.clear(screenToken)
        }
    }

    
    val versionName = remember {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                ).versionName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }
        } catch (e: Exception) {
            "1.0"
        } ?: "1.0"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp)
    ) {
        
        ListItem(
            headlineContent = { Text(stringResource(R.string.version)) },
            supportingContent = { Text(versionName) }
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        
        ListItem(
            headlineContent = { Text(stringResource(R.string.license)) },
            supportingContent = { Text("AGPL-3.0") }
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        
        ListItem(
            headlineContent = { Text(stringResource(R.string.github_repository)) },
            supportingContent = { Text("https://github.com/lwp2070809/speculonic-android") },
            modifier = Modifier.clickable {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/lwp2070809/speculonic-android"))
                    context.startActivity(intent)
                } catch (e: Exception) {
                    
                }
            }
        )
    }
}
