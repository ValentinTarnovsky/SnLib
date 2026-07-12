package com.sn.lib.action;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
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
import com.sn.lib.item.ItemRegistry;
import com.sn.lib.text.SnText;
import com.sn.lib.util.SoundUtil;

/**
 * Executes YML action lists of the form {@code [tag] argument}, reached through
 * {@code sn.actions()} (one engine per context).
 *
 * <p>Line anatomy: optional guard prefixes, then the action tag and its argument. Guard
 * catalog:</p>
 * <ul>
 *   <li>Inclusive click guards (v1.0.0 semantics intact): {@code [right-click]} passes
 *       with RIGHT and SHIFT_RIGHT ({@link ClickType#isRightClick()});
 *       {@code [left-click]} passes with LEFT, SHIFT_LEFT, DOUBLE_CLICK and CREATIVE
 *       ({@link ClickType#isLeftClick()}).</li>
 *   <li>Exact click guards, each matching exactly one {@link ClickType}:
 *       {@code [shift-right-click]}, {@code [shift-left-click]},
 *       {@code [right-click-only]} (RIGHT, excludes SHIFT_RIGHT/DOUBLE_CLICK/CREATIVE),
 *       {@code [left-click-only]} (LEFT), {@code [middle-click]}, {@code [double-click]},
 *       {@code [drop-click]}, {@code [number-key]} and {@code [swap-offhand]}.</li>
 *   <li>{@code [click=TYPE,...]}: comma-separated {@link ClickType} names, case
 *       insensitive, dashes accepted for underscores; the line runs when the context
 *       click is in the set. FAIL-CLOSED: an invalid spec WARNs once and skips the line
 *       (unlike {@code [chance=N]}, which is fail-open), because a typo must never fire
 *       actions on unwanted clicks.</li>
 *   <li>Positional guards {@code [click-block]} / {@code [click-air]}: exact match
 *       against {@link ActionContext#clickSurface()}. Only world item interactions carry
 *       a surface; GUI clicks and clickless runs leave it null and the line is skipped
 *       with a debug note.</li>
 *   <li>{@code [chance=N]}: rolls a 0-100 chance (doubles allowed; a malformed value
 *       WARNs once and lets the line run).</li>
 * </ul>
 * <p>Every click guard skips its line with a debug note when the context has no
 * {@link ClickType}. A line without a leading tag runs as {@code [message]}.</p>
 *
 * <p>Every argument goes through local placeholders and PAPI (viewer-aware, through the
 * context papi service) before reaching its handler; message-like actions then run the
 * full SnText pipeline including {@code [rgb]} and {@code [center]}. Execution always
 * dispatches to the main thread. An unknown tag WARNs once per tag.</p>
 *
 * <p>Built-in catalog: {@code [player]}, {@code [player-as-op]}, {@code [console]},
 * {@code [message]}, {@code [broadcastmessage]}, {@code [actionbar]},
 * {@code [title] title;subtitle;fadeIn;stay;fadeOut} (times in ticks),
 * {@code [sound] SOUND_ID [vol] [pitch]}, {@code [close]}, {@code [open] gui-id},
 * {@code [connect] server}, {@code [next-page]}, {@code [previous-page]},
 * {@code [set-page] n}, {@code [refresh-page]}, {@code [refresh-menu]},
 * {@code [particle] TYPE [count] [offX offY offZ] [extra] [key=value...]} (options:
 * {@code color}, {@code size}, {@code to}, {@code block}, {@code item}, matched to the
 * particle data type), {@code [potion] EFFECT [seconds] [amplifier]} and
 * {@code [remove-item] [n] [selector]} (default main hand; selectors {@code offhand},
 * {@code MATERIAL} - which never consumes SnLib-tagged stacks - and
 * {@code id:<item-id>}). Page actions delegate to the context {@link PageTarget}; with a null target or
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
                    "Actions discarded: plugin disabled during scheduling");
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
            throw new IllegalArgumentException("Empty action tag");
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
                plugin.getLogger().warning("Action failed in '" + line + "': " + t);
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
            warnOnce("tag:" + tag, "Unknown action '[" + tag + "]'; line ignored: " + line);
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
                || tag.equals("right-click-only") || tag.equals("left-click-only")
                || tag.equals("middle-click") || tag.equals("double-click")
                || tag.equals("drop-click") || tag.equals("number-key")
                || tag.equals("swap-offhand") || tag.equals("click-block")
                || tag.equals("click-air") || tag.startsWith("chance=")
                || tag.startsWith("click=");
    }

    private boolean passesGuard(String tag, ActionContext context, String line) {
        if (tag.startsWith("chance=")) {
            String rawChance = tag.substring("chance=".length()).trim();
            try {
                double chance = Double.parseDouble(rawChance);
                return ThreadLocalRandom.current().nextDouble(100.0) < chance;
            } catch (NumberFormatException e) {
                warnOnce("chance:" + rawChance, "Invalid guard [chance=" + rawChance
                        + "]; the action runs anyway: " + line);
                return true;
            }
        }
        if (tag.equals("click-block") || tag.equals("click-air")) {
            ClickSurface surface = context.clickSurface();
            if (surface == null) {
                ctx.debug().log(() -> "Positional guard [" + tag
                        + "] without ClickSurface in the context; line skipped: " + line);
                return false;
            }
            return surface == (tag.equals("click-block") ? ClickSurface.BLOCK
                    : ClickSurface.AIR);
        }
        if (tag.startsWith("click=")) {
            String spec = tag.substring("click=".length()).trim();
            EnumSet<ClickType> allowed = parseClickTypes(spec);
            if (allowed == null) {
                warnOnce("click-guard:" + spec, "Guard [click=" + spec
                        + "] with invalid type; line skipped: " + line);
                return false;
            }
            ClickType specClick = context.clickType();
            if (specClick == null) {
                ctx.debug().log(() -> "Click guard [" + tag
                        + "] without ClickType in the context; line skipped: " + line);
                return false;
            }
            return allowed.contains(specClick);
        }
        ClickType click = context.clickType();
        if (click == null) {
            ctx.debug().log(() -> "Click guard [" + tag
                    + "] without ClickType in the context; line skipped: " + line);
            return false;
        }
        return matchesExactClickGuard(tag, click);
    }

    /**
     * Named click guard matching. {@code right-click} / {@code left-click} keep the
     * inclusive v1.0.0 semantics ({@link ClickType#isRightClick()} /
     * {@link ClickType#isLeftClick()}); every other guard matches exactly one ClickType.
     */
    static boolean matchesExactClickGuard(String tag, ClickType click) {
        return switch (tag) {
            case "right-click" -> click.isRightClick();
            case "left-click" -> click.isLeftClick();
            case "shift-right-click" -> click == ClickType.SHIFT_RIGHT;
            case "shift-left-click" -> click == ClickType.SHIFT_LEFT;
            case "right-click-only" -> click == ClickType.RIGHT;
            case "left-click-only" -> click == ClickType.LEFT;
            case "middle-click" -> click == ClickType.MIDDLE;
            case "double-click" -> click == ClickType.DOUBLE_CLICK;
            case "drop-click" -> click == ClickType.DROP;
            case "number-key" -> click == ClickType.NUMBER_KEY;
            case "swap-offhand" -> click == ClickType.SWAP_OFFHAND;
            default -> false;
        };
    }

    /**
     * Parses a comma-separated {@code [click=...]} spec into ClickType constants. Names
     * are case insensitive and accept dashes for underscores. All-or-nothing: an empty
     * spec, an empty token or any unknown name yields null so the guard fails closed.
     */
    static @Nullable EnumSet<ClickType> parseClickTypes(String spec) {
        EnumSet<ClickType> types = EnumSet.noneOf(ClickType.class);
        for (String token : spec.split(",")) {
            String name = token.trim();
            if (name.isEmpty()) {
                return null;
            }
            try {
                types.add(ClickType.valueOf(name.toUpperCase(Locale.ROOT).replace('-', '_')));
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        }
        return types.isEmpty() ? null : types;
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
            warnOnce("empty-command", "Command action without an argument; ignored");
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
            warnOnce("open-arg", "Action [open] without gui-id; ignored");
            return;
        }
        Gui gui;
        try {
            gui = ctx.guis().get(id);
        } catch (UnsupportedOperationException e) {
            warnOnce("open-module",
                    "Action [open] ignored: guis module not declared in the spec");
            return;
        }
        if (gui == null) {
            warnOnce("open:" + id, "Action [open] ignored: gui '" + id + "' does not exist");
            return;
        }
        gui.open(player);
    }

    private void connect(Player player, String arg) {
        String server = arg.trim();
        if (server.isEmpty()) {
            warnOnce("connect-arg", "Action [connect] without a target server; ignored");
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
            warnOnce("connect-io", "Action [connect] failed building the message: " + e);
            return;
        }
        player.sendPluginMessage(plugin, BUNGEE_CHANNEL, bytes.toByteArray());
    }

    private void withPagination(ActionContext context, String tag, Consumer<PageTarget> op) {
        PageTarget target = context.pageTarget();
        if (target == null || !target.paginationEnabled()) {
            ctx.debug().log(() -> "Action [" + tag
                    + "] skipped: pagination not enabled (opt-in per menu)");
            return;
        }
        op.accept(target);
    }

    /**
     * Handles {@code [particle] TYPE [count] [offX offY offZ] [extra] [key=value...]}.
     * Tokens containing {@code '='} are options ({@code color}, {@code size}, {@code to},
     * {@code block}, {@code item}); the rest resolve positionally with the same
     * thresholds as always (count with 1+, offsets only with 4+, extra with 5+).
     */
    private void spawnParticle(Player player, String arg) {
        String[] parts = arg.trim().split("\\s+");
        if (parts[0].isEmpty()) {
            warnOnce("particle-arg", "Action [particle] without a type; ignored");
            return;
        }
        Particle particle = resolveParticle(parts[0]);
        if (particle == null) {
            return;
        }
        List<String> positional = new ArrayList<>();
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq >= 0) {
                String key = parts[i].substring(0, eq).toLowerCase(Locale.ROOT);
                options.put(key, parts[i].substring(eq + 1));
                if (!isParticleOption(key)) {
                    warnOnce("particle-opt-unknown:" + key,
                            "Unknown option '" + key + "' in [particle]; ignored");
                }
            } else {
                positional.add(parts[i]);
            }
        }
        int count = positional.size() >= 1 ? parseInt(positional.get(0), 1, "particle") : 1;
        double offX = positional.size() >= 4 ? parseDouble(positional.get(1), 0.0, "particle") : 0.0;
        double offY = positional.size() >= 4 ? parseDouble(positional.get(2), 0.0, "particle") : 0.0;
        double offZ = positional.size() >= 4 ? parseDouble(positional.get(3), 0.0, "particle") : 0.0;
        double extra = positional.size() >= 5 ? parseDouble(positional.get(4), 0.0, "particle") : 0.0;
        Object data;
        Class<?> dataType = particle.getDataType();
        if (dataType == Void.class) {
            data = null;
            for (String key : options.keySet()) {
                if (isParticleOption(key)) {
                    warnOnce("particle-opt:" + parts[0] + ":" + key, "Option '" + key
                            + "' incompatible with particle '" + parts[0] + "'; ignored");
                }
            }
        } else if (dataType == Particle.DustOptions.class) {
            data = new Particle.DustOptions(optionColor(options, "color", Color.RED),
                    optionSize(options));
        } else if (dataType == Particle.DustTransition.class) {
            Color from = optionColor(options, "color", Color.RED);
            Color to = optionColor(options, "to", from);
            data = new Particle.DustTransition(from, to, optionSize(options));
        } else if (dataType == BlockData.class) {
            Material mat = optionMaterial(options, "block", parts[0], Material::isBlock);
            if (mat == null) {
                return;
            }
            try {
                data = mat.createBlockData();
            } catch (IllegalArgumentException e) {
                warnOnce("particle-block-data:" + parts[0], "Particle '" + parts[0]
                        + "' does not accept block data of '" + mat + "'; ignored");
                return;
            }
        } else if (dataType == ItemStack.class) {
            Material mat = optionMaterial(options, "item", parts[0], Material::isItem);
            if (mat == null) {
                return;
            }
            data = new ItemStack(mat);
        } else {
            warnOnce("particle-data:" + parts[0], "Particle '" + parts[0]
                    + "' requires unsupported data; ignored");
            return;
        }
        player.getWorld().spawnParticle(particle, player.getLocation().add(0.0, 1.0, 0.0),
                count, offX, offY, offZ, extra, data);
    }

    private static boolean isParticleOption(String key) {
        return key.equals("color") || key.equals("size") || key.equals("to")
                || key.equals("block") || key.equals("item");
    }

    /** Option {@code key} parsed as a color, or {@code def} when absent or invalid. */
    private Color optionColor(Map<String, String> options, String key, Color def) {
        String raw = options.get(key);
        if (raw == null) {
            return def;
        }
        Color parsed = parseColor(raw, "particle");
        return parsed == null ? def : parsed;
    }

    /** Option {@code size} parsed as a float, defaulting to 1.0f when absent or invalid. */
    private float optionSize(Map<String, String> options) {
        String raw = options.get("size");
        return raw == null ? 1.0f : (float) parseDouble(raw, 1.0, "particle");
    }

    /**
     * Required material option; a missing key, an unknown material or one rejected by
     * {@code usable} WARNs once and returns null so the caller skips the line.
     */
    private @Nullable Material optionMaterial(Map<String, String> options, String key,
            String type, Predicate<Material> usable) {
        String raw = options.get(key);
        if (raw == null) {
            warnOnce("particle-" + key + "-missing:" + type,
                    "Particle '" + type + "' requires " + key + "=MATERIAL; ignored");
            return null;
        }
        Material mat = Material.matchMaterial(raw);
        if (mat == null || !usable.test(mat)) {
            warnOnce("particle-" + key + ":" + raw, "Invalid material '" + raw + "' in "
                    + key + "= of [particle]; ignored");
            return null;
        }
        return mat;
    }

    /** Parses {@code #RRGGBB} or {@code R,G,B} (0-255); invalid WARNs once and yields null. */
    private @Nullable Color parseColor(String raw, String tag) {
        try {
            if (raw.startsWith("#") && raw.length() == 7) {
                return Color.fromRGB(Integer.parseInt(raw.substring(1), 16));
            }
            String[] rgb = raw.split(",", -1);
            if (rgb.length == 3) {
                return Color.fromRGB(Integer.parseInt(rgb[0].trim()),
                        Integer.parseInt(rgb[1].trim()), Integer.parseInt(rgb[2].trim()));
            }
        } catch (IllegalArgumentException invalid) {
            // falls through to the warnOnce below
        }
        warnOnce("color:" + tag + ":" + raw, "Invalid color '" + raw + "' in action ["
                + tag + "]; using the default");
        return null;
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
                    warnOnce("particle-alias:" + name, "Particle '" + name
                            + "' does not exist on this server; using alias '" + alias + "'");
                    return particle;
                } catch (IllegalArgumentException aliasMissing) {
                    // neither of the two names exists in this runtime
                }
            }
            warnOnce("particle:" + name, "Invalid particle '" + raw + "'; ignored");
            return null;
        }
    }

    private void applyPotion(Player player, String arg) {
        String[] parts = arg.trim().split("\\s+");
        if (parts[0].isEmpty()) {
            warnOnce("potion-arg", "Action [potion] without an effect; ignored");
            return;
        }
        PotionEffectType type = resolveEffect(parts[0]);
        if (type == null) {
            warnOnce("potion:" + parts[0], "Invalid potion effect '" + parts[0] + "'; ignored");
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

    /**
     * Handles {@code [remove-item] [n] [selector]}. No selector keeps the historical
     * main-hand behavior; {@code offhand} mirrors it, {@code id:<item-id>} deducts
     * registered stacks of this context and any other token is a Material name that
     * never consumes SnLib-tagged stacks. Partial removal is allowed in every mode.
     */
    private void removeItem(Player player, String arg) {
        String trimmed = arg.trim();
        int amount = 1;
        String selector = null;
        if (!trimmed.isEmpty()) {
            String[] tokens = trimmed.split("\\s+");
            if (tokens.length == 1) {
                try {
                    amount = Integer.parseInt(tokens[0]);
                } catch (NumberFormatException notANumber) {
                    selector = tokens[0];
                }
            } else {
                amount = parseInt(tokens[0], 1, "remove-item");
                selector = tokens[1];
            }
        }
        if (selector == null) {
            removeFromHand(player, amount, false);
            return;
        }
        if (selector.equalsIgnoreCase("offhand")) {
            removeFromHand(player, amount, true);
            return;
        }
        if (selector.regionMatches(true, 0, "id:", 0, 3)) {
            String id = selector.substring(3).trim();
            if (id.isEmpty() || ctx.items().def(id) == null) {
                warnOnce("remove-item-id:" + id, "Action [remove-item] ignored: item '"
                        + id + "' is not registered");
                return;
            }
            sweepInventory(player, amount, stack -> ctx.items().is(stack, id));
            return;
        }
        Material mat = Material.matchMaterial(selector);
        if (mat == null || !mat.isItem()) {
            warnOnce("remove-item-mat:" + selector, "Action [remove-item] ignored: "
                    + "invalid material '" + selector + "'");
            return;
        }
        sweepInventory(player, amount, stack -> stack.getType() == mat && !hasSnlibTag(stack));
    }

    private static void removeFromHand(Player player, int amount, boolean offhand) {
        ItemStack hand = offhand ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            return;
        }
        if (hand.getAmount() > amount) {
            hand.setAmount(hand.getAmount() - amount);
        } else if (offhand) {
            player.getInventory().setItemInOffHand(null);
        } else {
            player.getInventory().setItemInMainHand(null);
        }
    }

    /**
     * Deducts up to {@code amount} units from matching stacks in storage slots 0-35 and
     * then the offhand. Removing fewer units than requested is not an error.
     */
    private static void sweepInventory(Player player, int amount, Predicate<ItemStack> matches) {
        PlayerInventory inv = player.getInventory();
        int remaining = amount;
        for (int slot = 0; slot < 36 && remaining > 0; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack == null || stack.getType().isAir() || !matches.test(stack)) {
                continue;
            }
            int take = Math.min(remaining, stack.getAmount());
            if (take == stack.getAmount()) {
                inv.setItem(slot, null);
            } else {
                stack.setAmount(stack.getAmount() - take);
                inv.setItem(slot, stack);
            }
            remaining -= take;
        }
        if (remaining <= 0) {
            return;
        }
        ItemStack off = inv.getItemInOffHand();
        if (off.getType().isAir() || !matches.test(off)) {
            return;
        }
        int take = Math.min(remaining, off.getAmount());
        if (take == off.getAmount()) {
            inv.setItemInOffHand(null);
        } else {
            off.setAmount(off.getAmount() - take);
            inv.setItemInOffHand(off);
        }
    }

    /**
     * Whether some SnLib context (any owner namespace) tagged the stack with an item id;
     * the Material selector of {@code [remove-item]} never consumes those stacks.
     */
    private static boolean hasSnlibTag(ItemStack stack) {
        if (!stack.hasItemMeta()) {
            return false;
        }
        for (NamespacedKey key : stack.getItemMeta().getPersistentDataContainer().getKeys()) {
            if (ItemRegistry.TAG_KEY.equals(key.getKey())) {
                return true;
            }
        }
        return false;
    }

    private int parseInt(String token, int def, String tag) {
        try {
            return Integer.parseInt(token.trim());
        } catch (NumberFormatException e) {
            warnOnce("num:" + tag + ":" + token, "Invalid number '" + token
                    + "' in action [" + tag + "]; using " + def);
            return def;
        }
    }

    private double parseDouble(String token, double def, String tag) {
        try {
            return Double.parseDouble(token.trim());
        } catch (NumberFormatException e) {
            warnOnce("num:" + tag + ":" + token, "Invalid number '" + token
                    + "' in action [" + tag + "]; using " + def);
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
