package io.github.docmorphic.cmuxapp

/** Connection admission may be absent while account membership is restoring.
 * It controls feed workers, but must not erase an already authenticated push tap.
 */
internal class NativeNotificationMonitorState(
    signedIn: Boolean,
    visible: List<NativeCredentialStore.PairedMac>,
    allowsSaved: (NativeCredentialStore.PairedMac) -> Boolean
) {
    private val saved = if (signedIn) visible else emptyList()
    val retainedOrigins = saved.map { it.origin }.toSet()
    val workers = saved.filter { it.deviceId.isNotBlank() && allowsSaved(it) }.toSet()
}
