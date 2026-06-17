# Keep Room generated code
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class *

# TensorFlow Lite
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# MediaPipe LLM Inference (experimental generative VLM describer) + its image framework.
# These call into JNI / reflect over generated classes, so keep them intact under R8.
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# protobuf-javalite (pulled in by tasks-genai): R8 must not strip generated message fields.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-dontwarn com.google.protobuf.**

# Guava (transitive of tasks-genai) references optional annotations not on the classpath.
-dontwarn com.google.common.**
-dontwarn javax.annotation.**
-dontwarn javax.lang.model.**
-dontwarn sun.misc.**
