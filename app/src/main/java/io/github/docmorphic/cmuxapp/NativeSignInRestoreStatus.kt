package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Original10-second warning and retry-generation timer; the account owns validation. */
@Composable internal fun NativeSignInRestoreStatus(revalidate: () -> Unit) {
    var warned by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(retry) { warned=false;delay(10_000);warned=true }
    Column(Modifier.fillMaxWidth().testTag("signin.restoreStatus").semantics { liveRegion=LiveRegionMode.Polite },
        verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if (warned) Icon(painterResource(R.drawable.ic_signin_restore_warning),contentDescription=null,
                tint=Color(0xFFFF9F0A),modifier=Modifier.size(16.dp))
            else CircularProgressIndicator(Modifier.size(16.dp),color=signInMuted(),strokeWidth=2.dp)
            Text(stringResource(if(warned)R.string.cmux_signin_restore_timeout_title else R.string.cmux_signin_restore_title),
                fontSize=15.sp,lineHeight=20.sp,fontWeight=FontWeight.SemiBold,color=signInInk())
        }
        Text(stringResource(if(warned)R.string.cmux_signin_restore_timeout_message else R.string.cmux_signin_restore_message),
            fontSize=12.sp,lineHeight=16.sp,color=signInMuted())
        if(warned) OutlinedButton({ warned=false;retry++;revalidate() },modifier=Modifier.testTag("signin.restoreRetry")) {
            Text(stringResource(R.string.cmux_signin_restore_retry),fontSize=15.sp)
        }
    }
}
