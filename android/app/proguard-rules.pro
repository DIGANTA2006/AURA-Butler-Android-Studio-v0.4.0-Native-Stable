# AURA uses platform JSON parsing and no reflection-based model serialization.
-keepattributes SourceFile,LineNumberTable

# Google Tink references these compile-time/static-analysis annotations in
# class metadata. They are not required by AURA at runtime, but R8 treats the
# absent annotation classes as missing references during release shrinking.
# Keep this list narrow: do not replace it with a broad -dontwarn rule.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy
