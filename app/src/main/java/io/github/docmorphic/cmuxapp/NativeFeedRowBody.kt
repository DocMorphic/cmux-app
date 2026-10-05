package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class NativeFeedVisual(val value: NativeFeedRowValue, val context: NativeFeedRowContext, val time: String)

@Composable
internal fun NativeFeedRowBody(model: NativeFeedVisual, modifier: Modifier = Modifier) {
    val value = model.value
    val context = model.context
    val row = value.presentation
    val hideHeadline = context.hideHeadline
    val hideSource = context.hideSource
    val hideComputer = context.hideComputer
    val connected = value.availability == NativeFeedAvailability.CONNECTED
    Column(modifier.padding(start = if (context.nested) 38.dp else 18.dp, end = 18.dp, top = 12.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(if (value.isRead) "  " else "●", Modifier.width(14.dp).clearAndSetSemantics { },
                        color = Color(0xFF76B9FF), fontSize = 9.sp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (hideHeadline) (if (!hideSource) row.source else null) ?: row.preview.orEmpty() else row.headline,
                                Modifier.weight(1f).padding(end = 8.dp),
                                fontWeight = if (!hideHeadline && !value.isRead) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = if (hideHeadline) 14.sp else 15.sp, maxLines = if (hideHeadline) 3 else 2,
                                overflow = TextOverflow.Ellipsis)
                            if (model.time.isNotEmpty()) Text(model.time, Modifier.widthIn(max = 105.dp),
                                color = Color(0xFF9B9FA8), fontSize = 11.sp, maxLines = 1)
                        }
                        if ((!hideSource && !hideHeadline && row.source != null) || !hideComputer) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                Row(Modifier.weight(1f).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (!hideSource && !hideHeadline) row.source?.let {
                                        Icon(painterResource(R.drawable.ic_feed_bell), null, Modifier.size(12.dp), tint = Color(0xFF9B9FA8))
                                        Spacer(Modifier.width(4.dp))
                                        Text(it, color = Color(0xFF9B9FA8), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                if (!hideComputer) Row(Modifier.widthIn(max = 150.dp), verticalAlignment = Alignment.CenterVertically) {
                                    val color = if (connected) Color(0xFF9B9FA8) else Color(0xFFFFB86C)
                                    Icon(painterResource(R.drawable.ic_feed_computer), null, Modifier.size(12.dp), tint = color)
                                    Spacer(Modifier.width(4.dp))
                                    Text(value.computer, color = color, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        if (!hideHeadline || (!hideSource && row.source != null)) row.preview?.let {
                            Text(it, color = Color(0xFF9B9FA8), fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
}
