# SnLib

Libreria comun de los plugins Sn, empaquetada como PLUGIN STANDALONE. Un solo
`SnLib.jar` en `plugins/` provee yml, menus, items, comandos, base de datos,
PAPI, lang, debug y el resto de los modulos a todos los consumers, con un unico
estilo de desarrollo y cero dependencias repetidas.

## Modelo standalone hard-depend

- `SnLib.jar` se instala UNA vez en `plugins/` (con `load: STARTUP`).
- Cada consumer declara `depend: [SnLib]` en su `plugin.yml` y compila contra
  `com.sn:snlib` con scope `provided`. Nada de SnLib se shadea en el consumer.
- `SnLib.jar` se adjunta como asset en cada release de cada consumer, para que
  el usuario instale siempre el nivel de API requerido.
- Regla dura: actualizar SnLib.jar requiere restart del server. `/snlib reload`
  JAMAS recarga clases; jamas hot-reload de la lib (classloader compartido con
  ~57 consumers).
- Distribucion SOLO por `.m2` local: `mvn install -f <ruta>/SnLib/pom.xml`
  publica `com.sn:snlib`. JitPack NO soportado (el repo es privado).

## Entrypoint: SnPlugin obligatoria + requiredApiLevel()

La UNICA via de inicializacion es extender `com.sn.lib.SnPlugin` (el init de
`SnLib` es package-private; no existe otro camino):

```java
public final class MyPlugin extends SnPlugin {
    @Override protected int requiredApiLevel() { return SnApi.LEVEL; }
    @Override protected SnSpec buildSpec() {
        return SnSpec.builder()
                .config("config.yml")   // yml managed + update-configs
                .lang()                 // lang/messages_<code>.yml
                .guis()                 // carpeta guis/, un gui por archivo
                .items("items.yml")     // items fisicos por YML (opcional)
                .db()                   // SQLite/MySQL via Hikari
                .debugCommand()         // sub "debug" en los roots propios
                .build();
    }
    @Override protected void onInnerEnable() {
        Sn sn = sn();  // contexto: sn.yml(), sn.guis(), sn.items(), ...
    }
    @Override protected void onInnerDisable() {
        // opcional; el teardown ordenado de la lib corre despues, solo
    }
}
```

`requiredApiLevel()` devuelve `SnApi.LEVEL` inlineado en el bytecode del
consumer al compilar: si el `SnLib.jar` instalado es mas viejo que el nivel
requerido, el consumer se deshabilita limpio con mensaje y URL de descarga,
sin `NoSuchMethodError` ni `NoClassDefFoundError`. Un accessor de un modulo no
declarado en el `SnSpec` tira `UnsupportedOperationException` nombrando el
builder faltante.

## Modulo yml (SnYml + YmlManager)

Toda lectura de YML pasa por `SnYml`: tabs en la indentacion se corrigen con UN
warning (block scalars intactos), getters tipados con default y WARN ante valor
invalido (jamas stacktrace), placeholders locales + PAPI resueltos por getter.

```java
SnYml cfg = sn.yml().config();                  // el config del spec
SnYml shop = sn.yml().managed("shop.yml");      // merge-always contra el jar
SnYml seed = sn.yml().seedOnly("presets.yml");  // solo se copia si falta
SnYml data = sn.yml().data("state.yml");        // datos: jamas se mergea
int max = cfg.getInt("max-uses", 10);           // no numerico -> 10 + WARN
String s = cfg.getString("msg", "hi", viewer);  // overload con viewer (PAPI)
cfg.onReload(() -> recache());
cfg.set("last-run", now); cfg.save();           // async con coalescing
```

- Sin viewer, PAPI resuelve con player null (`%server_online%` funciona); con
  viewer resuelve per-player; en async los tokens PAPI quedan intactos y solo
  aplican placeholders locales.
- `save()` es async con coalescing (a lo sumo un write pendiente por archivo);
  durante el teardown conmuta a write SINCRONO y `flush()` drena lo pendiente.

## Auto-update de configs (YamlUpdater, SIEMPRE-MERGE)

Sin `config-version`: en cada arranque el recurso del jar se compara
estructuralmente contra el archivo en disco y las keys faltantes se insertan en
su posicion anclada, preservando valores del usuario, keys extra y comentarios.

- Backup pre-merge `old-<name>-<timestamp>.yml` keep-last-3, SOLO si hay algo
  que insertar.
- YML corrupto -> se mueve a `<name>.backup-N`, se regenera del jar y WARN;
  jamas crash.
- Boolean maestro `update-configs: true` en el config del consumer; en false
  cuenta las keys faltantes y WARNea sin tocar nada. El config propio esta
  EXENTO del gate (se mergea siempre para que la key pueda llegar por merge).
- Prune opt-in via `sn.yml().managedPruning(path)`: borra keys ausentes del
  recurso; el merge default jamas borra.

## Modulo texto (SnText: MiniMessage + [rgb] + [center])

Pipeline con ORDEN FIJO: locales -> PAPI -> `[rgb]` -> color legacy ->
`[center]` -> render MiniMessage a Component.

- `[rgb]` interpola un gradiente por caracter sobre 7 anclas fijas
  (`#F300F3,#5555FF,#55FFFF,#55FF55,#FCFF21,#FF9B00,#FF5327`); pisa los
  codigos de COLOR preexistentes y PRESERVA el formato (`&l &o &n &m &k`).
- `[center]` centra a 154px midiendo el string legacy ya coloreado; ambos tags
  son componibles en cualquier orden: `[center][rgb]` == `[rgb][center]`.
- Legacy `&a` / `&#RRGGBB` y tags MiniMessage renderizan juntos en la misma
  linea. `SnText.color(String)`, `mini`, `colorLegacy`, `colorList`.

## Modulo menus (GuiManager, carpeta guis/)

Un GUI por archivo en `guis/`, spec golden `docs/menu-example.yml`: cualquier
campo del spec YA funciona sin codigo del plugin.

```java
Gui shop = sn.guis().get("shop");     // guis/shop.yml
shop.open(player);                    // una GuiSession + Inventory POR VIEWER
GuiSession s = shop.session(player);
s.bind(13, shop.template("offer"), Ph.of("price", 100));
s.bindPaged("entry", data, slots, (ph, item) -> ...);  // pagination: true
sn.guis().registerAction("my-tag", (ctx) -> ...);      // accion [custom]
```

- `pagination: true` es OPT-IN por menu: page-state real por jugador (el mismo
  GUI sirve N jugadores en paginas distintas). En `false` las acciones de
  pagina son no-op con nota debug y `bindPaged` WARNea una vez.
- Anti-robo NBT de 7 vectores + `COLLECT_TO_CURSOR` cancelado incondicional
  (anti double-click stacking) + catch-all de `ItemSpawnEvent`: un item de GUI
  jamas circula.
- En reload o disable las GUIs abiertas del consumer se cierran nativamente
  (sin `ClassCastException`); las de otros consumers quedan intactas.

## Modulo items (ItemDef + ItemRegistry)

Items fisicos por YML (spec golden `docs/item-example.yml`) o 100%
programaticos via `ItemDef.builder()` sin ningun archivo.

```java
sn.items().register("wand", ItemDef.builder()
        .item(new SnItem(Material.BLAZE_ROD).name("[rgb]&lWand").glow())
        .locked().noDrop().obtainVia(ObtainMode.COMMAND_ONLY)
        .onRightClick((player, stack) -> ...)
        .build());
sn.items().give(player, "wand", 1);
```

- 8 variantes de interact-actions (right/left x plain/shift/block/air), con
  callback Java opcional por variante.
- `locked`: ninguno de los 7 vectores extrae el item; el item real desplazado
  se restaura en quit y shutdown con backup write-through default-on (sobrevive
  crash sin `onDisable`). Mobs que recogen items registrados -> pickup
  cancelado.
- Durabilidad custom (max, damage-per-use, break-actions, lore-format),
  held-effects (mainhand/offhand/armor), recipes (shaped/shapeless/cooking/
  stonecutting), keep-on-death, cooldown por item.

## Modulo commands (SnCommands)

