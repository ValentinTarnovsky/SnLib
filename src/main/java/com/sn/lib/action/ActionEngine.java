package com.sn.lib.action;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.Nullable;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;

import com.sn.lib.Ph;
import com.sn.lib.Sn;
import com.sn.lib.gui.Gui;
import com.sn.lib.text.SnText;
import com.sn.lib.util.SoundUtil;

/**
 * Executes YML action lists of the form {@code [tag] argumento}, reached through
 * {@code sn.actions()} (one engine per context).
 *
 * <p>Line anatomy: optional guard prefixes, then the action tag and its argument. Guards:
 * {@code [right-click]}, {@code [left-click]}, {@code [shift-right-click]} and
 * {@code [shift-left-click]} match against {@link ActionContext#clickType()} (without a
 * click the guarded line is skipped with a debug note), and {@code [chance=N]} rolls a
 * 0-100 chance (doubles allowed; a malformed value WARNs once and lets the line run). A
 * line without a leading tag runs as {@code [message]}.</p>
 *
 * <p>Every argument goes through local placeholders and PAPI (viewer-aware, through the
 * context papi service) before reaching its handler; message-like actions then run the
 * full SnText pipeline including {@code [rgb]} and {@code [center]}. Execution always
 * dispatches to the main thread. An unknown tag WARNs once per tag.</p>
 *
 * <p>Built-in catalog: {@code [player]}, {@code [player-as-op]}, {@code [console]},
 * {@code [message]}, {@code [broadcastmessage]}, {@code [actionbar]},
 * {@code [title] titulo;subtitulo;fadeIn;stay;fadeOut} (times in ticks),
 * {@code [sound] SOUND_ID [vol] [pitch]}, {@code [close]}, {@code [open] gui-id},
 * {@code [connect] servidor}, {@code [next-page]}, {@code [previous-page]},
 * {@code [set-page] n}, {@code [refresh-page]}, {@code [refresh-menu]},
 * {@code [particle] TYPE [count] [offX offY offZ] [extra]},
 * {@code [potion] EFFECT [segundos] [amplifier]} and {@code [remove-item] [n]} (main
 * hand). Page actions delegate to the context {@link PageTarget}; with a null target or
 * {@link PageTarget#paginationEnabled()} false they no-op with a debug note (pagination
 * is opt-in per menu). Custom tags via {@link #register}; a registration may override a
 * built-in.</p>
 */
public final class ActionEngine {

    private static final String BUNGEE_CHANNEL = "BungeeCord";

    private final Sn ctx;
    private final JavaPlugin plugin;
    private final Map<String, ActionHandler> handlers = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean bungeeRegistered = new AtomicBoolean();

    /** Creates the engine for the given context with the built-in catalog registered. */
    public ActionEngine(Sn ctx) {
        this.ctx = ctx;
        this.plugin = ctx.plugin();
        registerBuiltins();
    }

    /** Runs the action lines for the player with local placeholders and no page/click data. */
    public void run(Player player, List<String> actions, Ph... phs) {
        run(player, actions, new ActionContext(player, ctx, null, null, phs));
    }

    /**
     * Runs the action lines for the player under the given context. Execution happens on
     * the main thread: callers already there run inline, any other thread hops through
     * the context scheduler.
     */
    public void run(Player player, List<String> actions, ActionContext context) {
        if (player == null || actions == null || actions.isEmpty()) {
            return;
        }
        Objects.requireNonNull(context, "context");
        if (Bukkit.isPrimaryThread()) {
            execute(player, actions, context);
            return;
        }
        try {
            ctx.scheduler().sync(() -> execute(player, actions, context));
        } catch (IllegalPluginAccessException e) {
            plugin.getLogger().warning(
                    "Acciones descartadas: plugin deshabilitado durante el scheduling");
        }
    }

