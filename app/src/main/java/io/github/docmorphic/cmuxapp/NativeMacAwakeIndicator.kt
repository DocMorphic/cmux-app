package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

@Composable
internal fun NativeMacAwakeIndicator(connection: NativeComputerConnection) {
    if (connection.availability == NativeFeedAvailability.CONNECTED && connection.keepAwake == true) {
        Icon(painterResource(R.drawable.ic_keep_awake), contentDescription = "Keeping Mac awake",
            tint = Color(0xFFFFAA55), modifier = Modifier.padding(horizontal = 6.dp).size(18.dp))
    }
}