Arbol root/sub, TODO tab-completable con example/actual value y GATEADO POR
PERMISO: un sub sin permiso es invisible en tab y en help.

```java
sn.commands().root("shop")
        .permission("shop.use")
        .sub("give")
            .permission("shop.admin.give")
            .arg("player", Args.onlinePlayer())
            .arg("amount", Args.intRange(1, 64))
            .executes(ctx -> ...)
        .and()
        .register();   // reload y help por defecto; keys snlib.* mergeadas
```

- Registro reload-safe por Plugin owner: al deshabilitar el consumer sus
  comandos se desregistran y `player.updateCommands()` borra los fantasmas.
- `reload` default delega en el ReloadManager; `debugCommand()` agrega el sub
  `debug`.

## Modulo db (SnDb: SQLite/MySQL via Hikari)

```java
sn.db().bootstrap(Schema.table("players", ...)).orDisablePlugin();
sn.db().query("SELECT ...", st -> st.setString(1, id), rs -> ...)
       .thenSync(result -> ...);          // hop al main con guard isEnabled
sn.db().update("INSERT ...", st -> ...);  // jamas main thread
PlayerDataCache<Stats> cache = sn.db().playerCache(loader, saver);
```

- SQLite: pool=1 + WAL + busy_timeout; MySQL: pool Hikari clasico. Drivers
  shadeados SIN relocar (JNI/binarios), copia UNICA en el server.
- `join()` solo permitido en bootstrap/teardown; shutdown joinea los writes y
  hace `shutdownNow` tras timeout.

## Modulo papi (SnPapi)

- `sn.papi().apply(viewer, text)`: con PlaceholderAPI ausente devuelve el
  string INTACTO, sin `NoClassDefFoundError` (hook en clase aislada).
- Expansiones declarativas con `persist true`:
  `sn.papi().expansion("shop").resolver((player, params) -> ...).register()`.
- Hook reactivo: si PlaceholderAPI se habilita/deshabilita en vivo, el bridge
  se activa/invalida solo.

## Modulo lang (SnLang)

- `lang/messages_<code>.yml` con merge-always y fallback por key a `en` + WARN
  una vez; keys compartidas `snlib.*` (no-permission, player-only, usage...)
  mergeadas en todos los idiomas.
- `sn.lang().send(player, "shop.bought", Ph.of("item", name))`, `broadcast`,
  `actionbar`, `title` (formato `title;subtitle;fadeIn;stay;fadeOut`),
  `get`/`getList` para Components; todo por el pipeline SnText.

## Modulo debug (SnDebug)

- Toggle en runtime sin reinicio: `/comando debug` (con `debugCommand()`),
  persistido en el config si hay yml; categorias y `Supplier` lazy para no
  construir strings caros con debug apagado:
  `sn.debug().log(() -> "state=" + expensive())`.

## Scheduler (SnScheduler, Folia-aware)

- `sync/async/syncLater/asyncLater/timer/timerAsync/supplyAsync/thenSync` con
  `TaskHandle.cancel()`; `thenSync` hace hop al main con guard
  `plugin.isEnabled()`.
- Claim Folia HONESTO: deteccion + no-crash (schedulers globales/region); los
  modulos GUI/items estan validados solo en Paper.

## Actions y Requirements (ActionEngine + RequirementEngine)

- Lineas `[tag] argumento` en YML: `[player]`, `[player-as-op]`, `[console]`,
  `[message]`, `[sound]`, `[close]`, `[open]`, `[connect]`,
  `[broadcastmessage]`, `[actionbar]`, `[title]`, filtros por tipo de click,
  `[next-page]`/`[previous-page]`/`[set-page]`/`[refresh-page]`,
  `[refresh-menu]`, `[particle]`, `[potion]`, `[remove-item]` y tags custom via
  `sn.actions().register("tag", handler)`.
- Requirements: `%placeholder% > 0 && %placeholder% < 10`, `=`, `!=`, `>=`,
  `<=`, sobre placeholders PAPI o locales; `view-requirements`,
  `click-requirements`, `interact-requirements` + `deny-actions`.

## Cooldowns, Economy y utils

- `sn.cooldowns().tryUse(uuid, "kit", Duration.ofMinutes(5))` sin boxing;
  entradas no expiradas sobreviven relogs POR DISENIO; categorias de sesion via
  `registerSessionCategory`.
- `sn.economy()`: Vault si esta, si no command-backend configurable
  (`useCommandBackend(give, take, balancePlaceholder)`); `getBalance`, `give`,
  `tryTake` async-safe.
- Utils puros: `SlotParser` (rangos mixtos), `TimeUtil` (`1d 2h 30m 15s`),
  `NumberFormatter` (K/M/B/T/Qa/Qi + parse inverso), `LocationSerializer`,
  `WeightedRandomPool`, `Experience`, `MathUtil` (fair rounding,
  `convertToRoman`), `Page<T>`. Bukkit: `SoundUtil` (ids lenient),
  `HeadUtil` (base64/basehead/URL, cache LRU acotado), `TagIo` (PDC por
  owner), `InvUtil`.

## Bossbars, Holograms, Cron, Leaderboards, Discord

- `sn.bossbars().create("raid").text("[rgb]Raid").progress(1f).build()` +
  `show/hide/setText/setProgress/timer`; Adventure puro (cero packets),
  auto-hide en quit y en el teardown.
- `sn.holograms().spawn("top", loc, lines)`: entidades TextDisplay reales
  (cero NMS), `setLines`, visibilidad por jugador, refresh PAPI opcional;
  huerfanas purgadas por chunk-load y al arrancar via marca PDC
  `snlib_hologram`.
- `sn.cron().schedule("payout", "0 4 * * *", task)`: subset cron de 5 campos +
  atajos `daily 04:00` / `hourly :30`, DST-safe via ZonedDateTime, `catchUp`
  persistible.
- `sn.leaderboards().register("kills", Duration.ofMinutes(5), query)`:
  snapshot inmutable con swap atomico, `getTop/positionOf/valueOf` lock-free,
  placeholders `top_<id>_<n>_name/value` y `pos_<id>` opt-in.
- `sn.discord().message(url).content("...").embed(...).send()`: POST async
  con `java.net.http.HttpClient`, cola FIFO con respeto de `Retry-After`;
  `drain()` best-effort en el teardown.

## Matriz de campos de los specs golden

Contrato de aceptacion: si el usuario configura un campo soportado por el
spec, YA funciona sin codigo del plugin.

| Spec | Campos |
|------|--------|
| `docs/menu-example.yml` | title, rows, open-sound, update-interval, inventory-type, pagination; por item: display-name, material (basehead), custom-model-data, amount, slots, glow, enchantments, flags (HIDE_ALL), color, trim-pattern, trim-material, potion-effects, update-interval, lore, view/click-requirements, click/deny-actions, nav items con nav-disabled; templates sin slots; [rgb]/[center]/MiniMessage en cualquier string |
| `docs/item-example.yml` | display-name, material, custom-model-data, amount, glow, lore, enchantments, flags, color, trim-pattern, trim-material, potion-effects, unbreakable, max-stack-size, droppable, moveable, placeable, tradeable, despawnable, keep-on-death, cooldown, locked, no-drop, no-manual-equip, obtain-via, custom-durability (max/damage-per-use/break-actions/lore-format), 8 listas *-click-actions, interact-requirements, deny-actions, pickup/drop-actions, held-effects (mainhand/offhand/armor), equipment-slot, recipe (7 tipos) |

Los headers de `GuiDef.java` e `ItemDef.java` llevan el checklist campo por
campo con el punto exacto de parseo.

## Compatibilidad

- Floor de runtime: 1.20.4. Target: 1.21.8. Versiones 1.22+ desconocidas
  arrancan con WARN forward, jamas hard-fail.
- Java 21 OBLIGATORIO: floor 1.20.4 requiere JVM Java 21; los classfiles son
  release 21 y con JVM 17 falla con `UnsupportedClassVersionError` antes de
  cualquier probe.
- Cero NMS/packets: 100% API de Paper y Adventure. Nada referencia
  `InventoryView`. Toda API posterior a 1.20.4 (setMaxStackSize, glint
  override) degrada con UN WARN via `SnCompat.probe`.
