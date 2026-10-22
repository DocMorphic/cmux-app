package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Native MarkdownWebTheme's contrast overlays, resolved against the Android viewer surface. */
internal data class MarkdownWebTheme(val dark: Boolean, val muted: String, val neutral: String,
    val border: String, val mutedBorder: String) {
    companion object {
        fun resolve(dark: Boolean, background: Int): MarkdownWebTheme {
            val base = listOf(16, 8, 0).map { ((background ushr it) and 255) / 255.0 }
            val overlay = if (dark) 1.0 else 0.0
            fun luminance(rgb: List<Double>) = rgb.map { if (it <= .04045) it / 12.92 else ((it + .055) / 1.055).pow(2.4) }
                .let { .2126 * it[0] + .7152 * it[1] + .0722 * it[2] }
            val baseline = luminance(base)
            fun alpha(contrast: Double): Double {
                var low = 0.0; var high = 1.0; var result = 1.0
                repeat(18) {
                    val mid = (low + high) / 2
                    val candidate = luminance(base.map { it + (overlay - it) * mid })
                    if ((max(candidate, baseline) + .05) / (min(candidate, baseline) + .05) < contrast) low = mid
                    else { high = mid; result = mid }
                }
                return result
            }
            fun css(alpha: Double): String {
                val channel = if (dark) 255 else 0
                return String.format(Locale.ROOT, "rgba(%d, %d, %d, %.3f)", channel, channel, channel, alpha)
            }
            val border = alpha(if (dark) 1.92 else 1.43)
            return MarkdownWebTheme(dark, css(alpha(if (dark) 1.09 else 1.06)),
                css(alpha(if (dark) 1.35 else 1.20)), css(border), css(border * .70))
        }
    }

    fun renderScript(markdown: String): String {
        val vars = JSONObject().put("--bgColor-default", "transparent").put("--bgColor-muted", muted)
            .put("--bgColor-neutral-muted", neutral).put("--borderColor-default", border)
            .put("--borderColor-muted", mutedBorder).put("--borderColor-neutral-muted", mutedBorder)
        // Configuration reaches Chromium asynchronously. Apply and re-render only once its real
        // media query agrees; never spoof matchMedia, reload the shell or reset image approvals.
        return """
            (() => {
              if (window.__cmuxAndroidPendingTheme) window.__cmuxAndroidPendingTheme();
              const mql = matchMedia('(prefers-color-scheme: dark)');
              const cleanup = () => mql.removeEventListener('change', apply);
              function apply() {
                if (mql.matches !== $dark) return;
                cleanup(); window.__cmuxAndroidPendingTheme = null;
                const content = document.getElementById('content');
                const vars = $vars;
                Object.keys(vars).forEach(name => content.style.setProperty(name, vars[name]));
                content.style.background = 'transparent';
                window.__cmuxApplyTheme();
                ${MarkdownPreviewPolicy.renderScript(markdown)}
                CmuxMarkdownBridge.postMessage('{"action":"markdownRendered"}');
              }
              window.__cmuxAndroidPendingTheme = cleanup;
              mql.addEventListener('change', apply); apply(); return true;
            })()
        """.trimIndent()
    }
}
