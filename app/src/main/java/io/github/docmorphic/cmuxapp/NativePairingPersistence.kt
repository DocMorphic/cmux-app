package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject

/** A computer owns a stable origin; adding or upgrading a route does not create a new draft/notification namespace. */
internal object NativePairingPersistence {
    fun remember(state: JSONObject, incoming: NativeCredentialStore.PairedMac,
                 team: NativeTeamScope? = null, expected: NativeCredentialStore.PairedMac? = null,
                 preferIncomingRoute: Boolean = false): NativeCredentialStore.PairedMac {
        NativeComputerVisibility.requireVisibleHandshake(state, incoming)
        // Runs inside the credential transaction: a Forget/replacement between
        // the handshake and this write must not recreate the captured pairing.
        if (expected != null) {
            val rows = state.optJSONArray("pairings")
            val saved = rows?.let { (0 until it.length()).mapNotNull { index ->
                it.optJSONObject(index)?.let(NativePairingRecords::decode)
            } }.orEmpty()
            check(NativeComputerMenuPairing.isCurrent(expected, saved)) {
                "This saved computer changed. Choose it again from Computers."
            }
        }
        if (team == null) return rememberUnscoped(state, incoming.copy(nativeRouteCode = null))
        check(state.optString("task_session") == team.login && state.optString("refresh_token").isNotBlank()) {
            "Account session changed. Reconnect to the Mac."
        }
        val pairing = PairingCodeParser.parse(incoming.code).getOrThrow()
        val grants = TailscaleGrantStore({ state }, { error("Read-only grant lookup") })
        var learnedGrant: TailscaleSavedGrant? = null
        when (pairing) {
            is PairingCode.Tailscale -> {
                require(pairing.stackUserId == null || pairing.stackUserId == team.userId) { "Computer account changed" }
                val grant = grants.find(team, TailscaleGrantStore.source(pairing))
                // A reconnect may retain its public locator after Details replaced that
                // locator's grant. Only a still-current, explicitly owned saved identity
                // can use the replacement; fresh pairing still requires its exact source.
                val savedIdentity = expected?.takeIf {
                    it.code == incoming.code && canonicalMacDeviceId(it.deviceId) == canonicalMacDeviceId(incoming.deviceId) &&
                        NativeComputerTarget.from(it, team)?.buildTag == incoming.instanceTag && it.accountUserId == team.userId &&
                        it.accountTeamId == team.teamId && it.stableOrigin != null
                }
                val retainedRoute = savedIdentity != null && NativeSavedTailscaleRoutes.candidates(savedIdentity, team, grants).isNotEmpty()
                if (expected != null && expected.instanceTag == null && !incoming.instanceTag.isNullOrBlank() &&
                    expected.code == incoming.code && canonicalMacDeviceId(expected.deviceId) == canonicalMacDeviceId(incoming.deviceId) &&
                    NativePairingRecords.owner(expected, grants) == (team.userId to team.teamId) &&
                    grant != null && grant.build == null && grant.device == canonicalMacDeviceId(incoming.deviceId)) {
                    val hinted = NativeComputerTarget.from(expected, team)
                    check(hinted == null || hinted.buildTag == incoming.instanceTag) { "Computer build changed" }
                    val build = incoming.instanceTag
                    require(build == build.trim() && build.length <= 64 && build.none(Char::isISOControl))
                    learnedGrant = grant.copy(build = build)
                }
                check(learnedGrant != null || retainedRoute || (grant != null && grant.device == canonicalMacDeviceId(incoming.deviceId) && grant.build == incoming.instanceTag)) {
                    "The Tailscale authorization changed. Pair this Mac again."
                }
            }
            is PairingCode.Iroh -> {
                require((pairing.userId == null || pairing.userId == team.userId) &&
                    (pairing.teamId == null || pairing.teamId == team.teamId)) { "Computer account or team changed" }
                require((pairing.macDeviceId == null || canonicalMacDeviceId(pairing.macDeviceId) == canonicalMacDeviceId(incoming.deviceId)) &&
                    (pairing.buildTag == null || pairing.buildTag == incoming.instanceTag)) { "Computer identity changed" }
            }
        }
        val previous = state.optJSONArray("pairings") ?: JSONArray()
        val decoded = (0 until previous.length()).mapNotNull { i ->
            previous.optJSONObject(i)?.let(NativePairingRecords::decode)?.let { i to it }
        }
        fun sameIdentity(row: NativeCredentialStore.PairedMac) =
            canonicalMacDeviceId(row.deviceId) == canonicalMacDeviceId(incoming.deviceId) && row.instanceTag == incoming.instanceTag
        val owner = team.userId to team.teamId
        val owned = decoded.filter { NativePairingRecords.owner(it.second, grants) == owner }
        val exactMatches = owned.filter { sameIdentity(it.second) }
        // A verified session can fill a missing build; discovery alone cannot.
        // An explicit captured row wins, otherwise only one owned legacy row may
        // donate history. Tagged sibling builds are never candidates for adoption.
        val legacyCandidates = if (incoming.instanceTag != null && (expected == null || expected.instanceTag == null))
            owned.filter { it.second.instanceTag == null && it.second.deviceId.isNotBlank() &&
                canonicalMacDeviceId(it.second.deviceId) == canonicalMacDeviceId(incoming.deviceId) }
        else emptyList()
        val legacy = if (expected != null && expected.instanceTag == null && incoming.instanceTag != null) {
            check(canonicalMacDeviceId(expected.deviceId) == canonicalMacDeviceId(incoming.deviceId)) { "Computer identity changed" }
            checkNotNull(legacyCandidates.singleOrNull { it.second.origin == expected.origin }) {
                "The older pairing has no unambiguous account ownership. Pair this Mac again."
            }
        } else {
            check(legacyCandidates.size <= 1) { "Choose the older saved computer before upgrading its build identity." }
            legacyCandidates.singleOrNull()
        }
        if (legacy != null) check(!NativeComputerVisibility.isHidden(state, legacy.second)) {
            "This computer is hidden on this phone. Show it in Computers before connecting."
        }
        check(owned.none { it != legacy && it.second.code == incoming.code && !sameIdentity(it.second) }) {
            "This pairing reaches a different Mac or installation."
        }
        val matches = exactMatches + listOfNotNull(legacy)
        // A fresh authenticated native reconnect supplies authority. A QR must not
        // choose arbitrarily between old native routes (these records have no dates).
        val nativeMatches = exactMatches.filter { PairingCodeParser.parse(it.second.code).getOrNull() is PairingCode.Iroh }
        val nativeCodes = (nativeMatches.map { it.second.code } + exactMatches.mapNotNull {
            it.second.nativeRouteCode?.takeIf { _ -> NativePairingRecords.retainedNativeRoute(it.second) != null }
        } + listOfNotNull(legacy?.second?.let { row -> row.nativeRouteCode?.takeIf {
            NativePairingRecords.retainedNativeRoute(row)?.buildTag == incoming.instanceTag
        } })).distinct()
        check(pairing !is PairingCode.Tailscale || nativeCodes.size <= 1) {
            "Reconnect this computer from Computers first, then add its Tailscale route."
        }
        val existing = matches.firstOrNull()
        if (pairing is PairingCode.Tailscale) {
            // Historical native hints without sufficient scope must not be erased by a QR scan.
            val ambiguous = decoded.any { (_, row) ->
                val code = PairingCodeParser.parse(row.code).getOrNull() as? PairingCode.Iroh
                code != null && sameIdentity(row) && incoming.instanceTag != null &&
                    (code.userId == null || code.userId == team.userId) && (code.teamId == null || code.teamId == team.teamId) &&
                    NativePairingRecords.owner(row, grants) == null
            }
            check(!ambiguous) { "Reconnect this native computer first, then add its route from Computer Details." }
        }
        val routeOwner = if (pairing is PairingCode.Tailscale && !preferIncomingRoute) nativeMatches.firstOrNull()?.second ?: incoming else incoming
        // The incoming handshake proves only its selected route. Never import an
        // alternative supplied by incoming data; retain it from owned saved rows.
        val retainedNative = if (PairingCodeParser.parse(routeOwner.code).getOrNull() is PairingCode.Tailscale)
            nativeCodes.singleOrNull() else null
        val scoped = NativePairingRecords.scoped(routeOwner.copy(nativeRouteCode = retainedNative), team, existing?.second?.origin)
        val aliases = matches.flatMap { it.second.origins }.toSet() - scoped.origin
        // An origin shared with an unresolved or differently scoped record cannot
        // become an alias: its old drafts/notifications have ambiguous ownership.
        val retained = aliases + scoped.origin
        check(decoded.none { it !in matches && it.second.origins.any(retained::contains) }) {
            "Saved computer history has conflicting ownership. Remove the conflicting pairing before reconnecting."
        }
        // A credential bound to the untagged identity must not survive as a
        // dangling revision after the build binding changes. Fresh tickets are
        // installed by the caller after this authenticated write.
        val incomingTicket = scoped.ticketRevision?.takeUnless { legacy != null && it == legacy.second.ticketRevision }
        val retainedTicket = incomingTicket ?: matches.singleOrNull { it.second.code == scoped.code && sameIdentity(it.second) }?.second?.ticketRevision
        val remembered = scoped.copy(previousOrigins = aliases, ticketRevision = retainedTicket)
        val next = JSONArray()
        val replaced = matches.map { it.first }.toSet()
        for (index in 0 until previous.length()) {
            if (index == existing?.first) next.put(NativePairingRecords.encode(remembered))
            else if (index !in replaced) next.put(previous.get(index))
        }
        if (existing == null) next.put(NativePairingRecords.encode(remembered))
        // Both changes commit inside the same encrypted credential transaction.
        // Keep the exact grant id/source/address; only authenticated build metadata changes.
        learnedGrant?.let { grant -> TailscaleGrantStore({ state }, { it(state) }).save(team, grant) { true } }
        state.put("pairings", next).put("pairing_code", remembered.code)
        NativeAttachTicketStore.prune(state)
        return remembered
    }

    /** Compatibility for fixtures/imports with no account authority. Production uses the scoped path above. */
    private fun rememberUnscoped(state: JSONObject, incoming: NativeCredentialStore.PairedMac): NativeCredentialStore.PairedMac {
        val previous = state.optJSONArray("pairings") ?: JSONArray()
        val next = JSONArray()
        for (index in 0 until previous.length()) {
            val item = previous.get(index)
            val existing = (item as? JSONObject)?.let(NativePairingRecords::decode)
            if (existing == null || (existing.code != incoming.code &&
                (incoming.deviceId.isBlank() || canonicalMacDeviceId(existing.deviceId) != canonicalMacDeviceId(incoming.deviceId) ||
                    existing.instanceTag != incoming.instanceTag))) next.put(item)
        }
        next.put(NativePairingRecords.encode(incoming))
        state.put("pairings", next).put("pairing_code", incoming.code)
        NativeAttachTicketStore.prune(state)
        return incoming
    }
}
