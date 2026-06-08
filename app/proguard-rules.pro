-keep class com.applocker.data.** { *; }
-keep class com.applocker.service.** { *; }
-keep class com.applocker.receiver.** { *; }

# javax.annotation classes referenced by Google Tink (used inside
# androidx.security.crypto) — they are compile-time only annotations
# and are not needed at runtime, so it's safe to suppress these warnings.
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**

# Tink internal classes
-dontwarn com.google.crypto.tink.**

# Keep Tink classes used by EncryptedSharedPreferences at runtime
-keep class com.google.crypto.tink.** { *; }
