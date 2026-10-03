-keepclasseswithmembernames class * { native <methods>; }
# Called by cached JNI method ID, including in minified release builds.
-keep class io.github.docmorphic.cmuxapp.ghostty.GhosttyPngDecoder {
    public static byte[] decode(byte[]);
}
