# Regras padrão do R8/ProGuard para o projeto.
# A lógica cripto usa o lazysodium (JNA), que não deve ter as classes ofuscadas
# a ponto de quebrar o carregamento nativo — mantenha as classes JNA mapeáveis.
-keep class com.sun.jna.** { *; }
-keep class com.goterl.lazysodium.** { *; }
-keep class net.java.dev.jna.** { *; }

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
