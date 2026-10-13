# BouncyCastle is looked up by name (JCA providers, algorithm classes): keep all of it.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
# OkHttp's optional platform integrations.
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
