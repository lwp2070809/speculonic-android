package de.lwp2070809.speculonic.ui.components

import android.content.Context
import android.widget.Toast
import de.lwp2070809.speculonic.R

fun Context.showOfflineRefreshToast() {
    Toast.makeText(this, R.string.refresh_offline_toast, Toast.LENGTH_SHORT).show()
}
