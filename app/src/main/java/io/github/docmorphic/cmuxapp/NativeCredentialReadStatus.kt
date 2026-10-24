package io.github.docmorphic.cmuxapp

/** Read availability, not an authenticated-session or empty-token verdict. */
internal enum class NativeCredentialReadStatus { UNCHECKED, EMPTY, AVAILABLE, UNAVAILABLE }

/** Sanitized: crypto/parser causes may include plaintext and must not escape. */
internal class NativeCredentialReadException : IllegalStateException("Saved account storage could not be read. Try again.")