- Enums abiertos: Sound/Particle/ItemFlag se resuelven por `valueOf`
  individual con catch, jamas switch/EnumSet; alias lenient
  `HIDE_POTION_EFFECTS` <-> `HIDE_ADDITIONAL_TOOLTIP` y `REDSTONE` <-> `DUST`.

## Threading

- PAPI SOLO en main thread; en async los tokens quedan intactos (nota debug).
- `join()` permitido SOLO en onDisable/bootstrap (`SnFuture.join` lo verifica
  con el flag de teardown).
- I/O sincrono SOLO en onEnable y en el comando reload (excepcion declarada);
  todo el resto es async con coalescing y flush sincrono en teardown.
- `thenSync` con guard `isEnabled()`: jamas `IllegalPluginAccessException` en
  el apagado.

## Contrato de NO-interferencia multi-tenant

Todo registro de la lib (contextos, GUIs, items, comandos, hooks, bossbars,
holograms, cron, leaderboards) esta keyed por Plugin owner en TenantRegistry;
el sweeper remueve la KEY completa cuando un consumer se deshabilita (PlugMan
incluido). El reload/disable de un consumer JAMAS toca estado de otro consumer
ni de la lib. Statics sin namespace solo para datos server-wide
(SnVersion/SnCompat, dedup de WARNs). Los 11 listeners compartidos viven en
ListenerHub y se registran UNA sola vez en el bootstrap de SnLibPlugin.

## Comando /snlib

`/snlib version` (lib + API-level + MC), `/snlib plugins` (consumers
enganchados), `/snlib integrations` (SoftDependency activas), `/snlib
iteminfo` (dump PDC del item en mano), `/snlib reload [plugin]` (sin args solo
la superficie propia; con plugin delega en el ReloadManager de ese plugin).
Permisos `snlib.admin.*` (default op).

## Smoke QA v1.0.0 (gate ejecutado)

Gate ejecutado sobre el jar construido, en Paper local con JVM Java 21:

- Paper 1.21.8 (build 60): arranque SIN errores ni excepciones,
  `SnLib 1.0.0 enabled (API level 1)`; `/snlib version` responde
  `SnLib version: 1.0.0 / API level: 1 / Server: 1.21.8`; `/snlib plugins` e
  `/snlib integrations` responden; disable y stop limpios.
- Paper 1.20.4 (build 499, floor): arranque SIN errores, deteccion `1.20.4`,
  `/snlib version` responde, cero `NoSuchMethodError`/`NoClassDefFoundError`,
  disable limpio. El WARN de degradacion (setMaxStackSize/glint) solo se
  dispara cuando un consumer construye un item que ejercita esos probes; la
  lib sola no tiene ninguno que disparar, queda cubierto por los pilotos.
- Hallazgo corregido por el gate: con Vault ausente, el backend Vault (clase
  aislada) no linkea; `EconomyBridge` lo instancia bajo catch de `Throwable` y
  arranca sin ese backend.
- Procedimiento reproducible: copiar `target/SnLib-1.0.0.jar` a `plugins/`,
  arrancar Paper con Java 21 (`java -jar paper.jar nogui`), correr
  `snlib version` en consola y revisar el log completo.

## Ruta de adopcion

1. Release v1.0.0 + `mvn install` al `.m2` local.
2. Pilotos post-release: SnTags (simple) y SnCrates (complejo); el smoke
   manual documentado es el QA de los pilotos.
3. Canary de `SnLib.jar` en UNA modalidad 48h antes del resto.
4. Migracion del resto de los ~57 plugins fuera de este plan.

## Desarrollo

- Templates de consumer en `docs/`: `consumer-pom-template.xml` (pom minimo,
  scope provided) y `snlib-consumer-rules.pro` (reglas ProGuard).
- Specs golden de configuracion en `docs/menu-example.yml` (GUIs) y
  `docs/item-example.yml` (items fisicos).
- API publica congelada bajo semver: japicmp additive-only (baseline real
  desde 1.0.1); paquetes `*.internal` fuera de contrato; `SnApi.LEVEL` se
  incrementa +1 en cada release que agrega API publica.
