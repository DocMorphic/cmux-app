package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Separate composition groups keep the main screen below the JVM method-size limit. */
@Composable
internal fun NativeSettingsLayout(onBack: () -> Unit, account: @Composable () -> Unit,
    computers: @Composable () -> Unit, notifications: @Composable () -> Unit,
    preferences: @Composable () -> Unit, connections: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().height(62.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹  Back") }
            Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        account(); computers(); notifications(); preferences(); connections()
    }
}
