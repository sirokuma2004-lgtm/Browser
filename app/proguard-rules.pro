# Hibari ProGuard / R8 rules

# ── WebView ──────────────────────────────────────────────────────────────────
# We do NOT expose addJavascriptInterface to web content (security invariant #1),
# but keep the annotation class itself so R8 doesn't complain.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ── OkHttp + DnsOverHttps ────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
# DnsOverHttps uses reflection to load the dns-over-https codec
-keep class okhttp3.dnsoverhttps.** { *; }

# ── Room ─────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-dontwarn androidx.room.**

# ── Kotlin Coroutines ────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ── DataStore (Preferences) ──────────────────────────────────────────────────
# Preferences DataStore does not use protobuf; the protobuf rule is not needed.
-keepclassmembers class androidx.datastore.** { *; }

# ── Compose ──────────────────────────────────────────────────────────────────
# R8 handles Compose well with the built-in rules; no extra rules needed here.

# ── General safety ───────────────────────────────────────────────────────────
# Preserve line-number information for crash reports (stack traces remain readable)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
