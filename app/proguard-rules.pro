# Ядро Xray вызывается через рефлексию (XrayCore.kt): эти классы нельзя переименовывать и удалять,
# если когда-нибудь будет включено сжатие (isMinifyEnabled = true).
-keep class libv2ray.** { *; }
-keep class go.** { *; }
-keep class com.v2ray.ang.service.TProxyService { *; }