    /**
     * Registers a custom action under {@code tag} (with or without brackets, case
     * insensitive), replacing any previous handler including a built-in.
     */
    public void register(String tag, ActionHandler handler) {
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(handler, "handler");
        String key = normalizeTag(tag);
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Tag de accion vacio");
        }
        handlers.put(key, handler);
    }

    /**
     * Releases the outgoing plugin channel that {@code [connect]} registered on first
     * use; invoked by the context teardown. Idempotent.
     */
    public void shutdown() {
        if (bungeeRegistered.compareAndSet(true, false)) {
            Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, BUNGEE_CHANNEL);
        }
    }

    private void execute(Player player, List<String> actions, ActionContext context) {
        for (String raw : actions) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String line = raw.trim();
            try {
                executeLine(player, line, context);
            } catch (Throwable t) {
                plugin.getLogger().warning("Accion fallo en '" + line + "': " + t);
            }
        }
    }

    private void executeLine(Player player, String line, ActionContext context) {
        String work = line;
        Head head = head(work);
        while (head != null && isGuard(head.tag())) {
            if (!passesGuard(head.tag(), context, line)) {
                return;
            }
            work = head.arg();
            head = head(work);
        }
        String tag = head == null ? "message" : head.tag();
        String arg = head == null ? work : head.arg();
        ActionHandler handler = handlers.get(tag);
        if (handler == null) {
            warnOnce("tag:" + tag, "Accion desconocida '[" + tag + "]'; linea ignorada: " + line);
            return;
        }
        String resolved = ctx.papi().apply(player, SnText.applyLocals(arg, context.phs()));
        handler.run(player, resolved, context);
    }

    /** Leading {@code [tag]} of the line, or null when the line does not start with one. */
    private static @Nullable Head head(String line) {
        if (!line.startsWith("[")) {
            return null;
        }
        int close = line.indexOf(']');
        if (close < 1) {
            return null;
        }
        String tag = line.substring(1, close).trim().toLowerCase(Locale.ROOT);
        if (tag.isEmpty()) {
            return null;
        }
        return new Head(tag, line.substring(close + 1).trim());
    }

    private static boolean isGuard(String tag) {
        return tag.equals("right-click") || tag.equals("left-click")
                || tag.equals("shift-right-click") || tag.equals("shift-left-click")
                || tag.startsWith("chance=");
    }

    private boolean passesGuard(String tag, ActionContext context, String line) {
        if (tag.startsWith("chance=")) {
            String rawChance = tag.substring("chance=".length()).trim();
            try {
                double chance = Double.parseDouble(rawChance);
                return ThreadLocalRandom.current().nextDouble(100.0) < chance;
            } catch (NumberFormatException e) {
                warnOnce("chance:" + rawChance, "Guard [chance=" + rawChance
                        + "] invalido; la accion corre igual: " + line);
                return true;
            }
        }
        ClickType click = context.clickType();
        if (click == null) {
            ctx.debug().log(() -> "Guard de click [" + tag
                    + "] sin ClickType en el contexto; linea omitida: " + line);
            return false;
        }
        if (tag.equals("right-click")) {
            return click.isRightClick();
        }
        if (tag.equals("left-click")) {
            return click.isLeftClick();
        }
        if (tag.equals("shift-right-click")) {
            return click == ClickType.SHIFT_RIGHT;
        }
        return click == ClickType.SHIFT_LEFT;
    }

    private void registerBuiltins() {
        handlers.put("player", (p, arg, c) -> dispatch(p, arg));
        handlers.put("player-as-op", (p, arg, c) -> dispatchAsOp(p, arg));
        handlers.put("console", (p, arg, c) -> dispatch(Bukkit.getConsoleSender(), arg));
        handlers.put("message", (p, arg, c) -> p.sendMessage(render(arg)));
        handlers.put("broadcastmessage", (p, arg, c) -> Bukkit.getServer().sendMessage(render(arg)));
        handlers.put("actionbar", (p, arg, c) -> p.sendActionBar(render(arg)));
        handlers.put("title", (p, arg, c) -> showTitle(p, arg));
        handlers.put("sound", (p, arg, c) -> SoundUtil.play(p, arg));
        handlers.put("close", (p, arg, c) -> p.closeInventory());
        handlers.put("open", (p, arg, c) -> openGui(p, arg));
        handlers.put("connect", (p, arg, c) -> connect(p, arg));
        handlers.put("next-page", (p, arg, c) -> withPagination(c, "next-page", PageTarget::nextPage));
        handlers.put("previous-page", (p, arg, c) -> withPagination(c, "previous-page", PageTarget::previousPage));
        handlers.put("set-page", (p, arg, c) -> withPagination(c, "set-page",
                t -> t.setPage(parseInt(arg, 1, "set-page"))));
        handlers.put("refresh-page", (p, arg, c) -> withPagination(c, "refresh-page", PageTarget::refreshPage));
        handlers.put("refresh-menu", (p, arg, c) -> withPagination(c, "refresh-menu", PageTarget::refreshMenu));
        handlers.put("particle", (p, arg, c) -> spawnParticle(p, arg));
        handlers.put("potion", (p, arg, c) -> applyPotion(p, arg));
        handlers.put("remove-item", (p, arg, c) -> removeItem(p, arg));
    }

    /** Message-like render: PAPI output normalization plus the full SnText pipeline. */
    private static Component render(String arg) {
        return SnText.color(SnText.normalizePapiOutput(arg));
    }

    private void dispatch(CommandSender sender, String arg) {
        String command = stripSlash(arg);
        if (command.isEmpty()) {
            warnOnce("empty-command", "Accion de comando sin argumento; se ignora");
            return;
        }
        Bukkit.dispatchCommand(sender, command);
    }

    private void dispatchAsOp(Player player, String arg) {
        if (player.isOp()) {
            dispatch(player, arg);
            return;
        }
        player.setOp(true);
        try {
            dispatch(player, arg);
        } finally {
            player.setOp(false);
        }
    }

    private static String stripSlash(String arg) {
        String command = arg.trim();
        return command.startsWith("/") ? command.substring(1).trim() : command;
    }

    private void showTitle(Player player, String arg) {
        String[] parts = arg.split(";", -1);
        Component title = render(parts[0]);
        Component subtitle = parts.length > 1 ? render(parts[1]) : Component.empty();
        long fadeIn = ticksPart(parts, 2, 10L);
        long stay = ticksPart(parts, 3, 70L);
        long fadeOut = ticksPart(parts, 4, 20L);
        player.showTitle(Title.title(title, subtitle, Title.Times.times(
                Duration.ofMillis(fadeIn * 50L),
                Duration.ofMillis(stay * 50L),
                Duration.ofMillis(fadeOut * 50L))));
    }

    private long ticksPart(String[] parts, int index, long def) {
        if (parts.length <= index || parts[index].isBlank()) {
            return def;
        }
        return parseInt(parts[index], (int) def, "title");
    }

    /** Opens the context GUI named by {@code arg}; misconfigurations WARN once and skip. */
    private void openGui(Player player, String arg) {
        String id = arg.trim();
        if (id.isEmpty()) {
            warnOnce("open-arg", "Accion [open] sin gui-id; se ignora");
            return;
        }
        Gui gui;
        try {
            gui = ctx.guis().get(id);
        } catch (UnsupportedOperationException e) {
            warnOnce("open-module",
                    "Accion [open] ignorada: modulo guis no declarado en el spec");
            return;
        }
        if (gui == null) {
            warnOnce("open:" + id, "Accion [open] ignorada: gui '" + id + "' no existe");
            return;
        }
        gui.open(player);
    }

    private void connect(Player player, String arg) {
        String server = arg.trim();
        if (server.isEmpty()) {
            warnOnce("connect-arg", "Accion [connect] sin servidor destino; se ignora");
            return;
        }
        if (bungeeRegistered.compareAndSet(false, true)) {
            Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, BUNGEE_CHANNEL);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF("Connect");
            out.writeUTF(server);
        } catch (IOException e) {
            warnOnce("connect-io", "Accion [connect] fallo armando el mensaje: " + e);
            return;
        }
        player.sendPluginMessage(plugin, BUNGEE_CHANNEL, bytes.toByteArray());
    }

    private void withPagination(ActionContext context, String tag, Consumer<PageTarget> op) {
        PageTarget target = context.pageTarget();
        if (target == null || !target.paginationEnabled()) {
            ctx.debug().log(() -> "Accion [" + tag
                    + "] omitida: paginacion no habilitada (opt-in por menu)");
            return;
        }
        op.accept(target);
    }

    private void spawnParticle(Player player, String arg) {
        String[] parts = arg.trim().split("\\s+");
        if (parts[0].isEmpty()) {
            warnOnce("particle-arg", "Accion [particle] sin tipo; se ignora");
            return;
        }
        Particle particle = resolveParticle(parts[0]);
        if (particle == null) {
            return;
        }
        int count = parts.length > 1 ? parseInt(parts[1], 1, "particle") : 1;
        double offX = parts.length > 4 ? parseDouble(parts[2], 0.0, "particle") : 0.0;
        double offY = parts.length > 4 ? parseDouble(parts[3], 0.0, "particle") : 0.0;
        double offZ = parts.length > 4 ? parseDouble(parts[4], 0.0, "particle") : 0.0;
        double extra = parts.length > 5 ? parseDouble(parts[5], 0.0, "particle") : 0.0;
        Object data = null;
        Class<?> dataType = particle.getDataType();
        if (dataType == Particle.DustOptions.class) {
            data = new Particle.DustOptions(Color.RED, 1.0f);
        } else if (dataType != Void.class) {
            warnOnce("particle-data:" + parts[0], "Particula '" + parts[0]
                    + "' requiere datos no soportados; se ignora");
            return;
        }
        player.getWorld().spawnParticle(particle, player.getLocation().add(0.0, 1.0, 0.0),
                count, offX, offY, offZ, extra, data);
    }

    /**
     * Particle is an open set: resolution goes through valueOf with a lenient
     * REDSTONE&lt;-&gt;DUST alias (WARN once) so specs written for either naming era keep
     * working on both sides of the 1.20.5 rename.
     */
    private @Nullable Particle resolveParticle(String raw) {
        String name = raw.toUpperCase(Locale.ROOT);
        if (name.startsWith("MINECRAFT:")) {
            name = name.substring("MINECRAFT:".length());
        }
        name = name.replace('.', '_').replace('-', '_');
        try {
            return Particle.valueOf(name);
        } catch (IllegalArgumentException notAConstant) {
            String alias = "REDSTONE".equals(name) ? "DUST"
                    : "DUST".equals(name) ? "REDSTONE" : null;
            if (alias != null) {
                try {
                    Particle particle = Particle.valueOf(alias);
                    warnOnce("particle-alias:" + name, "Particula '" + name
                            + "' no existe en este servidor; usando alias '" + alias + "'");
                    return particle;
                } catch (IllegalArgumentException aliasMissing) {
                    // ninguno de los dos nombres existe en este runtime
                }
            }
            warnOnce("particle:" + name, "Particula invalida '" + raw + "'; se ignora");
            return null;
        }
    }

    private void applyPotion(Player player, String arg) {
        String[] parts = arg.trim().split("\\s+");
        if (parts[0].isEmpty()) {
            warnOnce("potion-arg", "Accion [potion] sin efecto; se ignora");
            return;
        }
        PotionEffectType type = resolveEffect(parts[0]);
        if (type == null) {
            warnOnce("potion:" + parts[0], "Efecto de pocion invalido '" + parts[0] + "'; se ignora");
            return;
        }
        int seconds = parts.length > 1 ? parseInt(parts[1], 10, "potion") : 10;
        int amplifier = parts.length > 2 ? parseInt(parts[2], 0, "potion") : 0;
        player.addPotionEffect(new PotionEffect(type, seconds * 20, amplifier));
    }

    /** Registry lookup by key first, then the legacy name fallback for old configs. */
    @SuppressWarnings("deprecation")
    private static @Nullable PotionEffectType resolveEffect(String raw) {
        NamespacedKey key = NamespacedKey.fromString(raw.toLowerCase(Locale.ROOT));
        if (key != null) {
            PotionEffectType byKey = Registry.EFFECT.get(key);
            if (byKey != null) {
                return byKey;
            }
        }
        return PotionEffectType.getByName(raw);
    }

    private void removeItem(Player player, String arg) {
        int amount = arg.isBlank() ? 1 : parseInt(arg, 1, "remove-item");
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            return;
        }
        if (hand.getAmount() > amount) {
            hand.setAmount(hand.getAmount() - amount);
        } else {
            player.getInventory().setItemInMainHand(null);
        }
    }

    private int parseInt(String token, int def, String tag) {
        try {
            return Integer.parseInt(token.trim());
        } catch (NumberFormatException e) {
            warnOnce("num:" + tag + ":" + token, "Numero invalido '" + token
                    + "' en accion [" + tag + "]; usando " + def);
            return def;
        }
    }

    private double parseDouble(String token, double def, String tag) {
        try {
            return Double.parseDouble(token.trim());
        } catch (NumberFormatException e) {
            warnOnce("num:" + tag + ":" + token, "Numero invalido '" + token
                    + "' en accion [" + tag + "]; usando " + def);
            return def;
        }
    }

    private static String normalizeTag(String tag) {
        String key = tag.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith("[") && key.endsWith("]") && key.length() >= 2) {
            key = key.substring(1, key.length() - 1).trim();
        }
        return key;
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) {
            plugin.getLogger().warning(message);
        }
    }

    private record Head(String tag, String arg) {
    }
}
