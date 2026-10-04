package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

@Composable
internal fun WorkspaceActionIcon(resource: Int) {
    Icon(painterResource(resource), null, Modifier.size(20.dp))
}
