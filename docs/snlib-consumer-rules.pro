# snlib-consumer-rules.pro
# ProGuard rules for Sn plugins that consume SnLib and get obfuscated (sn-obfuscate).
#
# SnLib.jar is a LIBRARY at runtime (standalone plugin in plugins/):
# it is NEVER obfuscated nor shaded into the consumer. Declare it as -libraryjars
# just like paper-api. Adjust the path to the local .m2 or to the release jar.

-libraryjars <user.home>/.m2/repository/com/sn/snlib/1.2.0/snlib-1.2.0.jar

# SnLib does not travel inside the consumer jar: silence any warning
# about references to classes of the lib.
-dontwarn com.sn.lib.**

# Consumer entrypoint: the class extending com.sn.lib.SnPlugin is
# instantiated by Bukkit via reflection (main of plugin.yml) and SnLib invokes
# its requiredApiLevel() handshake. Keep name, constructor and hooks.
-keep public class * extends com.sn.lib.SnPlugin {
    public <init>();
    protected int requiredApiLevel();
    protected com.sn.lib.SnSpec buildSpec();
    protected void onInnerEnable();
    protected void onInnerDisable();
}

# Concrete classes registered via reflection or by the Bukkit framework:
# listeners, command executors and PAPI expansions of the consumer.
-keep class * implements org.bukkit.event.Listener { *; }
-keep class * implements org.bukkit.command.CommandExecutor { *; }
-keep class * implements org.bukkit.command.TabCompleter { *; }
-keep class * extends me.clip.placeholderapi.expansion.PlaceholderExpansion { *; }

# @EventHandler methods inside any class (in case a listener does not
# implement Listener directly but through an intermediate class).
-keepclassmembers class * {
    @org.bukkit.event.EventHandler <methods>;
}
