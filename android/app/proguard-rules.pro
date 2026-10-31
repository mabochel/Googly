# kotlinx.serialization keeps its generated serializers via its own consumer rules.
# OkHttp ships consumer rules too. Nothing app-specific is reflected on.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Keep the JSON models' generated serializers (the standard kotlinx.serialization rules, scoped to this app).
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.mabochel.bluey.**$$serializer { *; }
-keepclassmembers class com.mabochel.bluey.** { *** Companion; }
-keepclasseswithmembers class com.mabochel.bluey.** { kotlinx.serialization.KSerializer serializer(...); }
