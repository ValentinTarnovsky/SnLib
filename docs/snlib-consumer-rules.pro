# snlib-consumer-rules.pro
# Reglas ProGuard para plugins Sn que consumen SnLib y se ofuscan (sn-obfuscate).
#
# SnLib.jar es una LIBRERIA en runtime (plugin standalone en plugins/):
# NUNCA se ofusca ni se shadea en el consumer. Se declara como -libraryjars
# igual que paper-api. Ajustar la ruta al .m2 local o al jar del release.

-libraryjars <user.home>/.m2/repository/com/sn/snlib/1.1.0/snlib-1.1.0.jar

# SnLib no viaja dentro del jar del consumer: silenciar cualquier warning
# de referencias a clases de la lib.
-dontwarn com.sn.lib.**

# Entrypoint del consumer: la clase que extiende com.sn.lib.SnPlugin la
# instancia Bukkit por reflexion (main de plugin.yml) y SnLib invoca su
# handshake requiredApiLevel(). Mantener nombre, constructor y hooks.
-keep public class * extends com.sn.lib.SnPlugin {
    public <init>();
    protected int requiredApiLevel();
    protected com.sn.lib.SnSpec buildSpec();
    protected void onInnerEnable();
    protected void onInnerDisable();
}

# Clases concretas registradas por reflexion o por el framework de Bukkit:
# listeners, ejecutores de comandos y expansiones PAPI del consumer.
-keep class * implements org.bukkit.event.Listener { *; }
-keep class * implements org.bukkit.command.CommandExecutor { *; }
-keep class * implements org.bukkit.command.TabCompleter { *; }
-keep class * extends me.clip.placeholderapi.expansion.PlaceholderExpansion { *; }

# Metodos @EventHandler dentro de cualquier clase (por si un listener no
# implementa Listener directamente sino via clase intermedia).
-keepclassmembers class * {
    @org.bukkit.event.EventHandler <methods>;
}
