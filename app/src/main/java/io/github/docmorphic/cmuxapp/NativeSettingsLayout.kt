package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Separate composition groups keep the main screen below the JVM method-size limit. */
@Composable
internal fun NativeSettingsLayout(onBack: () -> Unit, account: @Composable () -> Unit,
    computers: @Composable () -> Unit, notifications: @Composable () -> Unit,
    preferences: @Composable () -> Unit, connections: @Composable () -> Unit,
    footer: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).padding(horizontal = 14.dp)
            .testTag("settings.header"), verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", Modifier.weight(1f).semantics { heading() }, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onBack, modifier = Modifier.testTag("settings.done")) { Text("Done") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("settings.content")) {
            account(); computers(); connections(); preferences(); notifications(); footer()
        }
    }
}
