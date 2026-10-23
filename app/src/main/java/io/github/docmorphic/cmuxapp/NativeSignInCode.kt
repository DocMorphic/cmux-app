package io.github.docmorphic.cmuxapp

/** Original SignInCodeInputPolicy: ASCII only, uppercase, at most six characters. */
internal fun nativeSignInCode(value: String): String = buildString(6) {
    for (character in value) {
        if (length == 6) break
        when (character) {
            in '0'..'9', in 'A'..'Z' -> append(character)
            in 'a'..'z' -> append(character.uppercaseChar())
        }
    }
}
