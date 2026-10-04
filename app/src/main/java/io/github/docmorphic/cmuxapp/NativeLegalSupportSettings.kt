package io.github.docmorphic.cmuxapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.delay

internal enum class NativeSupportLink(val title: String, val address: String) {
    PRIVACY("Privacy Policy", "https://cmux.com/privacy-policy"),
    TERMS("Terms of Service", "https://cmux.com/terms-of-service"),
    SUPPORT("Support", "mailto:feedback@manaflow.com?subject=cmux%20Android%20support")
}

internal fun nativeSupportIntent(link: NativeSupportLink): Intent = Intent(
    if (link == NativeSupportLink.SUPPORT) Intent.ACTION_SENDTO else Intent.ACTION_VIEW,
    Uri.parse(link.address)
)

@Composable
internal fun NativeLegalSupportSettings(launch: ((Intent) -> Unit)? = null) {
    val context = LocalContext.current
    var failure by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp)) {
        Text("LEGAL & SUPPORT", style = MaterialTheme.typography.labelSmall)
        NativeSupportLink.entries.forEach { link ->
            TextButton(onClick = {
                failure = null
                try { (launch ?: { context.startActivity(it) })(nativeSupportIntent(link)) }
                catch (_: android.content.ActivityNotFoundException) {
                    failure = if (link == NativeSupportLink.SUPPORT) "No email app is available. Contact feedback@manaflow.com."
                        else "No browser is available. Open ${link.address} in a browser."
                } catch (_: SecurityException) { failure = "This device could not open ${link.title}." }
            }, modifier = Modifier.fillMaxWidth().testTag("settings.link.${link.name.lowercase()}")) {
                Icon(painterResource(when (link) {
                    NativeSupportLink.PRIVACY -> R.drawable.ic_workspace_lock
                    NativeSupportLink.TERMS -> R.drawable.ic_workspace_file_text
                    NativeSupportLink.SUPPORT -> R.drawable.ic_support_email
                }), null, Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(link.title, Modifier.weight(1f)); Text("↗", Modifier.clearAndSetSemantics { })
            }
        }
        Text("Policies and support for the cmux service.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        failure?.let { SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("settings.link.error")) } }
    }
}

@Composable
internal fun NativeAboutSettings(session: () -> NativeSupportSession, copy: ((ClipData) -> Unit)? = null) {
    val context = LocalContext.current
    val info = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0) }
    val version = info.versionName.orEmpty().ifEmpty { "0.0.0" }
    val build = PackageInfoCompat.getLongVersionCode(info).toString()
    val displayVersion = "$version ($build)" + if (BuildConfig.DEBUG && BuildConfig.SOURCE_REVISION.isNotBlank()) " · ${BuildConfig.SOURCE_REVISION}" else ""
    var copied by remember { mutableStateOf(false) }
    var copyGeneration by remember { mutableIntStateOf(0) }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copyGeneration) { if (copied) { delay(2_000); copied = false } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp)) {
        Text("ABOUT", style = MaterialTheme.typography.labelSmall)
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Text("Version", Modifier.weight(1f))
            SelectionContainer(Modifier.weight(2f)) { Text(displayVersion, Modifier.testTag("settings.version")) }
        }
        TextButton(onClick = {
            try {
                val current = session()
                val report = NativeSupportInformation(current.accountId, current.teamId, context.packageName,
                    BuildConfig.NOTICE_CHANNEL, version, build, Build.VERSION.RELEASE, Build.MODEL,
                    if (current.connected) "connected" else "disconnected", current.transport.takeIf { current.connected }, BuildConfig.SOURCE_REVISION.takeIf { it.isNotBlank() }).report()
                val clip = ClipData.newPlainText("cmux support information", report)
                clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                (copy ?: { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(it) })(clip)
                failure = null; copied = true; copyGeneration++
            } catch (_: RuntimeException) { copied = false; failure = "Could not copy support information. Try again." }
        }, modifier = Modifier.testTag("settings.support.copy")) { Text(if (copied) "Copied" else "Copy Support Information") }
        Text("Unofficial Android companion", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("settings.support.error")) }
    }
}
