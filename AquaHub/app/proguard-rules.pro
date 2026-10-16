# AquaHub custom R8 / ProGuard rules

# Keep generic signatures, annotations, and nested-class metadata used by
# Room, WorkManager, Firebase, Billing, and the AndroidX credential providers.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes *Annotation*

# Kotlin metadata
-keep class kotlin.Metadata { *; }

# Android framework entry points. Manifest references are normally roots, but
# keeping these explicitly protects them when a dependency or manifest merge
# changes the reachability analysis.
-keep class com.aesprt.aquahub.AquaHubApplication { public <init>(); }
-keep class com.aesprt.aquahub.data.firebase.messaging.AquaHubMessagingService { public <init>(); }

# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.TypeConverter class * { *; }

# Firebase
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeInvisibleAnnotations

# Google Play Billing
-keep class com.android.billingclient.** { *; }

# Google Sign-In / Credentials
-keep class com.google.android.libraries.identity.googleid.** { *; }

# WorkManager
-keep class * extends androidx.work.ListenableWorker { *; }

# Keep enum valueOf / values where reflection may be involved
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
