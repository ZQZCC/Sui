-keepattributes SourceFile,LineNumberTable,*Annotation*

-keepclassmembers class **.R$* {
    public static <fields>;
}

-keepnames class * implements android.os.Parcelable
-keepclassmembers class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator CREATOR;
}

-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

-keep class rikka.sui.SuiActivity { *; }
-keep class rikka.sui.SuiRequestPermissionDialog { *; }
-keep class rikka.sui.util.MiuixPopupTransition {
    public <init>(android.content.Context, android.util.AttributeSet);
}

-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}
-assumenosideeffects class rikka.sui.util.Logger {
    public *** v(...);
    public *** d(...);
    public *** i(...);
    public *** w(...);
    public *** e(...);
}

-dontwarn androidx.**
-dontwarn android.support.**
-dontwarn org.jetbrains.annotations.**
