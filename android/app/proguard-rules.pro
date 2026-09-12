# WebRTC creates native-backed objects through its public Java API. Keep names
# used by JNI while allowing application code and resources to be optimized.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
-keepattributes Signature,*Annotation*
