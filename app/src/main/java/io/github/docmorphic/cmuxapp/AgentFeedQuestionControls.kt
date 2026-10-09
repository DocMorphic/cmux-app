/* Question preview follows cmux AgentFeedQuestionControls.swift at
 * f4b1509054949eaad5d695569ad443c4c18ed68d. GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val questionAccent = Color(0xFF76B9FF)
internal val questionMuted = Color(0xFF9CA3AF)

@Composable
internal fun AgentFeedQuestionControls(item: NativeAgentFeedItem, enabled: Boolean, onAnswer: () -> Unit) {
    val first = item.questions.firstOrNull() ?: return
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().alpha(if (enabled) 1f else .55f).padding(top = 4.dp).background(Color(0xFF191C21), shape)
        .border(1.dp, questionAccent.copy(alpha = .24f), shape).padding(14.dp).testTag("AgentFeedQuestions"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Needs your input", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(if (item.questions.size == 1) "1 question" else "${item.questions.size} questions", fontSize = 12.sp, color = questionMuted)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            first.header?.takeIf(String::isNotBlank)?.let { Text(it, fontSize = 12.sp, color = questionMuted) }
            ProvideTextStyle(LocalTextStyle.current.copy(fontWeight = FontWeight.SemiBold)) {
                AgentFeedMarkdownText(first.prompt, lineLimit = 4, fontSize = 16)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            if (first.multiSelect) {
                Checkbox(true, null, modifier = Modifier.padding(end = 6.dp).size(16.dp),
                    colors = CheckboxDefaults.colors(checkedColor = questionMuted, checkmarkColor = Color(0xFF191C21)))
            }
            Text(if (first.multiSelect) "Select all that apply" else "Choose one", color = questionMuted,
                fontSize = 12.sp, modifier = Modifier.weight(1f))
            Box(Modifier.heightIn(min = 44.dp).widthIn(min = 84.dp)
                .clickable(enabled = enabled, role = Role.Button, onClick = onAnswer)
                .testTag("AgentFeedQuestionAnswer"), contentAlignment = Alignment.Center) {
                val capsule = RoundedCornerShape(50)
                Row(Modifier.height(32.dp).background(questionAccent.copy(alpha = .14f), capsule)
                    .border(1.dp, questionAccent.copy(alpha = .3f), capsule).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Answer", color = questionAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp).rotate(-45f), tint = questionAccent)
                }
            }
        }
    }
}
