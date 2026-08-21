# R8 rules for release builds.
#
# The whole risk here is Gson. Every message on the wire is serialized by reflecting over field
# names, so a field R8 renames to `a` becomes a JSON key called "a" - and the failure is silent:
# the app builds, launches, hosts a table, and then nothing a guest sends can be understood. Unit
# tests never see it, because they run on the JVM against unminified classes.
#
# So the model package is kept whole. It is small, it is the wire format, and shrinking it buys
# almost nothing against the cost of getting this wrong.

# ── Gson itself ────────────────────────────────────────────────────────────────
# Signature is needed for generic types (Map<String, Int> in StateUpdate, List<Card> everywhere) -
# without it Gson cannot recover the element type and falls back to LinkedTreeMap.
# Annotations are kept because Gson reads @SerializedName, and the enum rules below rely on the
# constant names surviving.
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

-dontwarn sun.misc.**
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# ── The wire format ────────────────────────────────────────────────────────────
# Field names ARE the protocol. NetworkMessage's subclasses, PlayerInfo, Card, GameSettings,
# GameRules, HandTransfer and the enums they carry all cross the socket.
-keep class com.mutsho.localuno.model.** { *; }

# Gson serializes an enum by its constant name, so a renamed constant changes the wire value.
# GamePhase, CardColor, CardType, Direction, GameMode and TimeoutAction all travel.
-keepclassmembers enum com.mutsho.localuno.model.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    *;
}

# The envelope every message is wrapped in. Private and nested, which makes it exactly the kind of
# class R8 renames without hesitation - and its two fields, `type` and `payload`, are what the
# receiving end reads first.
-keep class com.mutsho.localuno.network.MessageSerializer$MessageWrapper { *; }

# ── Crash reports ──────────────────────────────────────────────────────────────
# The app reports its own crashes (see CrashReporter), and the slot-table bug that cost weeks was
# diagnosed from a stack trace. A minified trace with no line numbers would have made that
# impossible. Keep the file and line attributes and rewrite the source file name, which is the
# standard pairing - retrace can still de-obfuscate using mapping.txt.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Kotlin ─────────────────────────────────────────────────────────────────────
# Coroutine internals that R8 warns about but that are never actually referenced on Android.
-dontwarn kotlinx.coroutines.**
-dontwarn org.jetbrains.annotations.**
