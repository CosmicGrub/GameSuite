# GameSuite R8/ProGuard rules.
#
# Referenced by app/build.gradle.kts's release buildType (alongside
# proguard-android-optimize.txt) but did not exist as a file until now —
# minification is currently off (`isMinifyEnabled = false`), so nothing
# exercises these rules yet, but the first release build with minification
# turned on needs them in place or it will break at runtime in ways a debug
# build never surfaces (reflection-based (de)serialization silently getting
# obfuscated fields, OkHttp's platform-detection failing, or Nearby
# Connections' Play Services internals getting stripped).

# ---------------------------------------------------------------------------
# kotlinx.serialization
# ---------------------------------------------------------------------------
# GameSuite's networked game state (UnoState, UnoNetMessage/UnoIntentPayload,
# the Nearby/Online lobby protocols, PlayerInfo, and OnlineTransport's wire
# envelope) is all @Serializable and (de)serialized to/from JSON via
# kotlinx.serialization. The library resolves serializers for these types at
# runtime by looking up a generated `Companion.serializer()` method (and, for
# sealed classes like UnoNetMessage/UnoIntentPayload/NearbyLobbyMessage/
# OnlineLobbyMessage, a generated `$serializer` class per subtype for
# polymorphic dispatch) — R8 doesn't see any of that as a normal call site, so
# without these rules it renames/strips the very symbols serialization needs
# at runtime. This is the rule set kotlinx.serialization publishes for
# consumers (https://github.com/Kotlin/kotlinx.serialization#android), kept
# generic (matching every @Serializable type) rather than hand-listing each
# class, so it stays correct as new @Serializable types are added.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `Companion` object of serializable classes.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <1>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `serializer()` on companion objects of serializable objects (data
# object / plain object), e.g. UnoNetMessage.RequestState.
-if @kotlinx.serialization.Serializable class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class <1> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep the fields of every @Serializable class (UnoState, UnoCard, UnoColor,
# UnoRank, UnoPlayerState, UnoNetMessage/UnoIntentPayload and their
# subclasses, NearbyLobbyMessage/OnlineLobbyMessage and their subclasses,
# PlayerInfo, OnlineTransport's private WireMessage/WirePlayer) so field
# names survive to match the wire JSON on both ends.
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    <fields>;
}

-keepclasseswithmembers class kotlinx.serialization.internal.**
-dontnote kotlinx.serialization.internal.ClassValueParametrizedCacheKt

# ---------------------------------------------------------------------------
# OkHttp (OnlineTransport's WebSocket client)
# ---------------------------------------------------------------------------
# Standard rules OkHttp/Okio publish for consumers
# (https://square.github.io/okhttp/features/r8_proguard/) — mostly silencing
# warnings for optional/platform-specific code paths this app never
# exercises (Conscrypt/BouncyCastle/OpenJSSE providers, Java 9 modules), plus
# keeping the one resource OkHttp loads by relative path.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-adaptresourcefilenames okhttp3/internal/publicsuffix/PublicSuffixDatabase.gz

# ---------------------------------------------------------------------------
# Google Play Services Nearby Connections
# ---------------------------------------------------------------------------
# NearbyConnectionsTransport drives com.google.android.gms.nearby.connection.*
# purely through its public API, but the library's internal implementation
# (com.google.android.gms.internal.nearby.*) relies on reflection/Parcelable
# creators that R8 can't trace from GameSuite's code — standard Play Services
# guidance is to keep the whole api surface + internal package rather than
# try to enumerate individual reflectively-used classes.
-keep class com.google.android.gms.nearby.** { *; }
-keep interface com.google.android.gms.nearby.** { *; }
-keep class com.google.android.gms.internal.nearby.** { *; }
-dontwarn com.google.android.gms.internal.nearby.**

# ---------------------------------------------------------------------------
# GameSuite's own serializable model types
# ---------------------------------------------------------------------------
# Belt-and-suspenders on top of the generic kotlinx.serialization rules
# above: keep names/members for the actual networked model types by package,
# in case a future refactor narrows the generic rules and someone forgets
# these are the load-bearing types.
-keep class com.gamesuite.core.PlayerInfo { *; }
-keep class com.gamesuite.games.uno.UnoState { *; }
-keep class com.gamesuite.games.uno.UnoPlayerState { *; }
-keep class com.gamesuite.games.uno.UnoCard { *; }
-keep class com.gamesuite.games.uno.UnoColor { *; }
-keep class com.gamesuite.games.uno.UnoRank { *; }
-keep class com.gamesuite.games.uno.UnoNetMessage$* { *; }
-keep class com.gamesuite.games.uno.UnoIntentPayload$* { *; }
-keep class com.gamesuite.transport.NearbyLobbyMessage$* { *; }
-keep class com.gamesuite.transport.OnlineLobbyMessage$* { *; }
