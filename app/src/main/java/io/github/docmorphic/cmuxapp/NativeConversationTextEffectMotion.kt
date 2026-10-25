package io.github.docmorphic.cmuxapp

import kotlin.math.*

/** Original per-unit poses in font-size units, with top-left clockwise rotation in radians.
 * The renderer owns looping, glyph/word segmentation, frame interpolation and reduced motion. */
internal object NativeConversationTextEffectMotion {
    data class Pose(val dx: Double = 0.0, val dy: Double = 0.0, val rotation: Double = 0.0,
        val scale: Double = 1.0, val opacity: Double = 1.0)
    const val CYCLE = 3.0
    fun fontScale(effect: NativeConversationTextEffect) = when (effect) {
        NativeConversationTextEffect.BIG -> 1.6
        NativeConversationTextEffect.SMALL -> .7
        else -> 1.0
    }
    fun delay(effect: NativeConversationTextEffect, index: Int, count: Int): Double {
        val n = count.coerceAtLeast(1)
        fun spread(perUnit: Double, cap: Double) = min(perUnit, cap / n) * index
        return when (effect) {
            NativeConversationTextEffect.RIPPLE -> spread(.045, 1.1)
            NativeConversationTextEffect.NOD -> spread(.03, .6)
            NativeConversationTextEffect.BIG, NativeConversationTextEffect.SMALL -> spread(.02, .4)
            NativeConversationTextEffect.BLOOM -> min(.035, .7 / n) * abs(index - (n - 1) / 2.0)
            else -> 0.0
        }
    }
    fun duration(effect: NativeConversationTextEffect) = when (effect) {
        NativeConversationTextEffect.SHAKE -> .7
        NativeConversationTextEffect.NOD -> .9
        NativeConversationTextEffect.EXPLODE -> 1.1
        NativeConversationTextEffect.RIPPLE -> .5
        NativeConversationTextEffect.BLOOM -> .8
        NativeConversationTextEffect.JITTER -> 1.2
        NativeConversationTextEffect.BIG, NativeConversationTextEffect.SMALL -> .55
    }
    fun pose(effect: NativeConversationTextEffect, index: Int, count: Int, time: Double, seed: ULong = 0uL): Pose {
        val local = time - delay(effect, index, count)
        val duration = duration(effect)
        if (local < 0 || local > duration) return Pose()
        val p = local / duration
        return when (effect) {
            NativeConversationTextEffect.SHAKE -> Pose(dx = .16 * sin(2 * PI * 6 * local) * (1 - p))
            NativeConversationTextEffect.NOD -> {
                val wave = sin(2 * PI * 2 * p) * (1 - p)
                Pose(dy = .14 * wave, rotation = .10 * wave)
            }
            NativeConversationTextEffect.EXPLODE -> {
                val angle = random(seed, index, 1) * 2 * PI
                val distance = .9 + .8 * random(seed, index, 2)
                val spin = (random(seed, index, 3) - .5) * 1.6
                val out = if (p < .35) easeOut(p / .35) else 1 - easeInOut((p - .35) / .65)
                Pose(cos(angle) * distance * out, sin(angle) * distance * out, spin * out, 1 + .35 * out, 1 - .35 * out)
            }
            NativeConversationTextEffect.RIPPLE -> {
                val bump = sin(PI * p)
                Pose(dy = -.32 * bump, scale = 1 + .22 * bump)
            }
            NativeConversationTextEffect.BLOOM -> Pose(scale = .25 + .75 * spring(p), opacity = min(1.0, p / .35))
            NativeConversationTextEffect.JITTER -> {
                val step = (local * 24).toInt()
                val fade = if (p > .8) (1 - p) / .2 else 1.0
                Pose((random(seed, index, 10 + 2 * step) - .5) * .14 * fade,
                    (random(seed, index, 11 + 2 * step) - .5) * .14 * fade,
                    (random(seed, index, 1000 + step) - .5) * .12 * fade)
            }
            NativeConversationTextEffect.BIG -> Pose(scale = .45 + .55 * spring(p))
            NativeConversationTextEffect.SMALL -> Pose(scale = 1.6 - .6 * spring(p))
        }
    }
    private fun random(seed: ULong, index: Int, salt: Int): Double {
        var x = seed + index.toULong() * 0x9E3779B97F4A7C15uL + salt.toULong() * 0xBF58476D1CE4E5B9uL
        x = (x xor (x shr 30)) * 0xBF58476D1CE4E5B9uL
        x = (x xor (x shr 27)) * 0x94D049BB133111EBuL
        x = x xor (x shr 31)
        return (x shr 11).toDouble() / 9007199254740992.0
    }
    private fun easeOut(x: Double) = 1 - (1 - x).pow(3)
    private fun easeInOut(x: Double) = if (x < .5) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2
    private fun spring(x: Double) = if (x >= 1) 1.0 else 1 - exp(-6 * x) * cos(2.4 * PI * x) * (1 - x)

    data class TransformFrame(val time: Double, val dx: Double, val dy: Double, val rotation: Double, val scale: Double)
    data class OpacityFrame(val time: Double, val opacity: Double)
    data class Keyframes(val transforms: List<TransformFrame>, val opacities: List<OpacityFrame>)

    /** Original shared renderer samples 60 Hz, retaining each edge of an equal-pose interval.
     * A renderer must use these tracks rather than evaluate nonlinear poses directly per frame. */
    fun keyframes(effect: NativeConversationTextEffect, index: Int, count: Int, fontSize: Double, seed: ULong = 0uL): Keyframes {
        val frames = (CYCLE * 60).toInt()
        val poses = (0..frames).map { pose(effect, index, count, it / 60.0, seed) }
        fun kept(same: (Pose, Pose) -> Boolean) = (0..frames).filter { i ->
            i == 0 || i == frames || !same(poses[i], poses[i - 1]) || !same(poses[i], poses[i + 1])
        }
        val transforms = kept { a, b -> a.dx == b.dx && a.dy == b.dy && a.rotation == b.rotation && a.scale == b.scale }.map { i ->
            val pose = poses[i]
            TransformFrame(i / 60.0 / CYCLE, pose.dx * fontSize, pose.dy * fontSize, pose.rotation, pose.scale)
        }
        val opacities = if (poses.any { it.opacity != 1.0 }) kept { a, b -> a.opacity == b.opacity }.map { i ->
            OpacityFrame(i / 60.0 / CYCLE, poses[i].opacity)
        } else emptyList()
        return Keyframes(transforms, opacities)
    }

    fun seed(text: String): ULong = text.toByteArray(Charsets.UTF_8).fold(0xCBF29CE484222325uL) { hash, byte ->
        (hash xor byte.toUByte().toULong()) * 0x100000001B3uL
    }
}
