# Regras padrão do R8/ProGuard para o projeto.
# O núcleo Rust (crates/ffi) é carregado por JNA: o JNA acha os métodos nativos e as structs/callbacks por reflexão,
# e os bindings do UniFFI (com.localkeys.android.ffi) declaram as funções nativas por nome. Sem estas regras o R8 do
# release renomeia/apaga o que o JNA procura e o app cai ao abrir o cofre.
-keep class com.sun.jna.** { *; }
-keep class net.java.dev.jna.** { *; }
-keep class com.localkeys.android.ffi.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }

# java.awt/javax.swing não existem no Android (só stubs no android.jar); o JNA
# carrega `Native$AWT` com referências residuais que o R8 do release reclama.
-dontwarn com.sun.jna.Native$AWT
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn java.beans.Beans

# Credential Exchange (importar de outro gerenciador): o providerevents acha a implementação do Play Services pelo
# NOME da classe, escrito no manifesto (meta-data PROVIDER_EVENTS_API_PROVIDER_KEY), e a carrega por reflexão. Sem
# esta regra o R8 apaga a classe no release e a importação falha com "no provider dependencies found" (0.4.0).
-keep class androidx.credentials.providerevents.playservices.** { *; }
-keep class * implements androidx.credentials.providerevents.ProviderEventsApiProvider { <init>(...); }
