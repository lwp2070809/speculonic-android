package de.lwp2070809.speculonic.ui.screens.download

import androidx.lifecycle.ViewModel
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.lifecycle.HiltViewModel
import de.lwp2070809.speculonic.data.DownloadTaskInfo
import de.lwp2070809.speculonic.data.DownloadTracker
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
@androidx.annotation.OptIn(UnstableApi::class)
class DownloadManagerViewModel @Inject constructor(
    downloadTracker: DownloadTracker
) : ViewModel() {
    val allDownloadsFlow: StateFlow<List<DownloadTaskInfo>> = downloadTracker.allDownloadsFlow
}
