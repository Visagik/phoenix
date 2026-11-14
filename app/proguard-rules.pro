# Сжатие включено, переименование — нет: код открытый, а имена классов попадают в журнал и отчёты о сбоях.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*

# Ядро Xray вызывается по имени строкой (XrayCore.kt): классы нельзя переименовывать и удалять.
-keep class libv2ray.** { *; }
-keep class go.** { *; }
-dontwarn go.**
-dontwarn libv2ray.**

# Нативный туннель (hev-socks5-tunnel) ищет класс и методы по имени.
-keep class com.v2ray.ang.service.TProxyService { *; }
-keepclasseswithmembernames class * { native <methods>; }
