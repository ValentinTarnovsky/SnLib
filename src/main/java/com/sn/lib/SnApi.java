package com.sn.lib;

/**
 * Public API level of this SnLib build.
 *
 * <p>Policy: {@link #LEVEL} increases by exactly 1 on EVERY release that adds new public
 * methods or classes to the API; the API surface is frozen under an additive-only japicmp
 * gate. This is the number every consumer handshakes against through
 * {@code SnPlugin#requiredApiLevel()}: the required level is inlined into the consumer's
 * bytecode at compile time, so a consumer built against a newer level than the installed
 * SnLib.jar disables itself cleanly instead of failing with {@code NoSuchMethodError}.</p>
 *
 * <p>History: LEVEL 1 = release 1.0.0; LEVEL 2 = release 1.1.0; LEVEL 3 = release 1.4.0
 * (shared multi-plugin releases repo support in UpdateChecker); LEVEL 4 = release 1.8.0
 * (ItemRegistry take/removeAll, SnYml comment write surface, lang interactive-tag drift
 * WARN); LEVEL 5 = release 1.10.0 (SubCommandBuilder helpVisible help-only visibility
 * flag); LEVEL 6 = release 1.11.0 (SnItem itemModel 1.21.2+ item_model component
 * support); LEVEL 7 = release 1.12.0 (redeemable items via ItemRegistry.redeemable with
 * RedeemSpec/RedeemHandler; Args.intMin/doubleMin open-ended numeric factories;
 * k/m/b/t/qa/qi suffix parsing in every numeric arg; snlib.number-too-small message
 * key; SnItem lore newline split for multi-line placeholder values); LEVEL 8 = release
 * 1.13.0 (alias-aware command rendering: CommandContext.label() and RootContext.label()
 * expose the root label the sender typed, and every generated usage, help entry and help
 * footer renders under it); LEVEL 9 = release 1.14.0 (translatable command help: the
 * descriptions and argument labels of every command are seeded into the lang file under a
 * top-level {@code commands} block; SnLang.rawOrNull and SnCommands.applyLang); LEVEL 10 =
 * release 1.15.0 (owner-owned yml sections: the {@code # sn:extensible} and
 * {@code # sn:extensible-root} resource markers stop the always-merge updater from
 * re-inserting entries the server owner deleted; YamlUpdater.EXTENSIBLE_MARKER,
 * EXTENSIBLE_ROOT_MARKER and markerWarnings); LEVEL 11 = release 1.18.0 (config-driven
 * template placement: templates may declare {@code slots:}/{@code key:} against the menu
 * layout, exposed via GuiTemplate.slots()/hasSlots() and consumed by the no-slot
 * GuiSession.bind(String, Ph...)); LEVEL 12 = release 1.20.0 (runtime layout regions: a menu
 * declares named groups of cells under {@code regions:} and
 * GuiSession.bindEach(String, List, BiConsumer) fills one entry per cell, choosing the
 * template and the local placeholders per entry through the new GuiEntry;
 * GuiDef.regionSlots(String) reports the owner-declared cell count); LEVEL 13 = release
 * 1.21.0 (plugin-supplied stacks in menus: GuiSession.bind(int, GuiTemplate, ItemStack,
 * Ph...), PhCollector.stack(ItemStack) and GuiEntry.stack(ItemStack) let a bind render a
 * ready-made ItemStack under the template's display-name/lore overlay, so a menu can show
 * stacks the plugin did not author); LEVEL 14 = release 1.22.0 (per-session menu titles:
 * GuiSession.titlePlaceholders(Ph...) and the Gui.open(Player, Ph...) /
 * Gui.open(Player, int, Ph...) overloads resolve local placeholders into the declared
 * {@code title:}, so a menu whose subject is not its viewer can name that subject);
 * LEVEL 15 = release 1.23.0 (placeholders on an event thread: SnPapi.applyHere resolves
 * PAPI on the calling thread instead of skipping it off the main one, and the viewer-aware
 * SnLang.get(String, Player, Ph...) / getList(String, Player, Ph...) render a spliced
 * fragment for a viewer, so text assembled inside AsyncChatEvent can carry resolved
 * placeholders without blocking a hop back to the main thread); LEVEL 16 = release 1.24.0
 * (a bounded teardown wait: SnFuture.joinWithin(Duration) reports whether the future
 * settled inside a budget instead of blocking forever, and DbConfig.connectTimeoutSeconds
 * / socketTimeoutSeconds bound the JDBC connect and read so an unreachable host cannot
 * outlive that budget).</p>
 */
public final class SnApi {

    /** API level of this build. Bumped by 1 on every release that grows the public API. */
    public static final int LEVEL = 16;

    private SnApi() {
    }
}
