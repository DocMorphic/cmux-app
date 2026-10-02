package io.github.docmorphic.cmuxapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Stable IDs preserve Android's user-owned channel preferences across upgrades. */
internal object NativeOngoingNotifications {
    const val CONNECTION = "cmux_connection"
    const val REPLY = "cmux_reply_send"

    fun register(manager: NotificationManager, id: String, name: String) {
        // Android accepts this default only on first creation. Do not rotate or
        // delete existing IDs to override a user's badge/mute/sound preferences.
        manager.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        })
    }
}

internal fun nativeNotificationSettingsIntent(context: Context, channel: String? = null): Intent =
    Intent(if (channel == null) Settings.ACTION_APP_NOTIFICATION_SETTINGS else Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .apply { if (channel != null) putExtra(Settings.EXTRA_CHANNEL_ID, channel) }

@Composable
internal fun NativeNotificationSettings(openSettings: ((Intent) -> Unit)? = null) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val manager = remember(context) { context.getSystemService(NotificationManager::class.java) }
    var revision by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { revision++; failed = false }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val badgeChannels = remember(manager, revision) {
        listOf(NativeOngoingNotifications.CONNECTION to "Connection notification settings",
            NativeOngoingNotifications.REPLY to "Reply sending notification settings")
            .filter { (id, _) -> manager.getNotificationChannel(id)?.canShowBadge() == true }
    }
    fun open(channel: String?) {
        failed = false
        try {
            val intent = nativeNotificationSettingsIntent(context, channel)
            if (openSettings != null) openSettings(intent) else context.startActivity(intent)
        } catch (_: Exception) { failed = true }
    }
    Column {
        TextButton(onClick = { open(null) }, modifier = Modifier.padding(horizontal = 14.dp)) {
            Text("Android notification settings")
        }
        Text("Control sounds and app icon badges in Android settings.",
            Modifier.padding(horizontal = 22.dp), color = Color(0xFF92979F), fontSize = 12.sp)
        if (badgeChannels.isNotEmpty()) {
            Text("To keep ongoing activity from adding an app icon badge, turn off badges for these categories:",
                Modifier.padding(horizontal = 22.dp, vertical = 8.dp), color = Color(0xFF92979F), fontSize = 12.sp)
            badgeChannels.forEach { (id, label) ->
                TextButton(onClick = { open(id) }, modifier = Modifier.padding(horizontal = 14.dp)) { Text(label) }
            }
        }
        if (failed) Text("Could not open Android settings. Open Settings › Apps › cmux › Notifications on your phone.",
            Modifier.padding(horizontal = 22.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            color = Color(0xFFFF9999), fontSize = 13.sp)
    }
}
