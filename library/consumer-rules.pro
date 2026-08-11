# keep realtime-debug symbols when host enables R8
-keep class com.maiya.realtimedebug.RealtimeDebug { *; }
-keep class com.maiya.realtimedebug.RealtimeUidProvider { *; }
-keep class com.maiya.realtimedebug.RealtimeNetworkInterceptor { *; }
-keep class com.maiya.realtimedebug.RealtimeMockInterceptor { *; }
