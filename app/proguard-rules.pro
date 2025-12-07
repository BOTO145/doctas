# Add project specific ProGuard rules here.
-keep,includedescriptorclasses class com.andlab.doctas.** { *; }
-keepnames class com.andlab.doctas.**

-dontwarn java.lang.management.**
-dontwarn org.slf4j.impl.StaticLoggerBinder

# Keep rules for Ktor
-keep class io.ktor.** { *; }
-keepnames class io.ktor.**

# Keep rules for Kotlinx Serialization
-keep class kotlinx.serialization.** { *; }
-keepnames class kotlinx.serialization.**
-keep,includedescriptorclasses class kotlinx.serialization.internal.** { *; }

# Keep rules for Coroutines
-keepnames class kotlinx.coroutines.internal.** { *; }

# Preserve annotations, metadata, and inner classes
-keepattributes *Annotation*,Signature,EnclosingMethod,InnerClasses,Kotlin

# Keep serializers
-keepclassmembers,allowshrinking,allowobfuscation class * extends kotlinx.serialization.internal.GeneratedSerializer { <init>(...); }
-keepclassmembers class **$$serializer { *** Companion; }
