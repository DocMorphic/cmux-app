package io.github.docmorphic.cmuxapp

/** Android-owned permanent IDs. Prepend future releases; never repurpose an acknowledged ID. */
internal object NativeWhatsNewCatalog {
    val pages = listOf(
        WhatsNewPage(
            id = "android.introduction.0.2.0", title = "Get started with cmux", releaseLabel = "Android 0.2.0",
            body = WhatsNewBody.Features(listOf(
                WhatsNewFeature("A guided introduction", "Walk through workspaces, notifications and connecting your Mac. You can replay the introduction from Settings."),
                WhatsNewFeature("Computers while offline", "Previously saved computers stay visible when discovery is unavailable."),
                WhatsNewFeature("Help for empty workspaces", "Find Mac setup guidance and retry loading your workspaces from an empty list.")
            )), minVersion = "0.2.0"
        ),
        WhatsNewPage(
            id = "android.pairing.iroh-v2", title = "Enable mobile pairing on your Mac",
            releaseLabel = "Mac setup", body = WhatsNewBody.Pairing, requiredPairing = true, minVersion = "0.2.0"
        )
    )
}
