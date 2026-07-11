# SnLib v1.0.0 - Documentacion tecnica del estado actual

> Generada el 2026-07-10 contra el codigo real del repo (commit HEAD de main).
> Cobertura: todas las clases de `src/main/java/com/sn/lib` (106 archivos java), recursos, build y tests (12 suites).

**Resumen del proyecto:** SnLib es el plugin standalone base de los ~57 plugins Sn: un solo `SnLib-1.0.0.jar` en `plugins/`, consumers con `depend: [SnLib]` y scope provided. Java 21, floor 1.20.4, target 1.21.8, forward 1.22+ con WARN. 137 tests JUnit verdes en 12 suites; smoke gate verde en Paper 1.21.8 y 1.20.4; 38/38 pasos del plan ejecutados en 46 commits atomicos.

## Indice

- [01. Arquitectura y ciclo de vida](#01-arquitectura-y-ciclo-de-vida)
- [02. Compat multi-version](#02-compat-multi-version)
- [03. Pipeline de texto](#03-pipeline-de-texto)
- [04. YML: lectura, preprocesado y auto-update](#04-yml-lectura-preprocesado-y-auto-update)
- [05. Lang y Debug](#05-lang-y-debug)
- [06. Scheduler, SoftDependency y Cron](#06-scheduler-softdependency-y-cron)
- [07. Utils](#07-utils)
- [08. Acciones, Requirements y PAPI](#08-acciones-requirements-y-papi)
- [09. Multi-tenant, cleanup y reload](#09-multi-tenant-cleanup-y-reload)
- [10. Eventos custom](#10-eventos-custom)
- [11. Items](#11-items)
- [12. GUI](#12-gui)
- [13. Comandos](#13-comandos)
- [14. Base de datos y Economia](#14-base-de-datos-y-economia)
- [15. BossBars, Hologramas, Leaderboards y Discord](#15-bossbars-hologramas-leaderboards-y-discord)
- [16. Build, tests, specs golden y TODOs](#16-build-tests-specs-golden-y-todos)

---
## 01. Arquitectura y ciclo de vida

SnLib es un plugin standalone cargado una sola vez (load: STARTUP) y compartido por todos los consumidores via classloader compartido; cada registry que guarda datos de plugin, jugador o sesion DEBE estar keyeado por el `Plugin` dueño (los statics sin namespace de owner solo se permiten para datos server-wide). El unico camino publico de inicializacion es extender `SnPlugin`, que ejecuta el handshake de API level contra el jar instalado y llama al `SnLib.init` package-private; el contexto resultante (`Sn`) monta en una sola llamada todos los modulos declarados en el `SnSpec` del consumidor y se desmonta con `Sn.shutdown()` en un orden estricto de 13 pasos. Los paquetes `*.internal` quedan fuera del contrato semver. Piso de runtime 1.20.4, target 1.21.8, Java 21, 100% Paper/Adventure API (NMS, packets e InventoryView prohibidos); versiones desconocidas (1.22+) arrancan con WARN y nunca hard-failean.

### SnLib
`src/main/java/com/sn/lib/SnLib.java`

Punto de entrada y registro de contextos de la libreria. Clase `final` con constructor privado; mantiene el static server-wide `CONTEXTS` (un `LinkedHashMap<Plugin, Sn>` sincronizado a mano) justificado por ser el unico registro de contextos: keyeado por plugin dueño y con orden de insercion para que la cascada del sweeper apague en orden inverso de registro. Un bloque `static {}` llama `TenantSweeper.bindContexts(new ContextAccessImpl())` al cargar la clase.

- `static Sn init(JavaPlugin plugin, SnSpec spec)` - (package-private A PROPOSITO) crea y registra el contexto de un consumidor, montando todo lo que declara su spec en una llamada: config administrada (con el gate `update-configs` sembrado), lang, la carpeta `guis/` con su load, el archivo de items, el modulo de base de datos y el comando de debug de runtime. Idempotente por owner: un doble init devuelve el contexto vivo existente con el WARN "SnLib.init doble: se devuelve el contexto existente sin re-montar modulos" en vez de montar un segundo. Ante una race en el `putIfAbsent` final devuelve el contexto que gano la carrera.
- `static void detach(Plugin owner, Sn ctx)` - (package-private) quita la key del contexto de un owner solo si todavia mapea a `ctx` (`CONTEXTS.remove(owner, ctx)`); es el paso final de `Sn.shutdown()` e idempotente con el detach propio del tenant sweeper.
- `public static @Nullable Sn context(JavaPlugin plugin)` - contexto de un plugin consumidor, o `null` si ese plugin nunca se inicializo contra SnLib.

#### Logica interna

- `ContextAccessImpl` (clase privada estatica, implementa `TenantSweeper.ContextAccess`): acceso que usa el tenant sweeper para desmontar keys de contexto sin ensanchar la API publica.
  - `boolean detach(Plugin owner, Sn expected)` - remove condicional (owner -> expected) bajo el lock de `CONTEXTS`; devuelve si removio.
  - `List<Sn> detachAllReversed()` - copia todos los contextos vivos, limpia el mapa y devuelve la lista invertida (orden inverso de registro) para la cascada cuando SnLib se deshabilita.

#### Notas y gotchas

- `init` es package-private a proposito: no existe camino publico alternativo de inicializacion; extender `com.sn.lib.SnPlugin` es la unica via, y esa clase hace el handshake de API level ANTES de llamar aca.
- El tenant sweeper actua como red doble para owners que nunca hacen shutdown, y cascadea todos los contextos vivos, en orden inverso de registro, cuando SnLib mismo se deshabilita.
- Contrato arquitectonico documentado en el Javadoc de la clase: (a) classloader compartido con registries keyeados por owner, (b) paquetes `*.internal` fuera del semver, (c) compatibilidad 1.20.4 a 1.21.8 con `Sound`/`Particle`/`ItemFlag` tratados como sets abiertos (nunca switch/EnumSet sobre ellos), aliases lenientes con WARN (`HIDE_POTION_EFFECTS` -> `HIDE_ADDITIONAL_TOOLTIP`, `REDSTONE` -> `DUST`) y toda API posterior a 1.20.4 solo detras de `SnCompat.probe`, (d) entrypoint congelado: `SnPlugin` + `requiredApiLevel()` + `SnSpec` + `SnApi.LEVEL` nunca cambian dentro de una version mayor.

### SnLibPlugin
`src/main/java/com/sn/lib/SnLibPlugin.java`

Plugin de bootstrap del runtime de SnLib. Cargado en STARTUP antes que todo consumidor; punto unico que registra los listeners compartidos y el lado runtime del handshake de API level de los consumidores. Es dueño de su propio contexto sobre `plugins/SnLib/config.yml` (debug de la libreria mas el opt-out de bStats), creado por el mismo `SnLib.init` package-private que atraviesan todos los consumidores.

Constantes: `private static final int BSTATS_SERVICE_ID = 26887` (id de servicio en bstats.org). Estado: `private static volatile @Nullable SnLibPlugin instance`, mas los campos de instancia `selfCtx` y `metrics` (ambos nullable).

- `public static SnLibPlugin get()` - bootstrap de SnLib en ejecucion. Los consumidores nunca lo llaman directo; `SnPlugin` lo usa para el handshake, garantizado presente por `depend: [SnLib]` mas `load: STARTUP`. Si `instance` es null lanza `IllegalStateException("SnLib no esta habilitado: el consumer necesita depend: [SnLib]")`.
- `public int apiLevel()` - API level del SnLib.jar instalado: devuelve `SnApi.LEVEL` tal como quedo inlined en ESTE jar al compilarlo, comparado contra el `requiredApiLevel()` del consumidor.
- `public @Nullable Sn selfContext()` - contexto de la libreria misma, o `null` mientras esta deshabilitada.
- `public void onEnable()` - bootstrap: ver "Logica interna".
- `public void onDisable()` - `TenantSweeper.cascadeAll()` (apaga todo contexto vivo en orden inverso de registro), `metrics.shutdown()` si habia bStats activo (y lo nullea), `HeadUtil.clearCache()`, nullea `selfCtx` e `instance`.

#### Logica interna

Orden exacto del bootstrap (`onEnable`):
1. `instance = this` (habilita `get()` para el handshake de consumidores).
2. `logDetectedVersion()` - fuerza la inicializacion de la clase `SnVersion` (que WARNea una sola vez ante versiones forward desconocidas y nunca hard-failea) y loguea "Servidor detectado: X.Y[.Z]" con sufijo " (Folia)" cuando corresponde.
3. `ListenerHub.registerAll(this)` - registra los listeners compartidos de la libreria.
4. `Sn ctx = SnLib.init(this, buildSelfSpec())` - crea el contexto propio (`selfCtx`) con spec `SnSpec.builder().config("config.yml").debugCommand().build()` (solo config + comando de debug; sin lang/guis/items/db).
5. `SnLibCommand.register(this, ctx)` - registra el comando administrativo `/snlib`.
6. Si `ctx.yml().config().getBoolean("bstats", true)` esta activo, crea `new Metrics(this, 26887)`.
7. `ctx.scheduler().sync(this::purgeOrphanHolograms)` - scan de arranque de hologramas huerfanos, diferido al primer tick: SnLib se habilita en STARTUP antes de que cargue mundo alguno y antes de que ningun consumidor registre sus hologramas; para el primer tick ambas cosas ya pasaron y todo TextDisplay marcado sin registro vivo es sobra de una corrida anterior (`HologramChunkListener.purgeLoadedWorlds()`; si purgo > 0 loguea "Purgados N hologramas huerfanos de arranques previos").
8. Log final: `"SnLib <version> enabled (API level " + SnApi.LEVEL + ")"`.

### SnPlugin
`src/main/java/com/sn/lib/SnPlugin.java`

Clase base obligatoria de todo consumidor de SnLib y UNICO camino de inicializacion de la libreria: como `SnLib.init` es package-private, extender esta clase es la unica via publica de obtener un contexto. Parte del entrypoint congelado (esta superficie nunca cambia dentro de una version mayor). Los consumidores deben declarar `depend: [SnLib]` en su plugin.yml. Clase `abstract` que extiende `JavaPlugin`; `onEnable()` y `onDisable()` son `final`.

- `protected abstract int requiredApiLevel()` - implementarlo EXACTAMENTE como `return SnApi.LEVEL;` - inline-a el API level de compilacion del consumidor (ver handshake abajo).
- `public final void onEnable()` - ejecuta el handshake de API level; si pasa, hace `this.sn = SnLib.init(this, buildSpec())` y luego llama `onInnerEnable()` dentro de un try/catch de `Throwable`: ante cualquier fallo loguea SEVERE "onInnerEnable fallo" con el stacktrace y deshabilita el plugin via `PluginManager.disablePlugin(this)`.
- `public final void onDisable()` - llama `onInnerDisable()` y, en un `finally` (o sea incluso si el disable del consumidor tira), ejecuta `sn.shutdown()` si el contexto existe.
- `protected SnSpec buildSpec()` - modulos que declara este consumidor; se sobreescribe para opt-in (`SnSpec.builder().config("config.yml").lang()...build()`). El default (`SnSpec.builder().build()`) no declara ningun modulo opcional.
- `protected abstract void onInnerEnable()` - logica de enable del consumidor; corre despues del handshake y de la inicializacion del contexto.
- `protected void onInnerDisable()` - logica de disable del consumidor; corre antes del teardown del contexto. Opcional (implementacion vacia por defecto).
- `public final Sn sn()` - contexto SnLib de este plugin; disponible desde `onInnerEnable()` en adelante.

#### Handshake de API level (bytecode-side)

Mecanismo completo, tal como esta implementado:

1. `SnApi.LEVEL` es un `public static final int` (constante de compilacion). Al compilar el consumidor, javac inline-a el literal en el class file DEL CONSUMIDOR: el `return SnApi.LEVEL;` del `requiredApiLevel()` del consumidor queda congelado con el nivel contra el que se compilo, no con el del jar instalado en runtime.
2. En `onEnable()`, `SnPlugin` obtiene `int installed = SnLibPlugin.get().apiLevel()` - que devuelve el `SnApi.LEVEL` inlined en el SnLib.jar INSTALADO - y `int required = requiredApiLevel()`.
3. Si `installed < required`, loguea SEVERE: `"Requiere SnLib API level " + required + " (instalado: " + installed + "). Actualiza SnLib.jar (requiere restart): https://github.com/ValentinTarnovsky/SnLib/releases"` y se deshabilita a si mismo con `disablePlugin(this)`, con `return` inmediato (nunca llega a `SnLib.init`).
4. Resultado: un consumidor compilado contra un API level mas nuevo que el jar instalado se apaga limpio con mensaje de actualizacion en vez de fallar mas tarde con `NoSuchMethodError` o `NoClassDefFoundError`.

La presencia de `SnLibPlugin.get()` en el paso 2 esta garantizada por `depend: [SnLib]` del consumidor mas el `load: STARTUP` de SnLib; si falta, `get()` tira `IllegalStateException` con mensaje explicativo.

### Sn
`src/main/java/com/sn/lib/Sn.java`

Contexto SnLib por plugin: el handle por el que un consumidor llega a cada modulo que declaro en su `SnSpec`. Clase `final`; constructor package-private `Sn(JavaPlugin plugin, SnSpec spec)` que cablea TODOS los modulos en construccion (los declarados quedan montados; los no declarados quedan `null` solo en los 4 modulos gateados). Parte de la superficie del entrypoint congelado: su set de accessors solo crece dentro de una version mayor. Campo `volatile boolean shuttingDown` (package-private): lo setea el teardown antes que nada y flipea `SnYml.save()` a escrituras sincronicas.

Accessors SIEMPRE disponibles (nunca tiran, esten o no declarados modulos):

- `public JavaPlugin plugin()` - plugin consumidor dueño de este contexto.
- `public SnScheduler scheduler()` - scheduler Folia-aware atado al plugin dueño.
- `public SnDebug debug()` - servicio de debug de runtime; los toggles persisten a la config principal cuando el modulo yml esta declarado, en memoria si no.
- `public SnPapi papi()` - puente PlaceholderAPI; todo apply devuelve el texto intacto cuando PlaceholderAPI esta ausente.
- `public ActionEngine actions()` - motor de acciones; corre listas YML de la forma `[tag] argumento` y acepta tags custom via `ActionEngine.register`.
- `public Cooldowns cooldowns()` - store de cooldowns; las entradas no expiradas sobreviven relogs por diseño; solo las categorias registradas como de sesion se resetean en quit/kick.
- `public EconomyBridge economy()` - puente de economia; resuelve Vault si esta, despues el backend por comandos configurado via `EconomyBridge.useCommandBackend`; sin backend disponible WARNea una vez y reporta fallo.
- `public BossBarUtil bossbars()` - boss bars Adventure (cero packets) con titulos por el pipeline SnText; viewers que se van se dropean solos y el teardown esconde todas las barras de este owner.
- `public HologramUtil holograms()` - hologramas como entidades TextDisplay reales marcadas en el PDC; el teardown borra las entidades de este owner y la libreria purga markers huerfanos en chunk load y al arranque.
- `public SnCron cron()` - jobs en main thread en los instantes calendario de un subset cron de 5 campos o los shortcuts daily/hourly, re-armados tras cada corrida; los jobs con `catchUp(true)` persisten su ultima corrida a un data yml y disparan una corrida perdida en el proximo schedule.
- `public LeaderboardCache leaderboards()` - cada board corre su query async a intervalo fijo y swapea un snapshot inmutable detras de una referencia volatile: getTop/positionOf/valueOf son lecturas de cache lock-free seguras para resolvers de PlaceholderAPI.
- `public DiscordWebhook discord()` - los mensajes encolan FIFO y postean async por el HttpClient del JDK, honrando Retry-After en 429; el teardown drena lo que quede encolado.
- `public ItemRegistry items()` - registro de items; funciona con cero archivos: definiciones desde `ItemDef.builder()`, secciones YML o el archivo declarado via `SnSpec.builder().items(...)`.
- `public SnCommands commands()` - modulo de comandos; los roots construidos aca inyectan subcomandos reload y help por defecto, tab-complete gateado por permiso, y se desregistran en shutdown.
- `public ReloadManager reload()` - orquestador de reload; reconstruye los modulos declarados en orden estricto y re-despacha los reloadables registrados via `ReloadManager.register`; el subcomando `reload` default y `/snlib reload <plugin>` delegan aca.
- `public boolean isShuttingDown()` - true desde que arranco el teardown de este contexto; el I/O de modulos debe pasar a sincronico.

Accessors CON GATE de spec (tiran `UnsupportedOperationException` nombrando la llamada de builder faltante si el modulo no fue declarado):

- `public YmlManager yml()` - manager yml del plugin dueño (archivos managed/seedOnly/data mas la config principal montada). Gate: mensaje "Modulo yml no declarado: falta SnSpec.builder().config(\"config.yml\")".
- `public SnLang lang()` - modulo de idioma: `lang/messages_<code>.yml` con las keys compartidas `snlib.*` siempre mergeadas y fallback ingles por key. Gate: "Modulo lang no declarado: falta SnSpec.builder().lang()".
- `public GuiManager guis()` - modulo GUI: carpeta `guis/` con una GUI por archivo, una sesion e inventario por viewer, y paginacion opt-in por menu. Gate: "Modulo guis no declarado: falta SnSpec.builder().guis()".
- `public SnDb db()` - modulo de base de datos: dual SQLite/MySQL sobre un pool Hikari con toda query y update fuera del main thread. Gate: "Modulo db no declarado: falta SnSpec.builder().db()".

Otros metodos:

- `SnSpec spec()` - (package-private) declaracion de modulos con la que se inicializo este contexto.
- `public void shutdown()` - apaga cada modulo de este contexto y libera sus registraciones, en orden estricto que nunca pierde una escritura pendiente. Idempotente: solo la primera llamada corre el teardown. Ver orden exacto abajo.
- `public void reloadAll()` - recarga cada modulo de este contexto; delega en `reload.reloadPlugin()`.

#### Logica interna: orden de construccion

El constructor cablea en este orden: scheduler, papi, yml (null si `spec.config() == null`), debug (recibe la config o null), actions, lang (solo si `spec.lang()`), cooldowns, economy, bossbars, holograms, cron, leaderboards, discord, items. Si el spec declara items con archivo y HAY yml, hace `items.loadAll(yml.managed(itemsFile))`; si declara items SIN config, WARNea: `items("...") declarado sin config(): el archivo no se monta y sn.items() queda solo programatico`. Despues guis (solo si `spec.guis()`, con `guis.load()` inmediato), db (solo si `spec.db()`, con `DbConfig.load(plugin, yml.config().getSection("database"))` o seccion null sin yml), commands (`new SnCommands(this, lang, spec.debugCommand())`) y reload.

#### Logica interna: los 13 pasos de shutdown()

Si `shuttingDown` ya era true, retorna sin hacer nada (idempotencia). Si no, ejecuta exactamente:

- Paso 0 (pre-paso): `shuttingDown = true` ANTES que todo lo demas: `SnYml.save()` pasa a escribir inline y `SnFuture.join` acepta el thread del teardown. Asi toda persistencia hecha dentro del propio teardown (equipment backups, debug toggles, data files) escribe SYNC en el thread llamante en vez de pasar por un scheduler a punto de cancelarse.
1. `guis.closeAll()` (si guis declarado) - cierra las GUIs abiertas de este owner; cada sesion por viewer cancela sus timers, destrackea su holder y fuerza el cierre a su viewer.
2. `commands.unregisterAll()` - desregistra los command roots de este owner y refresca los arboles de comandos de los clientes.
3. `yml.flushAll()` (si yml declarado) - drena las escrituras yml async coalescidas ANTES de cancelar el scheduler que las correria.
4. `items.cancelTasks()` y `scheduler.cancelAll()` - recien ahora cancela toda task restante del plugin dueño.
5. `EquipmentBackup.restoreAll(plugin)` - locked items: devuelve el equipamiento real desplazado; el store write-through persiste sincronico gracias al flag `shuttingDown`.
6. `db.flushPlayerCaches()` (si db declarado) - caches de jugador: guarda toda entrada dirty y joinea las escrituras...
7. `db.shutdown()` (si db declarado) - ...y despues cierra el pool (joinea trabajo pendiente, `shutdownNow` tras timeout).
8. `RecipeLoader.unregisterAll(plugin)` - saca del servidor las recipe keys de este owner.
9. `cooldowns.clearAll()` - store de cooldowns de este owner.
10. `SoftDependency.forEachRegistered(...)` con `hook.forceDisable()` solo para hooks cuyo `owner() == plugin`, mas `papi.unregisterAll()` - integraciones propias: fuerza el disable de los hooks de soft-dependency de este owner y desregistra sus expansiones de PlaceholderAPI.
11. `actions.shutdown()` - libera el canal saliente de BungeeCord si `[connect]` lo registro.
12. `bossbars.hideAll()`, `holograms.deleteAll()`, `discord.drain()` - hooks de teardown de los modulos extra, antes del removeOwner generico: esconde las bossbars de este owner, borra sus hologramas TextDisplay para que no queden como huerfanos hasta la proxima purga de arranque, y drena los webhooks Discord encolados best-effort bajo un deadline corto.
13. `TenantRegistry.sweepOwner(plugin)` y `SnLib.detach(plugin, this)` - saca la key de este owner de TODOS los tenant registries y desmonta el contexto del registro global.

#### Notas y gotchas

- No-interferencia: `shutdown()` y `reloadAll()` operan SOLO sobre estado propiedad de este plugin; los contextos de otros consumidores nunca se tocan.
- El orden 3-antes-de-4 es deliberado: drenar escrituras yml antes de cancelar el scheduler evita perder writes coalescidos pendientes.
- Politica de accessors: 4 modulos gateados por spec (`yml`, `lang`, `guis`, `db`) y el resto siempre disponible; todo modulo declarado se cablea en construccion, asi que ningun accessor de un modulo declarado tira jamas.

### SnSpec
`src/main/java/com/sn/lib/SnSpec.java`

Declaracion inmutable de los modulos SnLib que usa un plugin consumidor. Parte del entrypoint congelado: la superficie del builder nunca cambia dentro de una version mayor. Todo lo declarado se monta con UNA llamada de init cuando el consumidor se habilita (config administrada sembrada y mergeada con el gate `update-configs`, lang y `guis/` cargan, el archivo de items registra sus definiciones, la db levanta y el subcomando debug se inyecta); el teardown correspondiente corre automatico cuando el consumidor se deshabilita.

- `public static Builder builder()` - crea un nuevo builder de spec.
- `public @Nullable String config()` - nombre del archivo de config principal administrado, o `null` si el modulo config no fue declarado.
- `public boolean lang()` - si se declaro el modulo lang (messages_en.yml).
- `public boolean guis()` - si se declaro el modulo guis (carpeta `guis/`).
- `public @Nullable String items()` - nombre del archivo de items, o `null` si el modulo items no fue declarado con fuente YML.
- `public boolean db()` - si se declaro el modulo de base de datos.
- `public boolean debugCommand()` - si se declaro el comando de debug de runtime.

#### SnSpec.Builder (clase anidada publica)

Builder de `SnSpec`; cada metodo es opt-in, los modulos omitidos quedan deshabilitados. Constructor privado (solo via `SnSpec.builder()`).

- `public Builder config(String fileName)` - declara el archivo de config principal administrado (por ejemplo `"config.yml"`).
- `public Builder lang()` - declara el modulo lang.
- `public Builder guis()` - declara el modulo guis.
- `public Builder items(String fileName)` - declara el modulo items respaldado por un archivo YML (por ejemplo `"items.yml"`).
- `public Builder db()` - declara el modulo de base de datos.
- `public Builder debugCommand()` - declara el comando de debug de runtime.
- `public SnSpec build()` - construye el spec inmutable.

### SnApi
`src/main/java/com/sn/lib/SnApi.java`

API level publico de este build de SnLib. Clase `final` con constructor privado; una sola constante publica:

- `public static final int LEVEL = 1` - API level de este build. Politica: sube exactamente 1 en CADA release que agregue metodos o clases publicas a la API; la superficie esta congelada bajo un gate japicmp additive-only. Es el numero contra el que todo consumidor hace handshake via `SnPlugin#requiredApiLevel()`: el nivel requerido queda inlined en el bytecode del consumidor en compile time, asi que un consumidor compilado contra un nivel mas nuevo que el SnLib.jar instalado se deshabilita limpio en vez de fallar con `NoSuchMethodError`.

### Ph
`src/main/java/com/sn/lib/Ph.java`

Record publico `Ph(String key, String value)`: par de placeholder local resuelto por el pipeline de texto ANTES de PlaceholderAPI. `key` es el nombre del placeholder sin delimitadores (se matchea como `%key%` y como `{key}`); `value` es el texto de reemplazo.

- `public static Ph of(String key, Object value)` - crea un par desde cualquier valor via `String.valueOf(Object)`.

### plugin.yml
`src/main/resources/plugin.yml`

Descriptor Bukkit del plugin bootstrap. Valores exactos:

- `name: SnLib`, `main: com.sn.lib.SnLibPlugin`, `version: ${project.version}` (filtrado por Maven), `author: ValentinTarnovsky`, `api-version: '1.20'`.
- `load: STARTUP` - clave del ciclo de vida: SnLib se habilita antes que todos los consumidores (que declaran `depend: [SnLib]`), garantizando que `SnLibPlugin.get()` funcione durante el handshake de cada consumidor.
- `description: Common library core for Sn plugins (standalone hard-depend).`
- `softdepend: [PlaceholderAPI, Vault]` - integraciones opcionales; ambas se degradan con gracia si faltan.
- Comando `snlib`: "SnLib administration command.", usage `/snlib <version|plugins|integrations|iteminfo|reload>`.
- Permisos (todos `default: op`): `snlib.admin` (padre; otorga todos los subcomandos admin, con children `snlib.admin.version`, `snlib.admin.plugins`, `snlib.admin.integrations`, `snlib.admin.iteminfo`, `snlib.admin.reload`, todos en true) mas los cinco permisos hijos individuales, uno por subcomando.

### config.yml
`src/main/resources/config.yml`

Config administrada de SnLib mismo (montada en `plugins/SnLib/config.yml` por el self-context). Es una config "managed": las keys que agreguen versiones futuras se mergean en cada arranque preservando valores y keys extra del usuario. Claves:

- `update-configs: true` - gate maestro del updater always-merge: en `false` saltea todo merge de yml excepto este archivo.
- `debug.enabled: false` - toggle maestro del output de debug de la libreria (tambien toggleable en vivo, sin restart).
- `debug.level: DEBUG` - umbral de verbosidad: `OFF`, `INFO`, `DEBUG` o `TRACE`.
- `debug.categories: []` - filtro de categorias; lista vacia deja pasar todas.
- `bstats: true` - metricas anonimas via bStats (https://bstats.org); `false` para opt-out (leido en `SnLibPlugin.onEnable` antes de crear `Metrics`).

### TODOs y limitaciones

Ninguno. No hay marcadores TODO/FIXME/XXX en ninguno de los archivos de este alcance. Limitaciones de diseño documentadas en el codigo (no pendientes, decisiones): `items(...)` declarado sin `config()` deja `sn.items()` solo programatico con WARN; el handshake de API level solo protege contra jar instalado VIEJO (`installed < required`), un jar mas nuevo que el consumidor siempre pasa (garantizado por la politica additive-only de `SnApi.LEVEL`); un doble `SnLib.init` no re-monta modulos (devuelve el contexto existente con WARN).

---

## 02. Compat multi-version

Modulo de compatibilidad multi-version de SnLib (paquete `com.sn.lib.compat`). Consta de dos clases utilitarias estaticas y finales: `SnVersion`, que parsea la version del server una unica vez en la inicializacion de la clase y expone chequeos `supports(...)` mas deteccion de Folia, y `SnCompat`, que hace probing reflectivo de API agregada despues del piso de runtime 1.20.4 para que los servers viejos degraden con un unico WARN en vez de tirar excepciones. La filosofia del modulo es "tolerancia forward": una version desconocida o 1.22+ nunca hace hard-fail, se asume soporte completo del target (1.21.8) con un solo WARN. Ambas clases usan estado estatico server-wide, permitido por el contrato de SnLib porque describen al server y no a un plugin consumidor.

### SnVersion
`src/main/java/com/sn/lib/compat/SnVersion.java`

Deteccion de la version del server, parseada una sola vez en el bloque `static` de la clase. Parsea `Bukkit.getBukkitVersion()` (ej: `1.21.1-R0.1-SNAPSHOT`) con el regex `(\d+)\.(\d+)(?:\.(\d+))?`; nunca usa `getVersion()`, cuyo texto libre varia por fork. Clase `final` con constructor privado (no instanciable).

Constantes publicas:
- `public static final int MAJOR` - major parseado, o `1` cuando el string no se pudo parsear.
- `public static final int MINOR` - minor parseado, o el minor target (`21`) cuando el string no se pudo parsear.
- `public static final int PATCH` - patch parseado; `0` cuando esta ausente en el string, patch target (`8`) cuando el string no se pudo parsear.

Metodos publicos:
- `public static boolean supports(int minor)` - true cuando el server corre 1.`minor` o mas nuevo. Siempre true en versiones desconocidas (flag interno `ASSUME_TARGET`).
- `public static boolean supports(int minor, int patch)` - true cuando el server corre 1.`minor`.`patch` o mas nuevo: `ASSUME_TARGET || MINOR > minor || (MINOR == minor && PATCH >= patch)`. Siempre true en versiones desconocidas.
- `public static boolean isFolia()` - true cuando el server es Folia. Detectado una sola vez y cacheado en el campo estatico `FOLIA`.

Logica interna:
- `KNOWN_MAX_MINOR = 21` (privado): la linea minor mas alta reconocida por este build, con target 1.21.8.
- Bloque `static`: aplica el regex sobre `Bukkit.getBukkitVersion()`. Si matchea, toma major/minor/patch (patch `0` si el grupo es null). Se activa el modo "assume target" (`ASSUME_TARGET = true`) en dos casos: (a) el string no matchea el regex, o (b) `major != 1 || minor > KNOWN_MAX_MINOR`, es decir cualquier 1.22+ o un major distinto de 1. En ese caso loguea exactamente un WARN: `[SnLib] '<raw>': version no reconocida, asumiendo compat target`. Con `ASSUME_TARGET` activo, ambos `supports(...)` devuelven siempre true: tolerancia forward en vez de hard-fail.
- `private static boolean detectFolia()` - intenta `Class.forName("io.papermc.paper.threadedregions.RegionizedServer")`; true si la clase existe, false ante `ClassNotFoundException`. Se ejecuta una unica vez al inicializar la clase.

Notas y gotchas:
- Cuando el parseo falla, los valores expuestos NO son la version real sino el target: `MAJOR=1`, `MINOR=21`, `PATCH=8`. Cualquier codigo que lea las constantes directamente en vez de usar `supports(...)` debe tener esto en cuenta.
- La deteccion corre en la inicializacion de la clase (primer uso), por lo que el WARN de version desconocida aparece una sola vez por vida de la JVM, no por plugin.
- El Javadoc justifica los estaticos server-wide: la version del server no es data per-consumer, asi que no viola el contrato de ownership por plugin de SnLib.

### SnCompat
`src/main/java/com/sn/lib/compat/SnCompat.java`

Probing de features para API agregada despues del piso de runtime 1.20.4. Todo uso de API Paper/Adventure mas nueva que 1.20.4 debe pasar por `probe` o `since`, de modo que un server mas viejo degrade con un unico WARN en vez de lanzar. Clase `final` con constructor privado.

Puntos version-sensibles reales documentados en el Javadoc de la clase:
- `ItemMeta#setMaxStackSize` (1.20.5+).
- `ItemMeta#setEnchantmentGlintOverride` (1.20.5+).
- `ItemFlag.HIDE_ADDITIONAL_TOOLTIP` (alias del legacy `HIDE_POTION_EFFECTS`).

Metodos publicos:
- `public static @Nullable Method probe(Class<?> owner, String name, Class<?>... params)` - lookup reflectivo de un metodo publico via `owner.getMethod(name, params)`, hecho una sola vez, cacheando tanto el hit como el miss. La key de cache incluye los tipos de parametros, asi que dos overloads del mismo nombre nunca colisionan. Devuelve el `Method` o null cuando falta en este server (un WARN, miss cacheado). `owner` debe ser una clase de la API del server o del JDK.
- `public static <T> T since(int minor, Supplier<T> modern, Supplier<T> fallback)` - gate por version: devuelve `modern.get()` cuando `SnVersion.supports(minor)`, si no `fallback.get()` con un WARN por call site. El WARN reporta: `API de 1.<minor>+ no disponible en <MAJOR>.<MINOR>.<PATCH>; usando fallback`.

Logica interna:
- `CACHE` (`ConcurrentHashMap<String, Method>`): hits cacheados, key `owner.getName() + "#" + name + "(" + tipos de parametros unidos por "," + ")"` (ej `org.bukkit.inventory.meta.ItemMeta#setMaxStackSize(java.lang.Integer)`); la misma key se usa en `MISSING` y como tag del `warnOnce` del miss.
- `MISSING` (`ConcurrentHashMap.newKeySet()`): keys probeadas y no encontradas en este server. Existe como set separado porque un `ConcurrentHashMap` no puede contener valores null, asi que el centinela de miss vive aca.
- `WARNED` (`ConcurrentHashMap.newKeySet()`): tags ya avisados; `warnOnce(tag, message)` solo loguea `[SnLib] <message>` via `Bukkit.getLogger().warning(...)` si `WARNED.add(tag)` es true (primera vez).
- `private static boolean isForeignPluginClass(Class<?> owner)` - guard de classloader del probe. Chequeo basado en nombre: devuelve false si el loader de `owner` es null (clases del JDK/bootstrap) o es el mismo classloader de `SnCompat` (clases de SnLib). Si no, recorre la jerarquia de clases del loader (`getClass()` y superclases) buscando un nombre que termine en `PluginClassLoader`; true si lo encuentra. Cubre tanto `org.bukkit.plugin.java.PluginClassLoader` como el `PaperPluginClassLoader` de Paper sin referenciar API interna del server.
- `private static String callSiteTag()` - usa `StackWalker` para identificar el primer frame fuera de `SnCompat` y arma el tag `Clase#metodo:linea` (o `"unknown"`). Se usa como clave de dedup del WARN de `since`, logrando "un WARN por call site".
- `private static void warnOnce(String tag, String message)` - loguea el WARN una sola vez por tag.

Guard de classloader del probe (detalle):
- Si `owner` fue cargado por un `PluginClassLoader` que no es el de SnLib, `probe` avisa una vez (tag `"loader:" + owner.getName()`) con el mensaje `probe de <clase> rechazado: clase cargada por un PluginClassLoader ajeno; solo clases de la API del server/JDK` y devuelve null SIN cachear.
- Motivo (del Javadoc): un `Method` retiene su `Class` declarante y por lo tanto su `PluginClassLoader`; cachearlo en el estatico `CACHE` de SnLib leakearia el classloader del plugin consumidor a traves de reloads.

Notas y gotchas:
- La key de cache de `probe` incluye los tipos de parametros (`owner#nombre(tipos)`), asi que dos probes del mismo nombre de metodo con firmas distintas sobre la misma clase cachean por separado y nunca colisionan.
- El rechazo por classloader ajeno NO se cachea a proposito (solo se dedupea el WARN via `WARNED`): cada llamada re-evalua el guard y devuelve null, evitando retener referencias al loader ajeno.
- Threading: todo el estado es `ConcurrentHashMap` / `newKeySet()`, por lo que `probe`, `since` y `warnOnce` son seguros desde cualquier thread. `Bukkit.getLogger()` tambien es thread-safe. No hay requerimiento de main thread en este modulo.
- `since` evalua `fallback.get()` (o `modern.get()`) de forma lazy via `Supplier`, asi el branch no tomado nunca se construye.
- `probe` solo encuentra metodos publicos (`getMethod`), nunca privados ni protected.
- Estaticos server-wide permitidos por el contrato de SnLib: los resultados de probe describen al server, no a un consumidor.

TODOs y limitaciones:
- Ninguno (no hay TODO/FIXME/placeholder en los archivos del alcance). Limitacion de diseño ya notada arriba: `probe` limitado a metodos publicos de clases de API del server/JDK.

---

## 03. Pipeline de texto

El paquete `com.sn.lib.text` implementa el pipeline de renderizado de texto compartido por todos los modulos de SnLib. El orden es FIJO y no configurable: locales -> PAPI -> [small] -> [rgb] -> conversion de color legacy -> [center]. Los pasos de locales y PAPI los resuelve el llamador (los getters de SnYml) antes de invocar estos metodos; las cuatro clases de este modulo cubren el resto. `SnText` es el orquestador (tags de prefijo, conversion legacy a MiniMessage, escape de `<`, render final a `Component`); `SmallCapsUtil` sustituye letras por glifos small caps detras del tag `[small]`; `RgbGradientUtil` interpola el gradiente de 7 anclas detras del tag `[rgb]`; `CenterUtil` centra la linea midiendo pixeles con la tabla de fuente vanilla. Las cuatro clases son transformaciones puras de strings (sin estado, sin Bukkit, sin ownership por plugin): son thread-safe y pueden usarse desde main o async indistintamente.

### SnText
`src/main/java/com/sn/lib/text/SnText.java`

Clase final utilitaria (constructor privado) que orquesta el pipeline. Mantiene una instancia estatica de `MiniMessage.miniMessage()` y el mapa `MINI_TAGS` que traduce cada codigo legacy `&X` a su tag MiniMessage con nombre (`0`->`black`, `1`->`dark_blue`, `2`->`dark_green`, `3`->`dark_aqua`, `4`->`dark_red`, `5`->`dark_purple`, `6`->`gold`, `7`->`gray`, `8`->`dark_gray`, `9`->`blue`, `a`->`green`, `b`->`aqua`, `c`->`red`, `d`->`light_purple`, `e`->`yellow`, `f`->`white`, `k`->`obfuscated`, `l`->`bold`, `m`->`strikethrough`, `n`->`underlined`, `o`->`italic`, `r`->`reset`). Constantes privadas: `CENTER_TAG = "[center]"`, `RGB_TAG = "[rgb]"`, `SMALL_TAG = "[small]"`, `SECTION = (char) 0xA7`. No expone constantes publicas ni enums.

- `public static Component color(String s)` - Render completo: consume los TRES tags de prefijo `[small]`/`[rgb]`/`[center]`, convierte codigos legacy a tags MiniMessage y deserializa con MiniMessage. Input null rinde `Component.empty()`.
- `public static Component mini(String s)` - Render solo MiniMessage: sin tags de prefijo y sin conversion legacy. Null rinde `Component.empty()`.
- `public static String colorLegacy(String s)` - Misma fase legacy que `color(String)` pero la salida queda como string legacy con codigos de section sign (`&#RRGGBB` se convierte en la secuencia hex de bungee `§x§R§R§G§G§B§B`). Para APIs que todavia exigen strings legacy; los tags MiniMessage quedan intactos. Null devuelve null.
- `public static String normalizePapiOutput(String s)` - Convierte la salida de PlaceholderAPI de vuelta a la forma `&` que el pipeline entiende: las secuencias hex de bungee (`§x§R§R...`) pasan a `&#RRGGBB` y los codigos `§X` pasan a `&X`, para que los valores coloreados por PAPI sobrevivan la conversion a MiniMessage. Si el string es null o no contiene section sign, se devuelve tal cual sin alocar.
- `public static String applyLocals(String s, Ph... phs)` - Resuelve placeholders locales a partir de pares `Ph` (key/value); arma un `HashMap` y delega en la sobrecarga con resolver. Devuelve el input intacto si el string es null/vacio o no hay pares.
- `public static String applyLocals(String s, Function<String, String> resolver)` - Escaner de una sola pasada sobre tokens `%key%` y `{key}`. Si el resolver devuelve null el token queda intacto (asi los tokens PAPI no resueltos sobreviven sin tocar); los valores de reemplazo NO se re-escanean (sin expansion recursiva).
- `public static List<Component> colorList(List<String> lines)` - Aplica `color(String)` a cada linea; una lista null o vacia devuelve una `ArrayList` vacia nueva.
- `public static String smallCaps(String s)` - Transformacion small caps programatica (scoreboards, tab, nombres) sin pasar por el tag `[small]`: delega en `SmallCapsUtil.applySmallTag`. Mapeo 1:1 char a char; los codigos de color legacy, las secuencias section-sign y los tags MiniMessage se saltean intactos. Null/vacio pass-through y devuelve la MISMA instancia cuando no hay cambios.
- `public static String applyPrefixTags(String line)` - Consume los tags de prefijo `[center]`, `[rgb]` y `[small]` al inicio de la linea, en cualquier orden y case-insensitive (via `regionMatches(true, ...)`), en un loop hasta que no quede ninguno (una rama por tag). Orden interno FIJO de aplicacion: `[small]` corre primero (delega en `SmallCapsUtil.applySmallTag` sobre el contenido restante), despues `[rgb]` (delega en `RgbGradientUtil.applyRgbTag`); `[center]` se re-emite como una unica marca lider normalizada `[center]` que consume la fase legacy final. Small ANTES de rgb para que el gradiente coloree los glifos finales y la pasada small opere sobre el string corto (no sobre el string inflado 9x por los hex del gradiente); como el mapeo es 1:1 y no toca espacios, el conteo de visibles del gradiente no cambia y las 6 permutaciones de tags rinden identico. Null devuelve null.

#### Logica interna (metodos privados)

- `consumeCenterMark(String line)` - Si la linea empieza con `[center]` (ya normalizado por `applyPrefixTags`), la despoja del tag y delega en `CenterUtil.center`.
- `legacyToMini(String s)` - Conversion legacy a MiniMessage: `&#RRGGBB` se vuelve `<#RRGGBB>`, `&X` se vuelve su tag con nombre segun `MINI_TAGS`, y un `<` literal que no puede iniciar un tag se escapa como `\<`.
- `toSectionCodes(String s)` - Contraparte para `colorLegacy`: `&#RRGGBB` se vuelve `§x` seguido de `§` + cada digito hex en minuscula, y `&X` (codigo valido) se vuelve `§x` minuscula. Lo demas pasa sin cambios.
- `canStartTag(String s, int next)` - Heuristica de inicio de tag MiniMessage: el caracter siguiente a `<` debe ser `/` (tag de cierre), `#` (color hex), `!` (decoracion negada), `_` o una letra ASCII (a-z, A-Z). Si no, el `<` se escapa.
- `isCodeChar(char c)` - Valida caracter de codigo legacy: `0-9`, `a-f`, `k-o`, `r` o `x`.
- `isBungeeHex(String s, int from)` - Valida que desde `from` haya 6 pares `§` + digito hex (formato bungee de 12 chars).
- `isHex(String s, int from)` / `isHexDigit(char c)` - Validan 6 digitos hex consecutivos / un digito hex (mayuscula o minuscula).

#### Notas y gotchas

- El orden del pipeline es FIJO por Javadoc de clase: locales -> PAPI -> `[small]` -> `[rgb]` -> conversion de color legacy -> `[center]`. "`[center]` al final" significa al final de la FASE LEGACY, nunca despues del render a `Component`: `CenterUtil` solo sabe medir strings legacy, por eso el centrado se aplica sobre el string legacy-coloreado (con los `&#RRGGBB` del gradiente ya interpolados) justo antes de `legacyToMini` + deserializacion. `[small]` corre ANTES que ambos: el gradiente colorea los glifos finales y el centrado mide los glifos finales.
- Legacy y MiniMessage se mezclan en el mismo string: los codigos `&X` / `&#RRGGBB` se traducen a tags MiniMessage y TODO el string pasa por MiniMessage al final, asi ambos formatos renderizan juntos. En `colorLegacy` es al reves: los tags MiniMessage quedan sin tocar dentro del string legacy.
- Escape de `<`: solo se escapa (`\<`) cuando el caracter siguiente NO puede iniciar un tag segun `canStartTag`. Un `<` seguido de letra, `/`, `#`, `!` o `_` se deja pasar y MiniMessage intentara parsearlo como tag.
- `applyLocals` corta la busqueda del delimitador de cierre en el primer `%`/`}` que encuentre; `%%` o `{}` (token vacio, `end == i + 1`) no se tratan como token y quedan literales.
- El tag `[rgb]` apunta a titulos y lineas cortas: emite un codigo hex por caracter visible (muy verboso). SnLang cachea las lineas resueltas estaticamente para pagar ese costo una sola vez.

### SmallCapsUtil
`src/main/java/com/sn/lib/text/SmallCapsUtil.java`

Clase final utilitaria (constructor privado). Sustitucion de letras por glifos small caps Unicode detras del tag de prefijo `[small]`. Transformacion pura de strings, sin Bukkit. El diccionario privado `SMALL` (escrito con escapes `\uXXXX`, indice = letra - 'a') mapea las 26 letras; todos los codepoints son BMP (un char de Java cada uno), asi el mapeo es SIEMPRE 1:1 char a char.

Diccionario completo (letra -> glifo -> codepoint):

| Letra | Glifo | Codepoint |
|---|---|---|
| a | ᴀ | U+1D00 |
| b | ʙ | U+0299 |
| c | ᴄ | U+1D04 |
| d | ᴅ | U+1D05 |
| e | ᴇ | U+1D07 |
| f | ꜰ | U+A730 |
| g | ɢ | U+0262 |
| h | ʜ | U+029C |
| i | ɪ | U+026A |
| j | ᴊ | U+1D0A |
| k | ᴋ | U+1D0B |
| l | ʟ | U+029F |
| m | ᴍ | U+1D0D |
| n | ɴ | U+0274 |
| o | ᴏ | U+1D0F |
| p | ᴘ | U+1D18 |
| q | ǫ | U+01EB |
| r | ʀ | U+0280 |
| s | ꜱ | U+A731 |
| t | ᴛ | U+1D1B |
| u | ᴜ | U+1D1C |
| v | ᴠ | U+1D20 |
| w | ᴡ | U+1D21 |
| x | x | U+0078 (a si misma, ASCII) |
| y | ʏ | U+028F |
| z | ᴢ | U+1D22 |

Semantica del mapeo (`mapChar`, privado): las mayusculas A-Z se transforman IGUAL que las minusculas (en small caps la caja no existe); las vocales acentuadas de AMBAS cajas se des-acentuan a los glifos small (a/e/i/o/u con tilde y la u con dieresis); la enye minuscula queda intacta y la enye mayuscula baja a la enye minuscula default U+00F1 (decision de disenio: el glifo small de la enye se ve mal en MC). Digitos, simbolos, espacios, glifos ya small y cualquier otro codepoint pasan intactos.

- `public static String applySmallTag(String line)` - Aplica el mapeo small caps a una linea ya despojada de su prefijo `[small]`. Escaner de UNA pasada sin regex. Reglas de skip verbatim: codigos legacy `&X` / `&#RRGGBB` (helper `codeLength` duplicado de RgbGradientUtil, precedente de utils autocontenidas), codigos section-sign `§X` y la secuencia bungee completa de 14 chars (helper `sectionCodeLength`; los callers programaticos de `SnText.smallCaps` pueden pasar strings ya seccionados), y tags MiniMessage via la heuristica `canStartTag` con busqueda del `>` de cierre: si NO hay `>` en el resto de la linea el `<` se trata como literal y el texto sigue transformandose (asi `<bold>` queda intacto pero "i<3" se transforma). Los argumentos string dentro de tags (`hover:show_text:'...'`) NO se transforman porque viven entre `<` y `>`. Invariante de largo 1:1: el output SIEMPRE mide igual que el input (lo que preserva el conteo de visibles del gradiente `[rgb]`). Devuelve la MISMA instancia cuando ningun char mapeo (cero garbage en lineas que no cambian); null y vacio pass-through.
- `static boolean isSmallGlyph(char c)` - Package-private, consumido por `CenterUtil.baseWidth`: switch O(1) que devuelve true para los 25 codepoints NO-ASCII del diccionario (todos menos la 'x').

#### Notas y gotchas

- El tag `[small]` corre ANTES que `[rgb]` y que `[center]` en el pipeline (ver `SnText.applyPrefixTags`): el gradiente colorea los glifos finales y el centrado mide los glifos finales.
- Al posicionarse despues de locales/PAPI, los VALORES de placeholders tambien salen en small caps: comportamiento deseado.

### RgbGradientUtil
`src/main/java/com/sn/lib/text/RgbGradientUtil.java`

Clase final utilitaria (constructor privado). Gradiente RGB caracter por caracter detras del tag de prefijo `[rgb]`. Transformacion pura de strings, sin Bukkit. Constante privada `ANCHORS` con las 7 anclas de color y `SEGMENTS = ANCHORS.length - 1` (6 segmentos).

Las 7 anclas (indice 0 colorea el primer caracter visible, indice 6 el ultimo):

| Indice | Hex | Color aproximado |
|---|---|---|
| 0 | `0xF300F3` | magenta |
| 1 | `0x5555FF` | azul |
| 2 | `0x55FFFF` | aqua |
| 3 | `0x55FF55` | verde |
| 4 | `0xFCFF21` | amarillo |
| 5 | `0xFF9B00` | naranja |
| 6 | `0xFF5327` | rojo anaranjado |

- `public static String applyRgbTag(String line)` - Aplica el gradiente a una linea ya despojada de su prefijo `[rgb]`. Con `n` caracteres visibles no-espacio, el caracter `i` recibe el color en `t = i / (n - 1)` sobre la cadena de anclas (`t = 0` si `n <= 1`), asi el primer caracter es exactamente `ANCHORS[0]` y el ultimo exactamente `ANCHORS[6]`. Devuelve la linea con un `&#RRGGBB` interpolado por caracter visible. Null o vacio se devuelve tal cual.

#### Logica interna (algoritmo de interpolacion)

- `hexAt(int index, int total)` - Mapea la posicion a la cadena de anclas: `t = index / (total - 1)` (0.0 si `total <= 1`), `segment = t * 6`, `idx = min((int) segment, 5)` selecciona el segmento y `fraction = segment - idx` es el avance dentro de el. Interpola R, G y B por separado entre `ANCHORS[idx]` y `ANCHORS[idx + 1]` y formatea con `String.format("&#%02X%02X%02X", r, g, b)` (hex en mayuscula).
- `lerp(int from, int to, double fraction)` - Interpolacion lineal por canal con `Math.round`.
- `countVisibleNonSpace(String line)` - Cuenta los caracteres visibles no-espacio saltando los codigos legacy (usa `codeLength`).
- `codeLength(String s, int i)` - Longitud del codigo legacy que arranca en `i`: 8 para `&#RRGGBB`, 2 para `&X` valido (`0-9`, `a-f`, `k-o`, `r`, `x`), 0 si no hay codigo.
- `isFormatChar(char c)` - `true` para `k`-`o` (formatos: obfuscated, bold, strikethrough, underlined, italic).
- `isHex(String s, int from)` - Valida 6 digitos hex consecutivos.

#### Notas y gotchas

- Codigos de COLOR preexistentes (`&0`-`&f`, `&#RRGGBB`) se DESCARTAN porque el gradiente los pisa. Los codigos de FORMATO (`&l &o &n &m &k`) se acumulan en un buffer (sin duplicados) y se re-emiten despues de CADA hex interpolado, porque cada codigo de color legacy resetea el formato; `&r` vacia el formato acumulado.
- Los espacios se copian tal cual y NO consumen posicion del gradiente: no reciben hex propio y no distorsionan la distribucion de las anclas.
- La salida usa formato `&#RRGGBB`, que el resto del pipeline convierte despues (a `<#RRGGBB>` en `color` o a secuencia bungee en `colorLegacy`).

### CenterUtil
`src/main/java/com/sn/lib/text/CenterUtil.java`

Clase final utilitaria (constructor privado). Centrado de chat contra la semi-anchura de 154px de la ventana de chat por defecto, usando la tabla de anchos de la fuente vanilla (DefaultFontInfo). Transformacion pura de strings: mide los pixeles visibles de un string legacy-coloreado y le antepone los espacios necesarios. Constantes privadas: `CENTER_PX = 154` (mitad del ancho de la ventana de chat por defecto, en pixeles de fuente) y `SECTION = '§'`.

- `public static String center(String legacyColored)` - Antepone los espacios requeridos para centrar la linea en 154px. El parametro debe ser el string legacy-coloreado FINAL (con el hex del gradiente ya interpolado). Devuelve la linea centrada, o el input sin cambios si es null, vacio, o ya es mas ancho que el objetivo (`toCompensate <= 0`). Calcula `toCompensate = 154 - px / 2` y agrega espacios de a `width(' ', false)` = 4px hasta cubrirlo.

#### Logica interna

- `measure(String s)` - Suma el ancho en pixeles de los caracteres visibles. Salta `&#RRGGBB` (avanza 8 chars) y los codigos `&X` / `§X`; trackea el estado bold: `&l`/`§l` lo enciende y `&r`/`§r` lo apaga (los demas codigos de color NO lo apagan durante la medicion).
- `width(char c, boolean bold)` - Avance en pixeles: ancho de tabla mas 1px de separacion de glifo; bold suma 1px extra excepto para espacios.
- `baseWidth(char c)` - Tabla de anchos vanilla DefaultFontInfo (ver tabla abajo). En la rama `default`, ANTES del check del rango ASCII imprimible, los glifos small caps (detectados via `SmallCapsUtil.isSmallGlyph`, acceso package-private dentro de `com.sn.lib.text`) devuelven base 5 como las mayusculas, salvo U+026A (la i small, angosta) que devuelve base 3 como la 'I' mayuscula; sin esta rama caerian en el fallback de 4 y una linea `[center][small]` saldria corrida a la derecha.
- `isCodeChar(char c)` - Valida caracter de codigo legacy (`0-9`, `a-f`, `k-o`, `r`, `x`).
- `isHex(String s, int from)` - Valida 6 digitos hex consecutivos.

Tabla de medicion en pixeles (ancho base por glifo, ANTES de sumar el gap de 1px y el +1px de bold):

| Ancho base | Caracteres |
|---|---|
| 1 | `i` `!` `:` `;` `'` `.` `,` `\|` |
| 2 | `l` `` ` `` |
| 3 | `I` `t` `[` `]` `"` espacio |
| 4 | `f` `k` `(` `)` `{` `}` `<` `>` |
| 5 | resto del rango ASCII imprimible (`!` a `~`) |
| 6 | `@` |
| 5 | glifos small caps del diccionario de `SmallCapsUtil` (salvo U+026A) |
| 3 | U+026A (la i small caps, mide como la `I` mayuscula) |
| 4 | fallback para glifos desconocidos (fuera de `!`-`~`) |

Los anchos de los glifos small caps (5 y 3) son aproximaciones razonables ajustables en `baseWidth` unicamente: los avances exactos dependen de los bitmaps de la fuente accented del cliente.

#### Notas y gotchas

- Solo puede medir strings LEGACY, nunca `Component`s: por eso el pipeline lo aplica como ultimo paso de la fase legacy, antes de `legacyToMini` y la deserializacion MiniMessage. Un tag MiniMessage sin convertir dentro de la linea se mediria como texto visible.
- `measure` acepta codigos tanto con `&` como con `§`, pero la forma hex solo se salta con `&#RRGGBB` (la secuencia bungee `§x§R...` se salta igual porque cada par `§X` es un codigo valido: `x` esta en `isCodeChar`).
- El compensado usa espacios normales de 4px (3 de tabla + 1 de gap): el centrado tiene granularidad de 4px, es una aproximacion, no pixel-perfect.

### TODOs y limitaciones

No hay marcadores TODO/FIXME en los archivos del alcance. Limitaciones documentadas en el codigo:

- `[rgb]` esta pensado para titulos y lineas cortas: emite un codigo hex por caracter visible, lo que multiplica el largo del string (el `StringBuilder` reserva `line.length() * 9`). SnLang cachea las lineas resueltas estaticamente para pagar el costo una sola vez.
- `CenterUtil` solo mide strings legacy (nunca `Component`s) y asume la ventana de chat por defecto (154px de semi-anchura) con la tabla de fuente vanilla: resource packs con fuentes custom o anchos de chat modificados no se contemplan.
- `applyLocals` no re-escanea los valores de reemplazo: un placeholder cuyo valor contiene otro token `%key%`/`{key}` no se expande recursivamente (decision deliberada para evitar loops).
- Los pasos de locales y PAPI son responsabilidad del llamador (getters de SnYml): `color`/`colorLegacy` no los ejecutan.

---

## 04. YML: lectura, preprocesado y auto-update

Modulo `com.sn.lib.yml`: toda la vida de un archivo YAML de un plugin consumidor pasa por aca. `YamlPreprocessor` repara texto YAML indentado con tabs antes de parsear (texto puro, sin tipos Bukkit). `SnYml` es la vista viva de UN archivo: getters tipados con placeholders y fallback + WARN, y guardado async coalescido con conmutacion a sync durante el shutdown. `YamlUpdater` es el updater always-merge basado en lineas (sin key de version): inserta lo que falta respecto del recurso del jar preservando valores, comentarios y keys extra del usuario, con backups pre-merge y recuperacion de archivos corruptos. `YmlManager` es el modulo `sn.yml()` del contexto: registra cada `SnYml` por path relativo al data folder y decide el modo de cada archivo (managed / managedPruning / seedOnly / data / load) en el primer mount.

### YamlPreprocessor
`src/main/java/com/sn/lib/yml/YamlPreprocessor.java`

Preprocesador de texto puro (clase `final` con constructor privado, solo estaticos) que repara YAML indentado con tabs antes de que SnakeYAML lo rechace. Reescribe cada tab del run de whitespace inicial de una linea en DOS espacios, dejando el resto de la linea byte a byte intacto (tabs dentro de valores quoted o plain se preservan). Normaliza CRLF y CR sueltos a LF. No referencia tipos Bukkit: testeable en unit tests planos. `preprocess` nunca lanza.

- `public record Result(String cleanText, List<Integer> fixedLines)` - resultado de `preprocess`: texto reparado con line endings LF mas la lista (copiada inmutable en el constructor compacto) de numeros de linea 1-based cuyos tabs de indentacion fueron reemplazados; vacia si no hubo reparacion.
- `public static Result preprocess(String rawText)` - repara los tabs de indentacion; `null` se trata como vacio; nunca lanza y nunca devuelve null. Devuelve las lineas corregidas para que el caller emita UN solo warning.
- `public static String read(Path file) throws IOException` - lee el archivo como UTF-8; las secuencias de bytes malformadas decodifican al replacement character en vez de fallar, y un BOM inicial (U+FEFF) se recorta.

#### Logica interna: maquina de block scalars

`preprocess` recorre linea por linea con dos variables de estado: `boolean enBlockScalar` y `int scalarIndent`.

1. Estado normal: cada linea pasa por `fixIndentTabs(line, n, fixedLines)` (reemplaza cada tab del prefijo de whitespace por `"  "` y registra la linea 1-based si toco algo; el resto de la linea se copia verbatim). Despues, si la linea LIMPIA termina en un indicador de block scalar (`startsBlockScalar`), se entra al estado block scalar y `scalarIndent = indentColumns(lineaLimpia)` (el indent del HEADER, no del contenido).
2. Estado block scalar: una linea en blanco (`isBlank`: solo espacios/tabs) o una linea con `indentColumns(line) > scalarIndent` es CONTENIDO del scalar y se copia sin tocar (los tabs ahi son contenido literal). La primera linea no blanca con indent `<= scalarIndent` cierra el estado y se procesa como linea normal (puede a su vez abrir otro block scalar).
3. `indentColumns` mide el ancho del whitespace inicial en columnas: espacio = 1, tab = 2 (mismo ancho que el reemplazo, asi la medicion es coherente antes y despues de reparar).
4. Deteccion del indicador (`startsBlockScalar` + `isBlockScalarIndicator`): se recorta el comentario con `stripComment` (corta en el primer `#` fuera de comillas que este al inicio de linea o precedido de espacio/tab; maneja comillas simples, dobles y escapes `\` dentro de dobles), se hace rstrip, y se toma el ultimo token (desde el ultimo espacio/tab). El token es indicador si empieza con `|` o `>` y sus modificadores son vacios o matchean `[0-9][+-]?|[+-][0-9]?` (chomping `+`/`-` y digito de indentacion en cualquier orden, un digito maximo).

Metodos privados: `fixIndentTabs(String, int, List<Integer>)`, `startsBlockScalar(String)`, `isBlockScalarIndicator(String)`, `stripComment(String)`, `indentColumns(String)`, `isBlank(String)`, `rstrip(String)`.

#### Notas y gotchas
- El digito de indentacion del indicador (`|2`, `>4`) se acepta sintacticamente pero NO se usa para calcular el indent del contenido: la pertenencia al scalar se decide solo relativo al indent del header. Es suficiente para el objetivo (no tocar contenido literal), no es un parser YAML completo.
- Como el tab reemplaza a 2 columnas y `indentColumns` cuenta el tab como 2, la deteccion de contenido de block scalar da igual antes o despues de la reparacion.

### SnYml
`src/main/java/com/sn/lib/yml/SnYml.java`

Un archivo YAML poseido por un contexto consumidor (`Sn ctx`): carga tolerante a tabs, getters tipados placeholder-aware con fallback + WARN, y guardado async coalescido. Las instancias las crea el `YmlManager` del contexto, una por archivo (constructor package-private `SnYml(Sn ctx, File file)` que hace `loadFromDisk()`). El campo `yaml` es `volatile YamlConfiguration`: los getters leen el snapshot vigente desde cualquier thread.

- `public File file()` - archivo de respaldo en disco.
- `public String getString(String key, String def)` - string resuelto; key ausente devuelve `def` en silencio. Delega en la variante con viewer null.
- `public String getString(String key, String def, Player viewer)` - string resuelto; los tokens PAPI se resuelven por-viewer cuando se pasa uno.
- `public int getInt(String key, int def)` - entero; los `Number` se leen directo (`intValue()`), los strings se resuelven (viewer null) y se parsean con `Integer.parseInt(trim())`.
- `public double getDouble(String key, double def)` - idem con `doubleValue()` / `Double.parseDouble`.
- `public long getLong(String key, long def)` - idem con `longValue()` / `Long.parseLong`.
- `public boolean getBoolean(String key, boolean def)` - boolean; `Boolean` directo; de un string solo parsean los literales `true`/`false` (case-insensitive, despues de resolver y trim).
- `public List<String> getStringList(String key, List<String> def)` - lista de strings con cada elemento resuelto; key ausente devuelve `def` en silencio.
- `public List<String> getStringList(String key, List<String> def, Player viewer)` - idem resolviendo por-viewer; un elemento null se convierte a `""` antes de resolver.
- `public ConfigurationSection getSection(String key)` - seccion cruda o null si no existe; los valores leidos desde ella NO pasan por `resolve` (sin placeholders).
- `public boolean isSet(String key)` - true cuando la key existe en el archivo, incluso con valor 0/false/vacio; mantiene distinguible "0 explicito" de "key ausente".
- `public void set(String key, Object value)` - setea el valor en memoria; hay que llamar `save()` para persistir.
- `public void save()` - persiste el estado actual (ver "save() coalescido" abajo).
- `public void flush()` - drena cualquier guardado pendiente; lo invoca el teardown del contexto (ver abajo).
- `public SnYml placeholder(String key, Supplier<String> value)` - registra un placeholder local que se resuelve ANTES de cualquier lookup PAPI; fluido (devuelve `this`).
- `public SnYml placeholders(Map<String, Supplier<String>> values)` - registro en lote de placeholders locales.
- `public void onReload(Runnable hook)` - registra un hook que se dispara despues de cada `reload()`.
- `public void reload()` - relee el archivo de disco (preprocesando tabs) y dispara los hooks de reload; un hook que lanza `Throwable` solo loguea WARN "Hook de reload fallo para <file>: <t>" y no corta a los demas.

#### Tabla de resolucion por tipo de los getters

Todos los getters comparten el mismo esqueleto sobre `Object raw = yaml.get(key)`:

| Caso | getString | getInt / getLong / getDouble | getBoolean | getStringList |
|---|---|---|---|---|
| `raw == null` y `!isSet(key)` (key ausente) | `def` en silencio | `def` en silencio | `def` en silencio | `def` en silencio |
| `raw == null` pero `isSet(key)` (key con valor null explicito) | WARN + `def` | WARN + `def` | WARN + `def` | WARN + `def` |
| Tipo nativo esperado | `String` -> `resolve(s, viewer)` | `Number` -> `intValue()/longValue()/doubleValue()` (sin resolve) | `Boolean` -> directo | `List<?>` -> cada elemento `String.valueOf` + `resolve` (null -> `""`) |
| `String` (para tipos no-string) | n/a | `resolve(s, null).trim()` + parse; `NumberFormatException` -> WARN + `def` | `resolve(s, null).trim()`; solo `"true"`/`"false"` ignore-case; otro -> WARN + `def` | n/a (un string NO es lista: WARN + `def`) |
| Otro tipo | WARN + devuelve `resolve(String.valueOf(raw), viewer)` (NO `def`) | WARN + `def` | WARN + `def` | WARN + `def` |

El WARN es siempre `warnInvalid`: `"Valor invalido en <file> -> '<key>': se recibio '<value>', usando default '<def>'"` (en el caso especial de getString con tipo incorrecto el mensaje dice "usando default" pero el metodo devuelve el valor stringificado y resuelto, no `def`).

Resolucion de placeholders (`resolve(String s, Player viewer)`, privado): primero locals via `SnText.applyLocals(s, this::localValue)`; despues, solo si queda algun `%` en el texto, PAPI: en el primary thread delega en `ctx.papi().apply(viewer, out)` (identidad si el servicio papi todavia es null durante la construccion del contexto); FUERA del main thread los tokens PAPI quedan intactos y el skip se registra por `ctx.debug()` (si no es null): "PAPI omitido fuera del main thread en <file>; tokens intactos: <texto>". Los getters sin viewer resuelven PAPI contra el server (player null). Los getters numericos y boolean resuelven siempre con viewer null.

#### save() async coalescido + conmutacion sync + guard anti stale-write

Estado: `saveLock` protege `pendingSnapshot`, `pendingSeq`, `pendingWrite`, `writeScheduled` y el contador `saveSeq`; `ioLock` protege la escritura fisica y `lastAttemptedSeq`.

1. `save()` toma el snapshot serializado (`yaml.saveToString()`) EN el thread llamador: lo que se persiste es el estado al momento del save, no al momento de la escritura.
2. Si `ctx.isShuttingDown()`: bajo `saveLock` se descarta `pendingSnapshot` (queda cubierto por este snapshot mas nuevo), se toma `seq = ++saveSeq`, y se escribe SINCRONO en el thread llamador via `writeToDisk` - nunca por el scheduler (que ya puede estar rechazando tareas).
3. Runtime normal: bajo `saveLock` se reemplaza `pendingSnapshot`/`pendingSeq` (coalescing: a lo sumo UNA escritura pendiente por archivo; un save mas nuevo pisa el snapshot pendiente) y, si no hay drain agendado (`!writeScheduled`), se agenda `ctx.scheduler().supplyAsync(this::drainPendingWrites)` y se guarda el future en `pendingWrite`. Un `whenComplete` resetea `writeScheduled = false` SOLO si el future termino con error (si el async nunca corrio, un save posterior puede reagendar); en el camino feliz lo resetea el propio drain.
4. `drainPendingWrites()` (corre en el pool async): loop que bajo `saveLock` roba el snapshot pendiente y lo limpia; si no hay nada, apaga `writeScheduled` y termina; si hay, escribe fuera del lock via `writeToDisk` y vuelve a iterar (asi consume saves que llegaron mientras escribia).
5. `writeToDisk(String content, long seq)`: bajo `ioLock`, guard de secuencia - si `seq <= lastAttemptedSeq` retorna sin escribir. El comentario del codigo lo dice literal: un snapshot mas viejo que uno ya intentado JAMAS pisa el estado nuevo (carrera drain async vs save sincrono de teardown). Despues crea los directorios padre si hace falta y escribe UTF-8; una `IOException` solo loguea WARN "No se pudo guardar <file>: <msg>".

`flush()`: copia `pendingWrite` bajo `saveLock` y si existe le hace `get(10, TimeUnit.SECONDS)` (una `InterruptedException` re-asserta el interrupt; cualquier otra excepcion o timeout se ignora porque el sobrante se maneja abajo). Despues, bajo `saveLock`, roba el `pendingSnapshot` sobrante (caso: el scheduler rechazo o cancelo el async y nunca corrio) y lo escribe sincrono con su `pendingSeq`. Lo invoca el teardown del contexto (via `YmlManager.flushAll()`) para que ningun write coalescido se pierda.

#### Notas y gotchas
- `loadFromDisk()` con archivo inexistente deja un `YamlConfiguration` vacio; con `IOException`/`InvalidConfigurationException` NO pisa el estado: WARN "No se pudo leer <file>: <msg>; se mantiene el contenido anterior" y el yaml previo sigue vigente.
- Si el preprocesador reparo lineas, se emite UN warning: "Tabs de indentacion corregidos en <file> (lineas [...])".
- `locales` es `ConcurrentHashMap` y `reloadHooks` es `CopyOnWriteArrayList`: registro seguro desde cualquier thread.

### YamlUpdater
`src/main/java/com/sn/lib/yml/YamlUpdater.java`

Updater YAML always-merge basado en LINEAS (clase `final`, constructor privado, todo estatico). Inserta keys/secciones presentes en el recurso del jar pero ausentes del archivo en disco, preservando valores del usuario, comentarios, contenido de listas y keys extra que el usuario agrego. NO hay key de version (`config-version`): el recurso se compara estructuralmente contra el disco EN CADA arranque. Por defecto nunca borra nada ni reformatea lineas existentes; el borrado de keys ausentes del recurso solo ocurre con prune explicito. I/O sincrona por diseño: `update` corre solo dentro de onEnable y del comando reload, nunca durante gameplay (excepcion documentada a la regla de I/O async de la lib).

Constantes (privadas): `BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")`, `BACKUPS_KEPT = 3`.

- `public static List<String> merge(List<String> resourceLines, List<String> diskLines)` - entrada pura del merge: devuelve una copia de `diskLines` con cada bloque faltante (keys mas sus comentarios adjuntos) insertado en su posicion anclada. Sin I/O, testeable en unit tests planos.
- `public static boolean isParseable(String yamlText)` - true cuando el texto parsea como YAML (`null` se trata como vacio); se usa para detectar archivos de disco corruptos antes de un merge. Pura.
- `public static void update(JavaPlugin plugin, String resourcePath, File diskFile, boolean prune)` - mergea el recurso del jar en el archivo de disco, seedeandolo si falta y respaldandolo si esta corrupto. El gate `update-configs` se lee del `config.yml` del data folder; cuando `diskFile` ES ese config, queda exento del gate y mergea siempre.
- `static void update(JavaPlugin plugin, String resourcePath, File diskFile, boolean prune, @Nullable File gateFile, boolean gateExempt)` - variante gate-aware (package-private) usada por `YmlManager`, que conoce el archivo de config REAL declarado en el spec del consumidor (puede no llamarse `config.yml`).
- `public static boolean updateFromLines(JavaPlugin plugin, List<String> referenceLines, File diskFile, @Nullable File gateFile)` - variante cuya referencia vive en memoria en vez del jar (ej: una traduccion mergeada contra el `messages_en.yml` de DISCO). Misma semantica: seed si falta, backup-N + reseed si corrupto, backup pre-merge keep-last-3 y gate leido de `gateFile` (`null` saltea el gate). Devuelve true cuando el archivo de disco cambio (seedeado, regenerado o mergeado). No soporta prune.
- `static void seedIfMissing(JavaPlugin plugin, String resourcePath, File diskFile)` - (package-private) seedea el archivo desde el recurso del jar SOLO cuando no existe; nunca mergea.
- `static boolean readUpdateConfigsGate(@Nullable File gateFile)` - (package-private) lee el gate maestro parseando el config directo de DISCO antes de cualquier merge; gateFile null, archivo ausente, key ausente o contenido ilegible cuentan todos como `true`.
- `public static List<String> prune(List<String> resourceLines, List<String> lines)` - entrada pura del prune: devuelve una copia de `lines` con cada bloque cuyo key-path no existe en el recurso removido, comentarios incluidos. Opt-in unicamente (via `managedPruning`). Sin I/O.

#### Logica interna: algoritmo Node / insertions

Parser propio (`parse(List<String>)`) que construye un arbol de `Node` (clase interna privada: `key`, `indent`, `keyLine`, `blockStart`, `blockEnd`, `children`, y `findChild(String)` que compara keys normalizadas via `unquoteKey`: una key envuelta en comillas balanceadas `'...'` o `"..."` cuenta igual que la misma sin comillas). Recorre linea a linea con un stack:

- Lineas vacias o de comentario acumulan `pendingCommentStart`: los comentarios que preceden a una key forman parte de su bloque (`blockStart`), y tambien marcan el limite (`boundary`) al cerrar bloques anteriores, asi un comentario "cuelga" de la key que le sigue y no de la anterior.
- Items de lista (`- ` o `-` solo) se saltean: son parte del VALOR del nodo actual; los comentarios encima quedan adjuntos a lo que originalmente encabezaban (tipicamente la key padre).
- Una linea sin dos-puntos "unquoted" (`findUnquotedColon`: colon fuera de comillas seguido de whitespace o fin de linea; un `#` fuera de comillas aborta) se trata como continuacion de scalar multilinea y se saltea.
- Al encontrar una key: se cierran del stack todos los nodos con `indent >= indent` de la nueva (asignandoles `blockEnd = boundary - 1`), se crea el nodo hijo del tope y se pushea.

Planificacion (`planInsertions`): parsea recurso y disco, y `collectInsertions` recorre recursivamente los hijos del recurso; un hijo ausente en disco produce una `Insertion` (clase interna privada: `position`, `sequence`, `lines`) cuyo bloque son las lineas `blockStart..blockEnd` del RECURSO (comentarios lider incluidos) y cuya posicion la da `computeInsertPosition`; un hijo presente recursiona para insertar solo lo faltante dentro.

Anclado (`computeInsertPosition`): 1) el hermano PRECEDENTE mas cercano que exista en recurso y disco -> insertar justo despues de su `blockEnd`; 2) si no hay, el hermano SIGUIENTE compartido mas cercano -> insertar justo antes de su `blockStart` (preservando sus comentarios lider); 3) sin hermanos en disco -> final del padre (raiz `indent < 0` -> `diskLines.size()`; padre con hijos -> `blockEnd + 1` del ultimo hijo; si no -> `blockEnd + 1` del padre).

Aplicacion: las insertions se ordenan por `position` descendente y, a igualdad, por `sequence` (el `blockStart` en el recurso) descendente; `applyInsertions` hace `addAll(pos, lines)` con `pos` acotado a `lines.size()`. Insertar de abajo hacia arriba evita recalcular offsets, y el desempate por sequence hace que varios bloques en la misma posicion queden en el orden del recurso.

Prune (`collectRemovals`): espejo del merge - cada hijo del DISCO ausente del recurso aporta un rango `[blockStart, blockEnd]` (comentarios incluidos); los rangos se ordenan por inicio descendente y se remueven linea a linea de atras hacia adelante, con `end` acotado al tamaño de la lista.

#### Flujo de `update` (gate, backups, corrupcion)

1. `readResource`: si el recurso no esta en el jar -> WARN "[update-configs] Recurso <path> ausente del jar; <file> no se puede actualizar" y retorna.
2. Archivo de disco inexistente -> `seed` (crea directorios padre y escribe las lineas del recurso UTF-8) y retorna.
3. Corrupcion: se lee el disco con `YamlPreprocessor.read` + `preprocess` y se valida con `isParseable`; si NO parsea -> `backupCorrupt` MUEVE el archivo a `<name>.backup-N` (N = primer entero libre desde 1, nunca pisa un backup previo), se reseedea desde el jar y se loguea WARN "[update-configs] <file> no parsea como YAML: respaldado en <backup> y regenerado desde el jar". Nunca crashea al caller.
4. Merge en memoria: `planInsertions` + `applyInsertions` sobre las lineas de disco; con `prune=true` se aplica ademas `prune`. Si el resultado es identico al disco, retorna sin tocar nada (ni backup ni gate ni log).
5. Gate: solo si hubo cambios y `!gateExempt`, se lee `readUpdateConfigsGate(gateFile)` (parse fresco de DISCO, para que un merge previo del propio config ya cuente); en `false` se loguea WARN y NO se escribe: "faltan N keys en <file>" cuando hay insertions, o "prune pendiente en <file>" cuando el cambio era solo de prune.
6. Escritura: `backupBeforeMerge` COPIA el archivo actual a `old-<base>-<yyyyMMdd-HHmmss>.yml` al lado (timestamp exacto con `BACKUP_STAMP`), y `pruneOldBackups` borra los mas viejos dejando solo los ultimos `BACKUPS_KEPT = 3`; el match es EXACTO por regex `old-<base>-\d{8}-\d{6}\.yml` (el comentario del codigo explica el porque: un prefijo suelto mezclaria los backups de otro archivo cuyo nombre extiende la base, ej `config` vs `config-extra`). Un backup viejo que no se puede borrar nunca bloquea el merge. Finalmente se escriben las lineas resultantes UTF-8.
7. Errores: `IOException` -> SEVERE "[update-configs] Fallo actualizando <file>: <msg>"; `RuntimeException` (parseo) -> SEVERE "[update-configs] Error de parseo mergeando <resource> en <file>: <msg>". Nunca propaga.

`updateFromLines` sigue el mismo flujo con tres diferencias: la referencia son lineas en memoria (no hay paso 1), corta temprano con `false` si no hay insertions (sin prune), y devuelve boolean indicando si el disco cambio.

#### Notas y gotchas
- Sin `config-version`: la comparacion es estructural contra el recurso en cada arranque; agregar una key al recurso del jar alcanza para que llegue a todos los servers.
- El gate se chequea DESPUES de calcular el resultado y solo si difiere: un arranque sin cambios no loguea nada aunque el gate este en false.
- La exencion del config propio existe para que la key `update-configs` misma pueda llegar por merge en el primer arranque post-upgrade.
- `findChild` compara keys normalizando el quoting (`unquoteKey`): `foo`, `'foo'` y `"foo"` cuentan como la misma key tanto en el plan de inserciones como en el prune. La normalizacion es SOLO para comparar: al insertar se copia verbatim la forma textual del recurso y las lineas existentes en disco jamas se reformatean.
- Limitacion documentada en el Javadoc: la indentacion se asume con espacios y consistente entre recurso y disco (ambos salen del mismo baseline del plugin).

### YmlManager
`src/main/java/com/sn/lib/yml/YmlManager.java`

Modulo yml de un contexto consumidor, alcanzado via `sn.yml()`. Es dueño de todos los `SnYml` del plugin, keyeados por path relativo al data folder en un `LinkedHashMap` (orden de mount preservado), y monta el config principal managed EN LA CONSTRUCCION. Todo acceso al mapa esta sincronizado sobre `entries`; las iteraciones (`reloadAll`, `flushAll`) trabajan sobre un `snapshot()` copiado bajo lock. I/O sincrona por diseño: mount y `reloadAll()` corren solo en onEnable y en el comando reload.

Constantes (privadas): `GATE_KEY = "update-configs"`, `GATE_COMMENT = "# Master gate of the always-merge updater: false skips every yml merge except this file."`. Enum interno privado `Mode { MANAGED, SEED_ONLY, PLAIN }` y record interno privado `Entry(SnYml yml, String resourcePath, Mode mode, boolean prune, boolean isConfig)`.

- `public YmlManager(Sn ctx, String configName)` - crea el manager y monta el config principal managed; `configName` es el archivo de config declarado en el spec (ej `config.yml`). Lo instancia el contexto.
- `public SnYml config()` - config principal managed; la key maestra `update-configs` se seedea si falta.
- `public SnYml managed(String path)` - archivo managed: seedeado si falta, always-merged desde el recurso del jar, nunca pruneado.
- `public SnYml managedPruning(String path)` - managed con prune opt-in: las keys removidas del recurso del jar se borran del disco. Es el UNICO modo que borra.
- `public SnYml seedOnly(String path)` - seedeado desde el jar si falta; el contenido existente nunca se mergea ni se toca.
- `public SnYml data(String path)` - archivo de datos de runtime totalmente del plugin: nunca seedeado, nunca mergeado.
- `public SnYml load(String path)` - yml arbitrario bajo el data folder, leido as-is: nunca seedeado, nunca mergeado. (Misma implementacion que `data`: ambos montan en `Mode.PLAIN`; la distincion es semantica.)
- `public void reloadAll()` - re-corre el merge de cada archivo managed (el config PRIMERO, por orden de insercion, asi el gate esta fresco para los demas) y el seed de los seedOnly faltantes, y despues recarga cada archivo montado de disco disparando sus hooks de reload.
- `public void flushAll()` - drena la escritura coalescida pendiente de cada archivo montado; lo usa el teardown del contexto.

#### Logica interna
- `mountConfig()` (privado): llama `YamlUpdater.update(plugin, configPath, disk, false, null, true)` - gate null y `gateExempt=true`: el config propio SIEMPRE mergea. Despues `ensureGateKey(disk)` y registra la entry con `isConfig=true`.
- `mount(String rawPath, Mode mode, boolean prune)` (privado): normaliza el path y, bajo lock, si ya existe una entry para ese path devuelve su `SnYml` SIN re-ejecutar nada: el modo lo decide el PRIMER mount de cada path. Un mount posterior con otro modo (o distinto prune) devuelve la instancia existente y loguea un WARN unico por path (`yml '<path>' ya montado en modo <MODO>; se ignora el modo <MODO>`, con nombres `MANAGED`/`MANAGED_PRUNING`/`SEED_ONLY`/`PLAIN` via el helper `describe`, dedup en el set `modeConflictWarned` accedido bajo el lock de `entries`). Para MANAGED corre `YamlUpdater.update(..., prune, gateFile(), false)` (sujeto al gate), para SEED_ONLY `seedIfMissing`, para PLAIN nada.
- `ensureGateKey(File disk)` (privado): garantiza que `update-configs` exista en el config de DISCO. Si el archivo no existe, lo crea con solo el comentario y `update-configs: true`. Si existe, lo parsea (preprocesado); si la key ya esta, no toca nada; si falta, APPENDEA al final una linea en blanco (solo si la ultima linea no esta vacia), el `GATE_COMMENT` y `update-configs: true`. Errores de I/O o parseo -> WARN "No se pudo seedear la key update-configs en <file>: <msg>".
- `gateFile()` / `fileFor(String)` (privados): resuelven archivos contra `ctx.plugin().getDataFolder()`; el gate es el config declarado en el spec, no un `config.yml` hardcodeado.
- `normalize(String)` (privado estatico): backslashes a `/` y recorta slashes iniciales, para que `"gui\\menu.yml"` y `"/gui/menu.yml"` keyeen igual.
- `snapshot()` (privado): copia de las entries bajo lock para iterar sin retener el monitor.

#### Notas y gotchas
- El modo de un archivo queda clavado en el primer mount: pedir `managed("x.yml")` y despues `data("x.yml")` devuelve el mismo `SnYml` managed, con un WARN unico por path que hace visible el conflicto (el comportamiento no cambia: el primer mount manda).
- En `reloadAll` el merge del config corre antes que el de los demas archivos porque `entries` es `LinkedHashMap` y el config se inserta en el constructor: el valor fresco de `update-configs` (incluso recien mergeado) gobierna el resto del ciclo.
- `ensureGateKey` escribe el archivo directo (append de lineas), no via `SnYml.save()`: ocurre antes de crear el `SnYml` del config.

### TODOs y limitaciones

No hay marcadores TODO/FIXME/XXX en los archivos del alcance. Limitaciones documentadas en el codigo/Javadoc:

- `YamlUpdater`: asume indentacion con espacios y consistente entre recurso y disco (ambos provienen del mismo baseline del plugin); no maneja tabs en el algoritmo de merge (eso lo cubre el preprocesador en la LECTURA, no en el merge de lineas).
- `YamlUpdater.parse`: los items de lista se tratan como valor del nodo actual, por lo que los comentarios encima de un item de lista quedan adjuntos a la key padre; las keys se comparan normalizando el quoting balanceado (quoted vs unquoted cuentan igual).
- `YamlPreprocessor`: el digito de indentacion de un block scalar (`|2`) se acepta pero no se usa para calcular el indent del contenido; la deteccion es relativa al indent de la linea header.
- `YamlUpdater.update` / `YmlManager.mount` / `reloadAll`: I/O sincrona por diseño, valida solo en onEnable y en el comando reload (excepcion documentada a la regla de I/O async).
- `YmlManager.mount`: el modo queda fijado por el primer mount del path; mounts posteriores con otro modo no re-ejecutan merges (advierten con un WARN unico por path).

---

## 05. Lang y Debug

Este modulo agrupa dos servicios por contexto de consumidor: `SnLang` (mensajeria localizable, alcanzada via `sn.lang()`) y `SnDebug` (logging de debug toggleable en runtime, alcanzado via `sn.debug()`). SnLang administra los archivos `lang/messages_<code>.yml` del plugin consumidor: seedea el ingles desde el jar, mergea las keys compartidas `snlib.*` que viajan dentro de SnLib.jar, mergea traducciones contra el ingles de disco, resuelve fallback por key con un WARN unico y cachea pre-renderizados los mensajes estaticos. SnDebug ofrece niveles de verbosidad, categorias con filtro, `Supplier` lazy para no construir strings en vano y persistencia de cada toggle en el config principal. Todo el I/O de carga es sincronico por diseño: corre solo en onEnable y en el comando de reload, nunca durante gameplay.

### SnLang

`src/main/java/com/sn/lib/lang/SnLang.java`

Modulo de idioma de un contexto consumidor (clase `public final`). Se instancia desde el contexto cuando el spec declara `lang()`; los consumidores lo alcanzan via `sn.lang()`. Los archivos viven bajo `lang/` en la data folder del consumidor. En cada carga: seedea `lang/messages_en.yml` desde el jar del consumidor, mergea encima el recurso `snlib-messages.yml` de SnLib.jar, y si el config pide otro idioma carga y mergea la traduccion. Los archivos de lang NO llevan marcador de version: el merge es estructural y always-on.

Metodos publicos:

- `public SnLang(Sn ctx, @Nullable SnYml config)` - Constructor: guarda el contexto y el config principal montado (que aporta las keys `lang` y `update-configs`, o null si el modulo config no fue declarado) y ejecuta `load()` inmediatamente (seed + merges + caches).
- `public String language()` - Codigo de idioma activo; devuelve `en` cuando el idioma configurado cayo al fallback.
- `public void send(Player target, String key, Ph... phs)` - Envia el mensaje a un jugador; los valores de una sola linea reciben el prefix prepended. Delega en la sobrecarga de `CommandSender`.
- `public void send(CommandSender target, String key, Ph... phs)` - Envia el mensaje a cualquier sender; PAPI se resuelve por viewer cuando el sender es un `Player`. Null-safe: target o key null es no-op.
- `public void broadcast(String key, Ph... phs)` - Broadcast a todo el server via `Bukkit.getServer()` como Audience; PAPI resuelve contra el server (viewer null).
- `public Component get(String key, Ph... phs)` - Primera linea renderizada del mensaje, SIN prefix. Sin placeholders usa el cache pre-renderizado si existe. Key inexistente rinde `<missing:key>`.
- `public String getLegacy(String key, Ph... phs)` - Primera linea como string legacy con section codes (via `SnText.colorLegacy`), para APIs que todavia exigen texto legacy; misma resolucion y fallback que `get`. Key inexistente devuelve el string `<missing:key>` (y dispara el WARN unico).
- `public List<Component> getList(String key, Ph... phs)` - Todas las lineas del mensaje renderizadas en orden; keys inexistentes producen una lista con la linea marker. Sin placeholders devuelve copia del cache pre-renderizado.
- `public void actionbar(Player target, String key, Ph... phs)` - Muestra la primera linea en la action bar del jugador; linea vacia o key vacia es no-op, key inexistente manda el marker a la action bar.
- `public void title(Player target, String key, Ph... phs)` - Muestra el mensaje como title. La primera linea se parsea como `title;subtitle;fadeIn;stay;fadeOut` (tiempos en ticks, defaults 10;70;20, convertidos a millis multiplicando por 50); las partes omitidas o no numericas caen a su default.
- `public void reload()` - Re-ejecuta el seed, los merges y la relectura de ambos archivos de idioma desde disco, reconstruyendo todos los caches. Se invoca solo desde onEnable y el comando reload.

Constantes: no expone constantes publicas ni enums. Internamente usa las constantes privadas `LANG_DIR = "lang"`, `FALLBACK_CODE = "en"`, `CONSUMER_RESOURCE = "lang/messages_en.yml"` y `SNLIB_RESOURCE = "snlib-messages.yml"`.

#### Logica interna

Metodos privados, en el orden del flujo de carga:

- `private void load()` - Orquesta la carga: limpia `warnedKeys`, seedea el ingles, mergea las keys snlib, parsea el fallback, decide el codigo deseado (si es `en`, active = fallback; si no, carga la traduccion), cachea el prefix y reconstruye los caches.
- `private void seedEnglish(File dir, File enFile)` - Seed de `lang/messages_en.yml` desde el jar del consumidor via `YamlUpdater.update(plugin, CONSUMER_RESOURCE, enFile, false)` (always-merge, gated por `update-configs`). Si el jar del consumidor NO incluye el recurso y el archivo no existe, crea un archivo minimo de dos lineas de comentario y emite un WARN ("El jar de X no incluye lang/messages_en.yml; se creo un archivo minimo").
- `private void mergeSnlibKeys(File enFile)` - Mergea el recurso `snlib-messages.yml` de SnLib.jar dentro del `messages_en.yml` de disco via `YamlUpdater.merge(resource, disk)`. Siempre activo y EXENTO del gate `update-configs`: las keys `snlib.*` son el contrato de mensajes propio de la libreria. Si el disco no parsea como YAML (check con `YamlUpdater.isParseable` sobre el texto preprocesado) omite el merge con WARN; si el merge no cambia nada, no reescribe el archivo. Recurso ausente de SnLib.jar produce WARN y no mergea.
- `private void loadTranslation(File dir, File enFile, String code)` - Carga una traduccion no inglesa: si `messages_<code>.yml` no existe, WARN y fallback a ingles (activeCode vuelve a `en`); si existe, mergea contra el ingles de disco, parsea, y si quedo vacio o corrupto tambien cae a ingles con WARN.
- `private void mergeTranslation(File enFile, File langFile)` - Mergea keys faltantes usando el `messages_en.yml` de DISCO como referencia via `YamlUpdater.updateFromLines(plugin, reference, langFile, config.file())` (gated por `update-configs`). Si hubo cambios loguea INFO "[update-configs] Keys nuevas de messages_en.yml agregadas a lang/<file>; traducirlas cuando convenga".
- `private String desiredCode()` - Lee la key `lang` del config principal; null, blank o config ausente devuelven `en`; el valor se trimea y pasa a lowercase con `Locale.ROOT`.
- `private YamlConfiguration parseFile(File file)` - Parse tab-tolerante via `YamlPreprocessor`: los tabs de indentacion corregidos se reportan con WARN indicando las lineas; contenido ilegible produce configuracion vacia mas WARN.
- `private void cachePrefix()` - Cachea el valor top-level `prefix` como string crudo: primero del idioma activo, si no del fallback (solo si son objetos distintos); ausente queda `""`.
- `private void buildCaches()` - Limpia y reconstruye `templates` (lineas crudas por key, fallback ya resuelto) y `rendered` (Components pre-renderizados para keys cuyas lineas no tienen tokens). Recorre la union de leaf keys de fallback y active (LinkedHashSet, fallback primero).
- `private static Set<String> leafKeys(YamlConfiguration cfg)` - Keys hoja (no secciones) del yml, en orden de aparicion.
- `private @Nullable List<String> linesFor(String key)` - Idioma activo primero; una key presente solo en ingles cae al fallback con UN solo WARN por key ("Key 'X' falta en messages_<code>.yml; usando el valor de messages_en.yml"), deduplicado via `warnedKeys` con la marca `fallback:<key>`.
- `private static @Nullable List<String> readLines(YamlConfiguration cfg, String key)` - Lista YAML tal cual, string como lista de un elemento, otro tipo devuelve null.
- `private static boolean isStatic(List<String> lines)` - Estatico significa renderizable una sola vez: ninguna linea contiene `%` (tokens PAPI) ni `{` (placeholders locales).
- `private void deliver(Audience audience, @Nullable Player viewer, String key, Ph... phs)` - Entrega comun de send/broadcast: key inexistente manda el marker; mensaje de una linea recibe prefix (y usa el cache pre-renderizado solo si no hay prefix ni placeholders); mensajes multilinea se envian linea por linea SIN prefix.
- `private String withPrefix(String line)` - Inserta el prefix configurado DESPUES de cualquier tag inicial `[center]`/`[rgb]`/`[small]` (matching case-insensitive, en loop, soporta tags encadenados; `[small]` se saltea con sus 7 chars): un mensaje con prefix mantiene sus tags en la posicion 0 para que sigan renderizando. El prefix insertado despues del tag queda DENTRO del alcance de `[small]` y sale en small caps, consistente con `[rgb]` (que le aplica gradiente al prefix).
- `private Component renderLine(String line, @Nullable Player viewer, Ph... phs)` - Pipeline fijo: `SnText.color(resolveLine(...))`.
- `private String resolveLine(String line, @Nullable Player viewer, Ph... phs)` - Locals via `SnText.applyLocals`, luego PAPI por viewer via `ctx.papi().apply`, luego `SnText.normalizePapiOutput`.
- `private Component missing(String key)` - Devuelve `Component.text("<missing:" + key + ">")` y emite UN solo WARN por key ("Key de mensaje 'X' no existe en lang/messages_en.yml"), deduplicado con la marca `missing:<key>` en `warnedKeys`.
- `private static long ticksPart(String[] parts, int index, long def)` - Parte numerica del formato de title; ausente, blank o no numerica devuelve el default.
- `private @Nullable List<String> snlibResourceLines()` - Lee `/snlib-messages.yml` del classpath de SnLib como lineas UTF-8; recurso ausente o IOException devuelven null.

#### Notas y gotchas

- Semantica de fallback en dos niveles: key ausente del idioma activo cae a ingles con un WARN por key; key ausente TAMBIEN de ingles renderiza como `<missing:key>` con otro WARN por key. Ambos WARNs se emiten una sola vez por key por carga (el set `warnedKeys` se limpia en cada `load()`/`reload()`).
- El prefix se aplica SOLO en `send`/`broadcast` y SOLO a mensajes de una linea; `get`, `getLegacy`, `getList`, `actionbar` y `title` nunca lo agregan, y los mensajes lista se envian linea por linea tal cual.
- El cache `rendered` paga una sola vez la interpolacion `[rgb]` en la carga. En `deliver`, el cache solo se usa cuando no hay prefix configurado y no llegaron placeholders; con prefix el render es siempre por llamada.
- El merge de `snlib.*` es a nivel de LINEAS de texto (`YamlUpdater.merge`) sobre el archivo de disco, preservando valores existentes: los admins pueden restilizar cualquier linea y sus cambios sobreviven updates.
- Las traducciones se mergean contra el `messages_en.yml` de DISCO (no el del jar), por eso tambien reciben las keys `snlib.*` y cualquier key que el consumidor agregue despues.
- Un valor string vacio en la key produce no-op en `send`/`actionbar`/`title` y `Component.empty()` en `get`; distinto de key inexistente, que produce el marker.
- Threading: los campos mutables son `volatile` o colecciones concurrentes (lectura segura desde cualquier hilo), pero la carga y el merge son I/O sincronico deliberado, confinado a onEnable y al comando reload.
- `title` parsea con `split(";", -1)`, asi que `;` literales dentro del titulo no son escapables; los tiempos son ticks convertidos a `Duration.ofMillis(ticks * 50)`.

### snlib-messages.yml

`src/main/resources/snlib-messages.yml`

Recurso empaquetado dentro de SnLib.jar con las keys de mensaje compartidas `snlib.*`. El updater always-merge inserta en cada arranque toda key que falte en el `lang/messages_en.yml` de cada consumidor; los valores existentes nunca se sobreescriben. Cada key lleva un comentario explicando cuando se envia. Keys y placeholders (formato `{token}`, resueltos como locals por el pipeline de SnLang):

| Key | Placeholders | Uso |
|---|---|---|
| `snlib.no-permission` | (ninguno) | Enviada cuando el sender no tiene el permiso requerido por un comando o subcomando. |
| `snlib.usage` | `{usage}` | Enviada ante argumentos faltantes o malformados; `{usage}` es la sintaxis correcta. |
| `snlib.invalid-number` | `{value}` | Enviada cuando un argumento esperaba un numero; `{value}` es el input rechazado. |
| `snlib.invalid-value` | `{value}` | Enviada cuando un valor no es una de las opciones aceptadas; `{value}` es el input rechazado. |
| `snlib.out-of-range` | `{min}`, `{max}`, `{value}` | Enviada cuando un argumento numerico cae fuera de su rango permitido. |
| `snlib.player-not-found` | `{value}` | Enviada cuando un argumento esperaba un jugador online. |
| `snlib.unknown-subcommand` | `{value}` | Enviada cuando el subcomando dado no existe. |
| `snlib.reload-done` | (ninguno) | Enviada al sender tras un reload exitoso. |
| `snlib.help.header` | (ninguno) | Linea de encabezado impresa antes de las entradas de help generadas. |
| `snlib.help.entry` | `{usage}`, `{permission}` | Una linea por subcomando visible para el sender. |
| `snlib.help.footer` | `{page}`, `{total}`, `{command}` | Impresa despues de las entradas solo cuando el help abarca varias paginas; `{command}` es el nombre del comando raiz. |

Los valores default usan color codes legacy `&` (ej. `&cYou do not have permission to use this command.`).

### SnDebug

`src/main/java/com/sn/lib/debug/SnDebug.java`

Servicio de debug en runtime de un contexto consumidor (clase `public final`), alcanzado via `sn.debug()`: toggleable sin restart, con categorias string, suppliers lazy y persistencia de cada toggle. El output va al logger del server prefijado por canal: `info` emite con `[<PluginName>][INFO] `, `log` con `[<PluginName>][DEBUG] ` y `trace` con `[<PluginName>][TRACE] `.

Enum publico:

- `public enum Level { OFF, INFO, DEBUG, TRACE }` - Umbral de verbosidad, escalera creciente `OFF < INFO < DEBUG < TRACE`. Cada canal emite desde su escalon: `info` desde `INFO`, `log` desde `DEBUG` y `trace` solo en `TRACE`; `OFF` silencia todos los canales.

Metodos publicos:

- `public SnDebug(JavaPlugin plugin, @Nullable SnYml storage)` - Constructor: arma los tres prefijos (`[Plugin][INFO] `/`[Plugin][DEBUG] `/`[Plugin][TRACE] `) y, si hay yml de respaldo (el config principal montado), restaura `debug.enabled` (default false), `debug.level` (default `DEBUG`; valor invalido loguea WARN "Valor invalido en debug.level: 'X', usando DEBUG" y usa `DEBUG`) y `debug.categories` (normalizadas a lowercase trim). Con storage null los toggles viven solo en memoria.
- `public void info(String message)` - Loguea en el canal INFO cuando el master toggle esta encendido y el nivel es al menos `INFO`.
- `public void info(Supplier<String> message)` - Variante lazy del canal INFO (el supplier no se evalua si el canal no emite).
- `public void log(String message)` - Loguea el mensaje cuando el output de debug esta habilitado.
- `public void log(Supplier<String> message)` - Construye y loguea el mensaje lazy, SOLO si el output esta habilitado (el supplier no se evalua si esta apagado).
- `public void log(String category, Supplier<String> message)` - Construye y loguea lazy bajo una categoria, honrando el filtro de categorias; el output se prefija ademas con `[<categoria normalizada>] `.
- `public void trace(Supplier<String> message)` - Variante lazy del canal TRACE: emite solo con master toggle encendido y nivel `TRACE`.
- `public void trace(String category, Supplier<String> message)` - Canal TRACE bajo categoria, honrando el MISMO filtro de categorias que `log(category, ...)` (filtro vacio deja pasar todo); el output se prefija ademas con `[<categoria normalizada>] `.
- `public boolean enabled()` - True mientras se emite output: master toggle encendido y nivel al menos `DEBUG` (comparacion por `ordinal()`).
- `public boolean tracing()` - True mientras el canal TRACE emite: master toggle encendido y nivel `TRACE` (analogo a `enabled()`).
- `public boolean enabled(String category)` - True cuando la categoria pasa: `enabled()` y filtro vacio o conteniendo la categoria (normalizada).
- `public boolean toggle()` - Invierte el master toggle, lo persiste y devuelve el nuevo estado.
- `public boolean toggle(String category)` - Agrega la categoria al filtro, o la quita si ya estaba, y persiste. Devuelve true cuando la categoria quedo dentro del filtro; un filtro vacio deja pasar toda categoria.
- `public void setLevel(Level level)` - Setea el umbral de verbosidad y lo persiste; null se trata como `OFF`, que silencia todo.
- `public Level level()` - Umbral de verbosidad actual.

#### Logica interna

- `private void print(String prefix, String message)` - Emite via `Bukkit.getLogger().info(prefix + message)`: el logger GLOBAL del server, no el logger del plugin, y siempre a severidad INFO de java.util.logging (el "INFO"/"DEBUG"/"TRACE" es parte del prefijo textual del canal).
- `private boolean infoEnabled()` - Gate interno del canal INFO: master toggle encendido y nivel al menos `INFO`.
- `private void persist()` - No-op sin storage. Con storage escribe `debug.enabled`, `debug.level` (nombre del enum) y `debug.categories` (lista ordenada alfabeticamente) via `SnYml.set` mas `SnYml.save()`. Segun el Javadoc de la clase, el save es coalesced async en runtime y sincronico cuando el contexto dueño esta en shutdown.
- `private Level parseLevel(String raw)` - `Level.valueOf` sobre el valor trim + uppercase `Locale.ROOT`; invalido cae a `DEBUG` con WARN en el logger del plugin.
- `private static String normalize(String category)` - Trim + lowercase `Locale.ROOT`; se aplica en toda entrada y consulta de categorias.

#### Notas y gotchas

- Tres canales con umbrales reales: `info` emite desde `INFO`, `log` desde `DEBUG` y `trace` solo en `TRACE`. En nivel `INFO` solo emite `info`; en `DEBUG` emiten `info` y `log`; en `TRACE` emiten los tres; `OFF` silencia todo.
- El filtro de categorias vacio significa "todo pasa"; agregar la primera categoria con `toggle(String)` ANGOSTA el output a solo las filtradas.
- Las categorias se normalizan (trim + lowercase) tanto al persistir/togglear como al consultar, asi que el matching es case-insensitive.
- Estado thread-safe: `enabled` y `level` son `volatile`, las categorias viven en un set concurrente (`ConcurrentHashMap.newKeySet()`); `log` puede llamarse desde cualquier hilo, y `persist()` delega el costo de I/O al save coalesced de `SnYml`.
- Sin modulo config declarado (storage null) los toggles funcionan pero NO sobreviven un restart.

#### TODOs y limitaciones

Ninguno. No hay marcadores TODO/FIXME/placeholder en `SnLang.java`, `SnDebug.java` ni `snlib-messages.yml`. Limitacion documentada en el propio codigo: el I/O sincronico de SnLang es deliberado (confinado a onEnable/reload).

---

# (Seccion generada automaticamente - estado actual de SnLib v1.0.0)

## 06. Scheduler, SoftDependency y Cron

Este modulo agrupa tres piezas de infraestructura de SnLib: `SnScheduler` (scheduler Folia-aware con una instancia por contexto `Sn`, mas su handle uniforme `TaskHandle`), el sistema de hooks reactivos `SoftDependency` (con su listener compartido `HookListener` y el comparador de versiones `SemverComparator`), y el scheduler de calendario `SnCron` (con su parser puro `CronExpr`). El claim de Folia es honesto y acotado: deteccion mas no-crash (las tareas van por los schedulers global region y async cuando `SnVersion.isFolia()` es true), pero NO es un port region-aware completo; los modulos de GUI e items siguen siendo solo Paper. Tanto los hooks como los jobs cron viven en registries por plugin dueño (`TenantRegistry`), de modo que el disable de un consumidor barre sus recursos sin tocar a los demas.

### SnScheduler
`src/main/java/com/sn/lib/scheduler/SnScheduler.java`

Scheduler de tareas Folia-aware ligado a UN plugin dueño (una instancia por contexto `Sn`). Cada metodo bifurca segun `SnVersion.isFolia()`: en Folia usa `Bukkit.getGlobalRegionScheduler()` (sync) o `Bukkit.getAsyncScheduler()` (async); en Paper usa `Bukkit.getScheduler()`. Los delays/periodos en ticks se fuerzan a minimo 1; en los metodos async de Folia los ticks se convierten a milisegundos (`ticks * 50L`, `TimeUnit.MILLISECONDS`). Devuelve siempre un `TaskHandle` (records internos `FoliaHandle` sobre `ScheduledTask` y `BukkitHandle` sobre `BukkitTask`).

- `public SnScheduler(JavaPlugin plugin)` - construye el scheduler ligado al plugin dueño; todas las tareas se agendan a nombre de ese plugin.
- `public TaskHandle sync(Runnable task)` - corre en el main thread (global region en Folia).
- `public TaskHandle async(Runnable task)` - corre fuera del main thread (`runNow` del async scheduler en Folia).
- `public TaskHandle syncLater(long delayTicks, Runnable task)` - corre en el main thread despues de `delayTicks` (minimo 1).
- `public TaskHandle asyncLater(long delayTicks, Runnable task)` - corre fuera del main thread despues de `delayTicks` (minimo 1; en Folia el delay va en ms).
- `public TaskHandle timer(long delayTicks, long periodTicks, Runnable task)` - repetitiva en el main thread; delay y periodo en ticks (minimo 1 cada uno).
- `public TaskHandle timerAsync(long delayTicks, long periodTicks, Runnable task)` - repetitiva fuera del main thread; en Folia delay y periodo se convierten a ms.
- `public <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier)` - computa un valor fuera del main thread; una falla del supplier (o agendar contra un plugin ya deshabilitado, `IllegalPluginAccessException`) completa el future excepcionalmente en vez de tirar.
- `public <T> void thenSync(CompletableFuture<T> future, Consumer<T> consumer)` - consume el valor del future saltando al main thread; el hop se saltea si el plugin dueño ya esta deshabilitado, la race de disable dentro del scheduler se absorbe atrapando `IllegalPluginAccessException`, y una completacion excepcional loguea UN WARN ("Tarea async termino con error: ...") y nunca llega al consumer. El WARN del hop descartado es "Hop al main descartado: plugin deshabilitado durante el scheduling".
- `public void cancelAll()` - cancela todas las tareas agendadas por el plugin dueño (en Folia cancela en el global region scheduler Y en el async scheduler; en Paper via `Bukkit.getScheduler().cancelTasks(plugin)`).

Clases internas (privadas):
- `private record FoliaHandle(ScheduledTask task) implements TaskHandle` - `cancel()` delega en `task.cancel()`; `isCancelled()` en `task.isCancelled()`.
- `private record BukkitHandle(BukkitTask task) implements TaskHandle` - mismo par de delegaciones sobre `BukkitTask`.

#### Notas y gotchas
- Claim honesto de Folia (Javadoc literal): el soporte es "detection plus no-crash"; cuando `SnVersion.isFolia()` es true las tareas van por el global region scheduler y el async scheduler para que agendar nunca tire. NO es un port region-aware completo: no hay scheduling por region ni por entidad, y los modulos de GUI e items siguen siendo Paper-only.
- La conversion tick a ms en Folia async asume 50 ms por tick fijo.
- `supplyAsync`/`thenSync` son el patron recomendado para el ciclo async-compute + main-thread-apply sin fugas de excepciones ni acceso a Bukkit fuera del main.

### TaskHandle
`src/main/java/com/sn/lib/scheduler/TaskHandle.java`

Handle cancelable sobre una tarea agendada, uniforme entre el scheduler de Bukkit y los de Folia. Interface de dos metodos.

- `void cancel()` - cancela la tarea si sigue pendiente o repitiendo.
- `boolean isCancelled()` - true cuando la tarea fue cancelada.

### SoftDependency
`src/main/java/com/sn/lib/hook/SoftDependency.java`

Hook reactivo de dependencia blanda parametrizado en `T` (el tipo del adapter que produce la factory), keyed por su plugin dueño. Resuelve lazy contra el plugin target (presente + habilitado + gate semver + clase requerida opcional) y se activa/desactiva en vivo via `HookListener` cuando el target se habilita o deshabilita. Cada instancia se registra bajo su owner en un `TenantRegistry<SoftDependency<?>>` estatico (static server-wide justificado: el registry en si; el contenido va keyed por plugin dueño), cuyo callback de sweep es `SoftDependency::forceDisable`: el disable de un consumidor remueve sus hooks y los fuerza-deshabilita sin tocar los de otros consumidores. Estado interno: `instance`, `resolved` y `disabled` son `volatile`; la resolucion (`resolve()`) es `synchronized` e idempotente.

Metodos estaticos:
- `public static <T> SoftDependency<T> of(JavaPlugin owner, String pluginName, Supplier<T> factory)` - crea un hook de `owner` contra el plugin llamado `pluginName` y lo inscribe en el registry por owner. La factory debe ser el UNICO codigo que referencia clases del target, para que un target ausente nunca dispare `NoClassDefFoundError` fuera del boundary aislado de instanciacion.
- `public static void forEachRegistered(Consumer<SoftDependency<?>> action)` - aplica la accion a todos los hooks registrados de todos los owners; es la fuente de iteracion de `HookListener`.
- `public static void targetDisabled(String pluginName)` - "estaciona" (deactivate) todos los hooks de cualquier owner que apunten a `pluginName`; notificacion usada por el sweeper. La comparacion de nombres es case-insensitive.

Metodos de instancia:
- `public SoftDependency<T> minVersion(String version)` - exige que la version del target sea al menos `version` (gate semver via `SemverComparator`, con precedencia de pre-release: un target `-SNAPSHOT` instalado NO satisface la release pelada); invalida la resolucion actual y devuelve `this` (fluido).
- `public SoftDependency<T> requiresClass(String className)` - exige que `className` sea cargable desde el classloader del plugin target (`Class.forName(name, false, loader)`, sin inicializar); tambien invalida y devuelve `this`.
- `public JavaPlugin owner()` - plugin dueño del hook; usado para la inscripcion diferida en el registry por owner.
- `public String pluginName()` - nombre del plugin target al que este hook se liga.
- `public boolean isAvailable()` - true cuando el hook esta activo (resolviendolo primero si hace falta); equivale a `get().isPresent()`.
- `public Optional<T> get()` - el adapter activo, resolviendo primero si hace falta; empty cuando no esta disponible o el hook esta `disabled`.
- `public void invalidate()` - descarta el adapter actual (`instance = null`, `resolved = false`); el proximo `get()` vuelve a resolver.
- `public void forceDisable()` - deshabilita el hook PERMANENTEMENTE (teardown del consumidor): `disabled = true`, `instance = null`, `resolved = true`; nunca vuelve a resolver.

Metodos package-private (llamados por `HookListener`):
- `void refresh()` - re-resuelve inmediatamente (`invalidate()` + `resolve()`); llamado al habilitarse el target.
- `void deactivate()` - estaciona el hook como no disponible SIN re-resolucion lazy (`instance = null`, `resolved = true`); llamado al deshabilitarse el target, cuando el target todavia puede reportar `isEnabled()` durante su propio `PluginDisableEvent`.

#### Logica interna
- `resolve()` (privado, `synchronized`): si no esta `disabled` ni `resolved`, busca el target via `Bukkit.getPluginManager().getPlugin(pluginName)`; solo instancia si el target existe, esta habilitado, pasa `versionOk` y pasa `classOk`. Siempre deja `resolved = true` (resultado negativo cacheado hasta un `invalidate`/`refresh`).
- `versionOk(Plugin)`: sin `minVersion` pasa siempre; si `SemverComparator.compareVersions(instalada, requerida) < 0` loguea WARN en el logger del OWNER: "Hook '<target>' requiere version >= X (instalada: Y); hook deshabilitado". La comparacion aplica la precedencia semver de pre-release: un target `2.0.0-SNAPSHOT` instalado ya NO pasa un `minVersion("2.0.0")` (cambio de comportamiento documentado para el changelog de 1.1.0).
- `classOk(Plugin)`: atrapa `ClassNotFoundException` y `LinkageError`; en falla loguea WARN "Hook '<target>': clase requerida <clase> no encontrada; hook deshabilitado".
- `instantiate()`: boundary aislado de instanciacion; la factory corre SOLO aca y cualquier `Throwable` (incluido `NoClassDefFoundError` de un adapter compilado contra una API ausente) se atrapa con WARN "Hook '<target>' fallo al instanciar: ..." devolviendo null, asi un hook roto nunca se propaga al caller.

#### Notas y gotchas
- Ciclo de vida completo: `of` (crea + registra por owner) -> configuracion fluida `minVersion`/`requiresClass` (cada una invalida) -> `get`/`isAvailable` (resolucion lazy cacheada) -> `refresh`/`deactivate` en vivo via `HookListener` -> `invalidate` manual si el consumidor quiere forzar re-resolucion -> `forceDisable` terminal via sweep del `TenantRegistry` al deshabilitarse el owner.
- `deactivate` existe separado de `invalidate` a proposito: durante el `PluginDisableEvent` del target este todavia puede reportar `isEnabled() == true`, asi que una re-resolucion lazy inmediata re-engancharia un plugin moribundo; `deactivate` deja `resolved = true` con instancia null para evitarlo.
- Los WARNs salen por el logger del plugin OWNER (no el del target ni el de SnLib), asi cada consumidor ve sus propios diagnosticos.

### HookListener
`src/main/java/com/sn/lib/hook/HookListener.java`

Listener compartido que activa/desactiva en vivo cada `SoftDependency` registrado cuando su plugin target se habilita o deshabilita. La fuente de iteracion se inyecta por constructor (unit-testable sin registry); la instancia de produccion inscripta en el ListenerHub se puentea a `SoftDependency::forEachRegistered`.

- `public HookListener(Consumer<Consumer<SoftDependency<?>>> forEachDependency)` - recibe la funcion que aplica una accion a cada SoftDependency registrado de cada owner.
- `public void onPluginEnable(PluginEnableEvent event)` - (`@EventHandler`) llama `refresh()` en cada hook cuyo `pluginName()` iguala (case-insensitive) el nombre del plugin habilitado.
- `public void onPluginDisable(PluginDisableEvent event)` - (`@EventHandler`) llama `deactivate()` en cada hook cuyo target coincide con el plugin deshabilitado.

#### Notas y gotchas
- Nota de wiring literal del Javadoc: este listener se define aca unit-testable y se INSCRIBE en el ListenerHub; la llamada a `registerEvents` sucede UNICAMENTE en el bootstrap de SnLibPlugin (paso 31). Nunca registrarlo en otro lado.

### SemverComparator
`src/main/java/com/sn/lib/hook/SemverComparator.java`

Comparador de versiones puro para los gates de version de plugins, con precedencia semver de pre-release. Sin dependencia de Bukkit. Implementa `Comparator<String>`.

- `public int compare(String left, String right)` - delega en `compareVersions` (permite usarlo como `Comparator`).
- `public static int compareVersions(String left, String right)` - negativo cuando `left` es mas vieja que `right`, cero cuando son equivalentes, positivo cuando es mas nueva. Ignora build metadata (`+...`) en ambos lados.

#### Logica interna
- Build metadata: todo desde el primer `+` se ignora (`1.0.0+build.5` == `1.0.0`, item 10 de semver.org).
- El core se compara segmento a segmento como NUMEROS (cualquier cantidad de digitos por segmento, entonces `1.10 > 1.9`); segmentos finales faltantes cuentan como 0 (`1.2 == 1.2.0`).
- Con cores empatados, una version CON pre-release (todo desde el primer `-`) PRECEDE a la release pelada: `2.0.0-SNAPSHOT < 2.0.0`. Un pre-release vacio tras el `-` (`"1.0.0-"`) cuenta lenientemente como sin calificador.
- Dos pre-releases comparan por identificadores separados por `.` de izquierda a derecha: ambos all-digits -> comparacion numerica sin overflow (por largo del string sin ceros a la izquierda y despues lexicografica); numerico < alfanumerico; ambos alfanumericos -> orden ASCII case-sensitive (`String.compareTo`). Si todos los identificadores compartidos empatan, gana el que tiene MAS identificadores (`1.0.0-alpha < 1.0.0-alpha.1`).
- Escalera completa de semver.org cubierta por test: `1.0.0-alpha < 1.0.0-alpha.1 < 1.0.0-alpha.beta < 1.0.0-beta < 1.0.0-beta.2 < 1.0.0-beta.11 < 1.0.0-rc.1 < 1.0.0`.
- Parsing tolerante: `null` se trata como cadena vacia; de cada segmento del core se toma solo el prefijo numerico (`leadingNumber`), y un segmento sin prefijo numerico cuenta como 0. Nunca tira excepcion.

### SnCron
`src/main/java/com/sn/lib/cron/SnCron.java`

Scheduler de calendario de un contexto consumidor, alcanzado via `sn.cron()`. Cada job empareja un id con un `CronExpr` (subset de cron de 5 campos o los atajos `daily`/`hourly`) y una tarea que corre EN EL MAIN THREAD en cada instante que matchea: el delay al proximo run se calcula y agenda a traves del scheduler Folia-aware del contexto (`ctx.scheduler().syncLater(...)`), y el job se re-agenda a si mismo despues de cada run, asi el drift de wall-clock nunca se acumula. Los jobs viven en un `TenantRegistry<Job>` estatico keyed por plugin dueño (static server-wide justificado; sweep = `SnCron::sweep`): un disable los barre aunque el owner nunca los haya cancelado. Constantes privadas: `DATA_FILE = "cron-data.yml"`, `LAST_RUN_PREFIX = "last-run."`.

- `public SnCron(Sn ctx)` - construye el cron del contexto; toda la vida del modulo cuelga de ese `Sn` (scheduler, logger, yml, flag de shutdown).
- `public void schedule(String id, String expr, Runnable task)` - agenda `task` bajo `id` en cada instante que matchea `expr`, reemplazando cualquier job previo con el mismo id; una expresion invalida hace WARN ("Job cron '<id>' no agendado: <motivo>") y no agenda nada. Equivale a `create(id, expr).schedule(task)`.
- `public Builder create(String id, String expr)` - inicia una definicion de job; nada se agenda hasta `Builder.schedule`.
- `public void cancel(String id)` - cancela el job y olvida su id (lo remueve del registry por owner y lo barre); ids desconocidos son no-op.

Clase interna publica `SnCron.Builder` (builder de definicion de job, devuelto por `create`):
- `public Builder catchUp(boolean catchUp)` - persiste el last-run y dispara UNA vez el run perdido al re-agendar (default false).
- `public void schedule(Runnable task)` - registra y arma el job, reemplazando cualquier job previo con el mismo id.

Clase interna privada `SnCron.Job`: struct del job (`id`, `expr`, `task`, `catchUp` finales; `cancelled` y `handle` volatile). Sin metodos.

#### Logica interna
- `scheduleJob(...)`: parsea la expresion (WARN y return en `IllegalArgumentException`), reemplaza atomico en el mapa `byId` (el job previo se remueve del registry y se barre), inscribe el nuevo en `JOBS` bajo `ctx.plugin()`, corre el catch-up si aplica y arma el primer run.
- `scheduleNext(Job)`: si el job no esta cancelado y el contexto no esta en shutdown, calcula `nextRun(now)` y agenda con `syncLater` un delay en ticks de `max(1, (millis + 49) / 50)` (redondeo hacia arriba a ticks). Un `IllegalPluginAccessException` (owner deshabilitado mientras se armaba) se traga: el job simplemente para.
- `runJob(Job)`: chequea cancelado/shutdown, registra el run (si catchUp), ejecuta la tarea atrapando `Throwable` (WARN "Job cron '<id>' lanzo un error: ...") y se re-agenda. La excepcion de la tarea NO frena el ciclo.
- Catch-up (`catchUpIfMissed`): con `catchUp(true)`, el last-run se persiste en `cron-data.yml` del plugin dueño. Al agendar de nuevo (tipicamente el proximo startup), si el last-run persistido ya tenia un instante debido en el pasado (`!expr.nextRun(last).isAfter(now)`), dispara UNA vez inmediatamente via `sync` (`runMissed`, con su propio WARN "... lanzo un error en el catch-up: ..."). Un job SIN last-run persistido solo registra el instante actual como baseline: una instalacion fresca nunca dispara retroactivamente.
- `recordRun(Job)`: solo con catchUp; escribe `last-run.<id> = System.currentTimeMillis()` y llama `SnYml.save()`, que flipea a escritura sincronica durante el teardown del contexto, asi un run registrado mientras se apaga nunca se pierde.
- `dataFile()`: monta lazy el data file bajo `dataLock`; si el modulo yml esta ausente (`UnsupportedOperationException` de `ctx.yml()`), marca `dataUnavailable` y WARNea UNA sola vez: "catchUp(true) requiere el modulo yml (SnSpec.builder().config(...)): el last-run no persiste". El job igual corre, solo que sin persistencia.
- `sweep(Job)` (estatico): release completo de un job; marca `cancelled = true` y cancela el handle pendiente atrapando cualquier `Throwable` (scheduler ya desaparecido durante shutdown).

#### Notas y gotchas
- Todos los jobs corren en el MAIN THREAD (via `syncLater`/`sync` del scheduler del contexto); una tarea pesada debe delegar ella misma a async.
- El re-agendado post-run recalcula contra el reloj real en cada iteracion, por eso no acumula drift (a diferencia de un timer de periodo fijo).
- `catchUp` dispara a lo sumo UN run perdido, no uno por cada instante saltado.
- Las zonas horarias salen de `ZonedDateTime.now()` (zona default del sistema).

### CronExpr
`src/main/java/com/sn/lib/cron/CronExpr.java`

Expresion de calendario parseada: un subset de cron de 5 campos mas dos atajos. Matematica de tiempo pura sobre `java.time`, sin Bukkit. Estado interno: arrays booleanos por campo (`minutes[60]`, `hours[24]`, `daysOfMonth[32]`, `months[13]`, `daysOfWeek[7]`) mas los flags `anyDayOfMonth`/`anyDayOfWeek` (true cuando el campo fue literalmente `"*"`). Constante privada `MAX_ADJUSTMENTS = 5000` (cota de ajustes por busqueda; solo la alcanzan expresiones que nunca matchean).

- `public static CronExpr parse(String expr)` - parsea una expresion cron de 5 campos o uno de los atajos `daily`/`hourly`; tira `IllegalArgumentException` cuando la expresion esta malformada o un valor cae fuera del rango de su campo (mensajes en español: "Expresion cron vacia", "se esperaban 5 campos...", "fuera de rango", "paso invalido", "no es un numero", etc.).
- `public ZonedDateTime nextRun(ZonedDateTime from)` - proximo instante que matchea ESTRICTAMENTE despues de `from`, en la zona de `from`; tira `IllegalStateException` cuando la expresion nunca matchea (por ejemplo 31 de febrero): "Expresion cron '<expr>' sin proxima ejecucion alcanzable".
- `public String toString()` - texto original de la expresion desde la que se parseo esta instancia.

#### Gramatica soportada
Campos en orden `minuto hora dia-del-mes mes dia-de-semana` (rangos 0-59, 0-23, 1-31, 1-12, 0-7):
- `*` (cualquiera), listas `1,15`, rangos `1-5` y pasos `*/10` (tambien sobre rangos, `10-30/5`), combinables por campo (lista de atomos separados por coma, cada atomo con paso opcional). Un valor suelto con paso (`5/10`) se expande de ese valor al maximo del campo.
- Dia de semana 0-7 donde 0 Y 7 significan domingo (7 colapsa sobre 0). Cuando dia-del-mes Y dia-de-semana estan AMBOS restringidos, un dia que matchea CUALQUIERA de los dos corre (semantica OR del cron estandar); si solo uno esta restringido, manda ese.
- Atajo `daily HH:mm` (hora opcional, default 00:00) y atajo `hourly :mm` (minuto opcional, default :00; tambien acepta `mm` sin los dos puntos). Case-insensitive.
- NO soportado: campo de segundos, nombres (`JAN`, `MON`), macros `@daily`/`@yearly`, `L`/`W`/`#` de Quartz.

#### DST y fin de mes (nextRun)
`nextRun` trunca a minuto, suma 1 minuto (estrictamente despues) e itera por campo sobre `ZonedDateTime`, de mayor a menor (mes -> dia -> hora -> minuto), reseteando los campos menores en cada salto (`plusMonths`/`plusDays` con `atStartOfDay(zone)`, `plusHours(...).withMinute(0)`, `plusMinutes(1)`). Como todo el avance pasa por las reglas de la zona:
- Una hora de wall-clock borrada por un gap de DST se SALTEA al proximo dia que matchee (nunca dispara en un instante inexistente).
- Un dia ausente de un mes (el 31 en un mes de 30 dias, el 29 de febrero fuera de año bisiesto) espera al proximo mes que lo tenga.
- La cota `MAX_ADJUSTMENTS = 5000` corta la busqueda de expresiones imposibles con `IllegalStateException` en vez de loopear infinito.

#### Logica interna
- `fill(boolean[] field, String spec, int min, int max, String name)` (estatico privado): llena un campo desde su spec; valida paso >= 1 y rangos `min <= lo <= hi <= max`.
- `fillDayOfWeek(String spec)` (privado): parsea sobre 0-7 en un array crudo de 8 y colapsa 7 en domingo (0) con `% 7`; el match usa `dayOfWeek.getValue() % 7` (ISO lunes=1..domingo=7 -> domingo=0).
- `dayMatches(ZonedDateTime)` (privado): implementa la semantica OR/AND descripta arriba usando `anyDayOfMonth`/`anyDayOfWeek`.
- `parseClock` / `parseHourlyMinute` / `parseInt` (estaticos privados): parsers de los atajos y de enteros con mensajes de error contextuales.

### TODOs y limitaciones
No hay marcadores TODO/FIXME/placeholder en los archivos de este modulo. Limitaciones documentadas en el propio codigo:
- Folia: el soporte de `SnScheduler` es solo deteccion + no-crash (global region y async schedulers); NO es un port region-aware completo y los modulos de GUI e items siguen siendo Paper-only (Javadoc de `SnScheduler`).
- `SnCron` con `catchUp(true)` requiere el modulo yml del contexto (`SnSpec.builder().config(...)`); sin el, el job corre igual pero WARNea una vez y el last-run no persiste.
- El catch-up dispara a lo sumo UN run perdido por re-agendado, no repone cada instante saltado.
- `CronExpr` es un subset: sin segundos, sin nombres de mes/dia, sin macros `@...` ni extensiones Quartz (`L`/`W`/`#`); expresiones que nunca matchean cortan por `MAX_ADJUSTMENTS = 5000` con `IllegalStateException`.
- `SemverComparator` aplica la precedencia semver de pre-release (un `-SNAPSHOT` compara MENOR que la release pelada; cambio de comportamiento respecto de 1.0.0: el gate `minVersion` ahora rechaza un target `-SNAPSHOT` cuando el hook exige la release) e ignora el build metadata (`+...`); los segmentos no numericos del core siguen contando como 0 (comparador tolerante, no un validador semver estricto).

---

## 07. Utils

El paquete `com.sn.lib.util` agrupa 11 clases utilitarias estaticas (todas `final` con constructor privado, salvo `WeightedRandomPool` que es una clase inmutable instanciable via builder). La mitad son puras (sin dependencia de Bukkit): `SlotParser`, `TimeUtil`, `NumberFormatter`, `WeightedRandomPool` y `MathUtil`; el resto toca API de Bukkit/Paper (`LocationSerializer`, `Experience`, `SoundUtil`, `HeadUtil`, `TagIo`, `InvUtil`). Dos clases (`SoundUtil` y `HeadUtil`) mantienen estado estatico server-wide, justificado explicitamente por el contrato de SnLib: cachean hechos del servidor (resolucion de ids de sonido, perfiles de textura content-addressed) que son identicos para todo consumidor. La filosofia general del modulo es "nunca crashear al consumidor": entradas invalidas producen no-ops, nulls o WARNs delegables/deduplicados en lugar de excepciones (con las excepciones documentadas caso por caso: `NumberFormatter.parseFormatted`, `MathUtil.convertToRoman`, `WeightedRandomPool.pick` sobre pool vacio).

### SlotParser

`src/main/java/com/sn/lib/util/SlotParser.java`

Parsea definiciones de slots de inventario provenientes de YML a indices `int[]`. Acepta un int suelto, un string numerico, un rango (`"0-8"`), una mezcla separada por comas (`"0,2,4-6"`) o una lista (cualquier `Iterable`) que combine todo lo anterior (se procesa recursivamente). Clase pura: no toca Bukkit; los warnings se delegan a un sink que provee el caller.

- `public static int[] parse(Object raw)` - parsea descartando warnings (delega en la sobrecarga con `warn = null`).
- `public static int[] parse(Object raw, Consumer<String> warn)` - parsea `raw` a indices de slot distintos, en orden de primera aparicion (respaldado por `LinkedHashSet`). Devuelve array vacio cuando no se encontro nada valido; `warn` puede ser null.

Logica interna:

- `Number` se convierte con `Math.toIntExact(longValue())`; `Iterable` se recorre recursivamente; cualquier otro objeto se pasa a `toString()`, se trimea y se separa por comas.
- Un token con guion a partir del indice 1 (`indexOf('-', 1)`, para no confundir el signo negativo inicial) se interpreta como rango; `from` y `to` se pueden dar en cualquier orden (se normalizan con min/max).
- Constante privada `MAX_RANGE_SPAN = 10_000`: un rango cuyo span supere ese limite se rechaza entero con WARN ("exceeds 10000 slots; ignored"), como proteccion contra typos tipo `"0-999999"`.
- Slots negativos se ignoran con WARN ("Negative slot N ignored"); tokens no numericos generan WARN ("Invalid slot token '...'" / "Invalid slot range '...'"); `null` y string vacio tambien avisan ("Slot definition is null" / "is empty").

Notas y gotchas:

- Los duplicados se deduplican silenciosamente (es un `Set`), pero el orden de salida es first-seen, no ordenado numericamente.
- Tokens vacios entre comas (`"1,,3"`) se saltan sin WARN.

### TimeUtil

`src/main/java/com/sn/lib/util/TimeUtil.java`

Parseo y humanizacion de duraciones (pura, sin Bukkit). Parsea strings compactos como `"1d 2h 30m 15s"` y renderiza milisegundos de vuelta a texto, con etiquetas inyectables para i18n.

Enum publico `Unit`: `DAY, HOUR, MINUTE, SECOND` (los componentes que renderizan `humanize`/`humanizeShort`).

Interfaz publica `Labels` (proveedor de etiquetas para i18n):

- `String longLabel(Unit unit, boolean plural)` - etiqueta larga, ej. `"day"`/`"days"`.
- `String shortLabel(Unit unit)` - sufijo compacto, ej. `"d"`.

Constante publica: `Labels ENGLISH` - implementacion default en ingles (day/hour/minute/second y d/h/m/s).

- `public static long parseTicks(String text)` - parsea a ticks de servidor (20 por segundo); es `parseMillis(text) / 50`.
- `public static long parseMillis(String text)` - parsea a milisegundos. Lee cada par `<numero><unidad>` tolerando espacios, comas y palabras completas (`"1 day 2 hours"`). Unidades desconocidas y basura se saltan; null o input no parseable devuelve 0.
- `public static String humanize(long millis)` - render largo en ingles, ej. `"1 day 2 hours 30 minutes 15 seconds"`.
- `public static String humanize(long millis, Labels labels)` - render largo con etiquetas inyectables; los componentes en cero se omiten; si todo es cero devuelve `"0 <segundos en plural>"`.
- `public static String humanizeShort(long millis)` - render compacto en ingles, ej. `"1d 2h 30m 15s"`.
- `public static String humanizeShort(long millis, Labels labels)` - render compacto con etiquetas inyectables; cero total devuelve `"0s"` (con la etiqueta corta de segundos).

Formato aceptado por el parser:

- Unidades por primera letra (case-insensitive): `d` = dias, `h` = horas, `m` = minutos, `s` = segundos, `t` = ticks (50 ms); la excepcion es cualquier palabra que empiece con `ms` = milisegundos (chequeada antes que la primera letra, asi `"500ms"` no se lee como minutos).
- Numeros decimales permitidos (`"1.5h"`); el resultado de cada par se redondea con `Math.round(value * unitMillis)`.
- Numero sin unidad = segundos (`"90"` -> 90000 ms).
- El resultado total se clampea a minimo 0.

Notas y gotchas:

- Como la unidad se resuelve por primera letra, palabras completas funcionan (`"minutes"`, `"days"`), pero tambien cualquier palabra que arranque igual (`"month"` se leeria como minutos).
- `humanize`/`humanizeShort` truncan a segundos enteros: los millis sobrantes se descartan (no hay componente de ms en la salida).

### NumberFormatter

`src/main/java/com/sn/lib/util/NumberFormatter.java`

Formateo abreviado de numeros con sufijos `K/M/B/T/Qa/Qi` en potencias de 1000 (pura, sin Bukkit). Array privado de sufijos: `{"", "K", "M", "B", "T", "Qa", "Qi"}` (hasta 10^18).

- `public static String format(double value)` - formatea con el sufijo mas grande que aplique y hasta 2 decimales con ceros finales eliminados (`1500 -> "1.5K"`, redondeo `HALF_UP` via `BigDecimal`). Valores no finitos (`NaN`, infinitos) se devuelven como `String.valueOf(value)`. El signo negativo se preserva.
- `public static double parseFormatted(String text)` - inversa tolerante: sufijos case-insensitive (`"1.5k"` funciona) y acepta tanto coma como punto como separador decimal o de agrupacion. Lanza `NumberFormatException` con null, vacio, sufijo desconocido o numero no parseable.

Logica interna:

- `format` tiene una segunda pasada de escala: si tras redondear a 2 decimales el valor llega a 1000 (ej. `999999 -> 1000.00K`), sube un sufijo mas y re-escala (`-> "1M"`), evitando salidas tipo `"1000K"`.
- El sufijo de `parseFormatted` se toma como el run final de letras del string; el multiplicador es `1000^i` segun la posicion en el array de sufijos.
- Heuristica de separadores en `normalizeSeparators`: si hay coma y punto, el ultimo de los dos es el decimal y el otro es agrupacion; si hay solo comas, una unica coma seguida de exactamente 3 digitos cuenta como agrupacion (`"1,500" -> 1500`) mientras que `"1,5" -> 1.5`; varias comas siempre son agrupacion. Espacios internos se eliminan. Si quedan varios puntos, solo el ultimo sobrevive como decimal.

Notas y gotchas:

- La ambiguedad `"1,500"` se resuelve a favor de agrupacion (1500), no de decimal (1.5): es una decision deliberada documentada en el Javadoc de la clase.

### LocationSerializer

`src/main/java/com/sn/lib/util/LocationSerializer.java`

Serializa `Location` de Bukkit a la forma compacta `world;x;y;z;yaw;pitch` y viceversa.

- `public static String serialize(Location location)` - serializa a `world;x;y;z;yaw;pitch`; location null o sin mundo devuelve null. Los numeros usan `Double.toString`/`Float.toString`, que son locale-independientes, asi el round-trip es estable en cualquier locale de JVM.
- `public static Location deserialize(String raw)` - inversa null-safe. Acepta 6 partes, o 4 partes (`world;x;y;z`) con yaw y pitch en 0. Devuelve null ante cualquier fallo: input null/blank, cantidad de partes distinta de 4 o 6, mundo no cargado (`Bukkit.getWorld` null) o numero mal formado. Nunca lanza.

Notas y gotchas:

- `deserialize` requiere que el mundo este cargado en el momento de la llamada; si el mundo todavia no cargo (ej. deserializacion muy temprana en el arranque) el resultado es null silencioso.
- Cada parte se trimea individualmente, asi que `"world ; 1 ; 2 ; 3"` tambien parsea.

### WeightedRandomPool

`src/main/java/com/sn/lib/util/WeightedRandomPool.java`

Selector aleatorio ponderado inmutable (puro, sin Bukkit), generico en `T`. Respaldado por un `TreeMap` de pesos acumulados: `pick` sortea `r` uniforme en `[0, totalWeight)` y resuelve la entrada en O(log n) via `ceilingEntry(r)`. Los pesos son `double` de punta a punta (nunca se truncan a int), asi los pesos fraccionarios conservan sus probabilidades relativas exactas. Se construye con `builder()`.

- `public static <T> Builder<T> builder()` - crea un builder vacio.
- `public T pick(Random random)` - pick ponderado con la fuente de aleatoriedad dada; lanza `NoSuchElementException` si el pool esta vacio. Si `ceilingEntry` devolviera null (borde de punto flotante en `r == totalWeight` efectivo), cae en `lastEntry()`.
- `public T pick()` - pick ponderado usando `ThreadLocalRandom.current()`.
- `public int size()` - cantidad de entradas.
- `public boolean isEmpty()` - si el pool no tiene entradas.
- `public double totalWeight()` - suma de pesos.
- `public Collection<T> values()` - valores en orden de peso acumulado (coleccion no modificable).

Clase anidada publica `Builder<T>`:

- `public Builder<T> add(T value, double weight)` - agrega una entrada; pesos no positivos o no finitos (`<= 0`, `NaN`, infinito) se ignoran silenciosamente (contrato heredado de RandomCollection). Devuelve `this` para encadenar.
- `public WeightedRandomPool<T> build()` - construye el pool copiando el mapa acumulado (el builder puede seguir usandose despues sin afectar al pool construido).

Notas y gotchas:

- Si dos entradas hacen que el peso acumulado coincida exactamente en el mismo `double`, la segunda pisaria la clave del `TreeMap`; en la practica solo pasa con `weight` que se ignoran (0 o negativos), que ya estan filtrados en `add`.
- Al ser inmutable, la instancia es segura para compartir entre hilos; `pick()` sin argumentos es thread-safe via `ThreadLocalRandom`.

### Experience

`src/main/java/com/sn/lib/util/Experience.java`

Matematica de XP de jugador sobre la formula piecewise exacta de vanilla. XP total para alcanzar un nivel: `level^2 + 6*level` para 0-15, `2.5*level^2 - 40.5*level + 360` para 16-30 y `4.5*level^2 - 162.5*level + 2220` para 31+. Costo de un nivel: `2*level + 7`, `5*level - 38` y `9*level - 158` en los mismos brackets. Umbrales inversos: 315 XP total = nivel 15, 1395 XP total = nivel 30 (constantes privadas `BRACKET_15_TOTAL` y `BRACKET_30_TOTAL`).

- `public static long getExp(Player player)` - XP total que tiene el jugador ahora (niveles completos mas la fraccion de la barra de progreso, redondeada).
- `public static long getExpFromLevel(int level)` - XP total requerida para llegar a `level` desde cero; niveles `<= 0` devuelven 0.
- `public static int getExpToNext(int level)` - XP para pasar de `level` a `level + 1` (formula lineal por bracket; niveles negativos se clampean a 0 en el bracket bajo).
- `public static double getLevelFromExp(long exp)` - nivel fraccionario para una cantidad total de XP (parte entera = nivel, decimal = progreso de barra); usa las inversas cuadraticas por bracket (`sqrt`); `exp <= 0` devuelve 0.
- `public static int getIntLevelFromExp(long exp)` - nivel entero (truncado) para un XP total.
- `public static void changeExp(Player player, int amount)` - suma (o resta, si es negativo) XP de forma segura: recomputa nivel y barra de progreso del jugador a partir del total resultante, clampeado en 0.

Notas y gotchas:

- El Javadoc indica explicitamente usar `changeExp` en lugar de `Player#giveExp(int)`: al recomputar todo desde el total, nunca sobre-nivela y el clamp en 0 evita XP negativa.
- `changeExp` normaliza bordes de punto flotante: si el progreso calculado llega a `>= 1.0` sube un nivel y resetea la barra; progreso negativo se clampea a 0. Requiere hilo principal en la practica (muta estado del `Player`).

### MathUtil

`src/main/java/com/sn/lib/util/MathUtil.java`

Helpers matematicos: redondeo probabilistico "justo" y numeros romanos (pura, sin Bukkit). El redondeo justo resuelve la parte fraccionaria de forma probabilistica para que el valor esperado del resultado sea igual al input: `2.3` da 3 con probabilidad 30% y 2 en el resto.

- `public static int fairIntFromDouble(double value)` - redondeo justo a `int` usando `ThreadLocalRandom`.
- `public static int fairIntFromDouble(double value, Random random)` - redondeo justo a `int` con RNG inyectable: hace `floor` y suma 1 con probabilidad igual a la fraccion.
- `public static long fairLongFromDouble(double value)` - redondeo justo a `long` usando `ThreadLocalRandom`.
- `public static long fairLongFromDouble(double value, Random random)` - redondeo justo a `long` con RNG inyectable.
- `public static String convertToRoman(int number)` - numero romano para `number` en 1-3999 (algoritmo greedy sobre las tablas `ROMAN_VALUES`/`ROMAN_SYMBOLS`, con sustractivos CM/CD/XC/XL/IX/IV); lanza `IllegalArgumentException` fuera del rango.

Notas y gotchas:

- El redondeo justo funciona tambien con negativos por usar `Math.floor` (ej. `-2.3` da -2 con 70% y -3 con 30%, manteniendo el valor esperado -2.3).
- Las sobrecargas con `Random` existen para testear con RNG deterministico.

### SoundUtil

`src/main/java/com/sn/lib/util/SoundUtil.java`

Reproduce sonidos desde specs estilo YML: `"SOUND_ID [volume] [pitch]"` (separados por whitespace; volume y pitch defaultean a 1.0). La resolucion trata `Sound` como conjunto abierto (nunca switch/EnumSet): primero `Sound.valueOf` para ids estilo enum (`ENTITY_PLAYER_LEVELUP`), despues `Registry.SOUNDS` por `NamespacedKey` para ids estilo key (`minecraft:entity.player.levelup`), de modo que ids agregados por servidores mas nuevos siguen funcionando. Un id irresoluble loguea un solo WARN y la llamada se vuelve no-op. Specs null, en blanco y `"none"` (case-insensitive) son no-ops silenciosos.

- `public static void play(Player player, String spec)` - reproduce el spec solo para ese jugador, en la ubicacion del propio jugador; player null es no-op.
- `public static void playAt(Location location, String spec)` - reproduce el spec para todos los jugadores cercanos a la location (via `World.playSound`); location null o sin mundo es no-op.

Logica interna:

- `resolve(String)`: normaliza a mayusculas, quita el prefijo `MINECRAFT:` y reemplaza `.` por `_` para probar `Sound.valueOf`; si falla, intenta `NamespacedKey.fromString(id.toLowerCase())` contra `Registry.SOUNDS`.
- Volume/pitch mal formados no anulan el sonido: se loguea un WARN ("Volumen/pitch invalido en '...'; usando 1.0") y se usa 1.0.
- `warnOnce`: los WARNs se deduplican en un `Set` estatico concurrente (`WARNED`) con tags `"id:..."` / `"num:..."`; cada problema se loguea una sola vez por vida del servidor, con prefijo `[SnLib]` en `Bukkit.getLogger()`.

Notas y gotchas:

- Estado estatico server-wide permitido por el contrato de SnLib: que un id de sonido resuelva o no es un hecho del servidor, no del consumidor.
- Los mensajes de WARN estan en español ("Sonido invalido '...': no se resolvio por enum ni por Registry.SOUNDS; se ignora").

### HeadUtil

`src/main/java/com/sn/lib/util/HeadUtil.java`

Construye stacks de `PLAYER_HEAD` desde valores de textura sin NMS. Inputs aceptados: prefijos `texture-`/`texture:`, `base64-`/`base64:`, `basehead-` (desanidados recursivamente, ej. `basehead-eyJ...`), payloads base64 crudos (`eyJ...`) y URLs http(s) de skin (envueltas en el JSON de textures estandar y codificadas a base64).

- `public static ItemStack fromBase64(String value)` - crea un stack `PLAYER_HEAD` de amount 1 mostrando la textura dada; null o vacio devuelve una cabeza por defecto.
- `public static void applyBase64(SkullMeta meta, String value)` - aplica una textura a un `SkullMeta` con UUID de perfil deterministico derivado de los bytes de la textura; valores no parseables dejan el meta intacto con un WARN unico; meta null es no-op.
- `public static @Nullable String extractTextureValue(String value)` - normaliza un input crudo a su payload base64: quita prefijos (recursivo), codifica URLs http(s) al JSON `{"textures":{"SKIN":{"url":"..."}}}` en base64, acepta `eyJ...` tal cual; devuelve null cuando el input no es una textura.
- `public static void clearCache()` - vacia el cache acotado de perfiles; lo invoca el plugin SnLib en su teardown (onDisable).

Logica interna:

- UUID deterministico: texturas identicas comparten el mismo UUID de perfil via `UUID.nameUUIDFromBytes(texture.getBytes(UTF_8))`, asi el cliente cachea la skin una sola vez entre todas las cabezas que usan esa textura (menos descargas y parpadeo).
- Cache LRU acotado: `PROFILE_CACHE` es un `LinkedHashMap` en access-order con `removeEldestEntry` sobre `CACHE_CAP = 512`; todo acceso se sincroniza sobre el propio mapa (incluido el `get`, porque en access-order un get muta el orden interno).
- Aplicacion multi-tier: primero Paper `PlayerProfile` (`meta.setPlayerProfile`); si tira `Throwable`, fallback reflectivo legacy que instancia `com.mojang.authlib.GameProfile` (nombre `"SnLibHead"`) y lo inyecta en el field privado `profile` del meta, sin dependencia en compile-time. Si ambos tiers fallan, la cabeza queda default y se loguea un solo WARN con ambas causas.
- WARNs deduplicados en `WARNED` (set concurrente), con prefijo `[SnLib]` y valores abreviados a 40 caracteres en el mensaje ("Textura de cabeza invalida '...'; se deja la cabeza por defecto").

Notas y gotchas:

- Estatico server-wide justificado por el contrato de SnLib: el mapeo textura -> perfil es content-addressed e identico para cualquier consumidor.
- En `extractTextureValue`, tras quitar un prefijo se intenta desanidar recursivamente; si el resto no matchea ningun formato conocido, se devuelve el resto tal cual (el prefijo explicito se toma como declaracion de intencion de que eso ES una textura).
- Un string arbitrario sin prefijo, que no empiece con `eyJ` ni sea URL, devuelve null (no se adivina).

### TagIo

`src/main/java/com/sn/lib/util/TagIo.java`

Tags de tipo String sobre items via `PersistentDataContainer`. Toda key es un `NamespacedKey(owner, key)`, asi dos consumidores que usan el mismo nombre de key nunca colisionan: el dato del tag siempre es propiedad del plugin que lo escribio (ownership por `JavaPlugin`). Valores almacenados como `PersistentDataType.STRING`. Items null, aire e items sin meta son no-ops guardeados.

- `public static ItemStack set(ItemStack item, JavaPlugin owner, String key, String value)` - escribe `value` bajo la key namespaced del owner; un `value` null delega en `remove` (borra el tag). Devuelve la misma instancia de item, para encadenar.
- `public static @Nullable String get(ItemStack item, JavaPlugin owner, String key)` - lee el valor del tag, o null cuando esta ausente o el item es null/aire/sin meta.
- `public static boolean has(ItemStack item, JavaPlugin owner, String key)` - si el tag esta presente en el item (chequeado como STRING).
- `public static ItemStack remove(ItemStack item, JavaPlugin owner, String key)` - remueve el tag si esta presente; devuelve la misma instancia para encadenar.

Notas y gotchas:

- La key se normaliza a minusculas (`key.toLowerCase(Locale.ROOT)`) antes de crear el `NamespacedKey`, porque `NamespacedKey` no admite mayusculas; asi `"MyKey"` y `"mykey"` son el mismo tag.
- `set`/`remove` mutan el item pasado (hacen `setItemMeta` sobre la misma instancia); el retorno es solo azucar para chaining.

### InvUtil

`src/main/java/com/sn/lib/util/InvUtil.java`

Helpers de inventario para entregar items a jugadores.

- `public static void giveItems(Player player, ItemStack... items)` - agrega los items al inventario del jugador; lo que no entra se dropea naturalmente (`World.dropItemNaturally`) en la ubicacion del jugador, asi nunca se pierde nada. Stacks null y de aire se saltan; player null, array null o vacio son no-ops.

Notas y gotchas:

- Usa `Inventory.addItem`, que puede mergear con stacks parciales existentes; el `Map` de leftover que devuelve Bukkit es lo que se dropea.
- Debe llamarse en el hilo principal (muta inventario y spawnea entidades de item).

### TODOs y limitaciones

Ninguno. No hay marcadores TODO/FIXME/HACK/placeholder en ningun archivo del paquete `com.sn.lib.util`. Limitaciones de diseño ya documentadas arriba en cada clase: rango maximo de 10000 slots en `SlotParser`, sufijos de `NumberFormatter` topeados en `Qi` (10^18), `convertToRoman` limitado a 1-3999, `deserialize` de `LocationSerializer` dependiente de que el mundo este cargado, y cache de `HeadUtil` acotado a 512 entradas.

---

## 08. Acciones, Requirements y PAPI

Este modulo cubre tres piezas que casi todos los plugins consumidores usan desde YML: el motor de acciones (`com.sn.lib.action.ActionEngine`, alcanzado via `sn.actions()`, una instancia por contexto Sn) que ejecuta listas de lineas `[tag] argumento` con guards de click y de chance; el motor de requirements (`RequirementEngine`, estatico) que parsea expresiones de comparacion con `&&`/`||` una sola vez al load y las evalua contra placeholders en runtime con politica fail-open; y el servicio PAPI (`com.sn.lib.papi.SnPapi` + `ExpansionBuilder` + el holder interno `PapiHolder`) que resuelve tokens de PlaceholderAPI y registra expansiones declarativas sin que un servidor sin PlaceholderAPI cargue jamas una clase de PAPI. Todo el modulo es ownership por plugin: cada contexto Sn tiene su propio `ActionEngine` y su propio `SnPapi`/`PapiHolder`, y el teardown del contexto libera el canal Bungee y desregistra las expansiones.

### ActionEngine
`src/main/java/com/sn/lib/action/ActionEngine.java`

Ejecuta listas de acciones YML de la forma `[tag] argumento`. Es `final`, una instancia por contexto (`sn.actions()`). Mantiene un `ConcurrentHashMap<String, ActionHandler>` de handlers (built-ins registrados en el constructor), un set `warned` para WARN-once por clave, y un `AtomicBoolean bungeeRegistered` para el canal saliente `"BungeeCord"` (constante privada `BUNGEE_CHANNEL`). No expone constantes publicas ni enums.

- `public ActionEngine(Sn ctx)` - crea el motor para el contexto dado con el catalogo built-in ya registrado; toma el `JavaPlugin` de `ctx.plugin()`.
- `public void run(Player player, List<String> actions, Ph... phs)` - corre las lineas para el jugador con placeholders locales y sin datos de pagina ni de click (construye un `ActionContext` con `pageTarget` y `clickType` en null).
- `public void run(Player player, List<String> actions, ActionContext context)` - corre las lineas bajo el contexto dado. `player`/`actions` null o lista vacia retornan sin hacer nada; `context` null lanza NPE (`Objects.requireNonNull`). Threading: si el caller ya esta en el main thread ejecuta inline; desde cualquier otro thread salta via `ctx.scheduler().sync(...)`. Si el scheduling falla con `IllegalPluginAccessException` (plugin deshabilitado) loguea WARN "Acciones descartadas: plugin deshabilitado durante el scheduling" y descarta.
- `public void register(String tag, ActionHandler handler)` - registra una accion custom bajo `tag` (con o sin corchetes, case-insensitive: el tag se normaliza con trim + lowercase + strip de `[...]`), reemplazando cualquier handler previo INCLUIDO un built-in. Tag vacio tras normalizar lanza `IllegalArgumentException("Tag de accion vacio")`; tag o handler null lanzan NPE.
- `public void shutdown()` - libera el canal de plugin saliente que `[connect]` registro en su primer uso; lo invoca el teardown del contexto. Idempotente (CAS sobre `bungeeRegistered`).

#### Logica interna: anatomia de linea y guards

Cada linea se procesa asi (`executeLine`):

1. Se parsea el head `[tag]` inicial (`head(...)`: la linea debe empezar con `[`, tener `]` en posicion >= 1 y tag no vacio; el tag se lowercasea con `Locale.ROOT`).
2. Mientras el head sea un guard, se evalua y se re-parsea el resto (los guards se pueden ENCADENAR, ej `[right-click] [chance=50] [message] hola`). Guards reconocidos:
   - `[right-click]` - pasa cuando `clickType.isRightClick()` (incluye SHIFT_RIGHT, ver gotchas).
   - `[left-click]` - pasa cuando `clickType.isLeftClick()`.
   - `[shift-right-click]` - pasa solo con `ClickType.SHIFT_RIGHT` exacto.
   - `[shift-left-click]` - pasa solo con `ClickType.SHIFT_LEFT` exacto.
   - `[chance=N]` - tira `ThreadLocalRandom.current().nextDouble(100.0) < N` (N admite decimales, escala 0-100). Un N malformado WARN-once ("Guard [chance=...] invalido; la accion corre igual") y deja correr la linea (fail-open).
   - Si un guard de click se evalua con `context.clickType()` null (fuera de un click de GUI), la linea se OMITE con nota de debug (no WARN).
3. Si la linea no arranca con un tag valido, corre entera como `[message]`.
4. El argumento pasa por placeholders locales (`SnText.applyLocals(arg, context.phs())`) y despues por PAPI viewer-aware (`ctx.papi().apply(player, ...)`) ANTES de llegar al handler.
5. Tag desconocido: WARN-once por tag ("Accion desconocida '[tag]'; linea ignorada: ...").

Cada linea corre dentro de un try/catch de `Throwable`: una accion que explota loguea WARN "Accion fallo en '<linea>': <t>" y NO corta el resto de la lista. Lineas null o en blanco se saltean.

#### Catalogo completo de tags built-in

Render "message-like" = `SnText.color(SnText.normalizePapiOutput(arg))`: normalizacion del output de PAPI mas el pipeline completo de SnText, incluyendo `[rgb]` y `[center]` (segun el Javadoc de la clase).

| Tag | Sintaxis | Semantica exacta |
|---|---|---|
| `[player]` | `[player] comando` | `Bukkit.dispatchCommand` como el jugador. El `/` inicial se stripea. Argumento vacio: WARN-once "Accion de comando sin argumento; se ignora". |
| `[player-as-op]` | `[player-as-op] comando` | Si el jugador ya es OP, despacha directo. Si no, `setOp(true)`, despacha, y restaura `setOp(false)` en un `finally`. |
| `[console]` | `[console] comando` | Despacha con `Bukkit.getConsoleSender()`. Mismo strip de `/` y WARN de argumento vacio. |
| `[message]` | `[message] texto` (o linea sin tag) | `player.sendMessage(render(arg))`. Es el tag implicito de las lineas sin head. |
| `[broadcastmessage]` | `[broadcastmessage] texto` | `Bukkit.getServer().sendMessage(render(arg))` a todo el servidor. |
| `[actionbar]` | `[actionbar] texto` | `player.sendActionBar(render(arg))`. |
| `[title]` | `[title] titulo;subtitulo;fadeIn;stay;fadeOut` | Split por `;` (limite -1). Subtitulo default `Component.empty()`. Tiempos EN TICKS, defaults 10/70/20; se convierten a `Duration.ofMillis(ticks * 50)`. Parte en blanco o faltante usa el default; numero invalido WARN-once y usa el default. |
| `[sound]` | `[sound] SOUND_ID [vol] [pitch]` | Delega en `SoundUtil.play(player, arg)` (parseo de id/volumen/pitch vive en SoundUtil). |
| `[close]` | `[close]` | `player.closeInventory()`. |
| `[open]` | `[open] gui-id` | Abre la GUI del contexto via `ctx.guis().get(id)`. Id vacio: WARN-once. Modulo guis no declarado en el spec (`UnsupportedOperationException`): WARN-once "Accion [open] ignorada: modulo guis no declarado en el spec". Gui inexistente: WARN-once "gui '<id>' no existe". |
| `[connect]` | `[connect] servidor` | Envia el plugin message BungeeCord `Connect` + servidor al canal `"BungeeCord"`. Registra el canal saliente en el PRIMER uso (CAS); `shutdown()` lo libera. Servidor vacio o `IOException` armando el mensaje: WARN-once. |
| `[next-page]` | `[next-page]` | `PageTarget.nextPage()` del contexto. |
| `[previous-page]` | `[previous-page]` | `PageTarget.previousPage()`. |
| `[set-page]` | `[set-page] n` | `PageTarget.setPage(n)`; `n` invalido WARN-once y usa 1. |
| `[refresh-page]` | `[refresh-page]` | `PageTarget.refreshPage()` (re-render de la pagina actual). |
| `[refresh-menu]` | `[refresh-menu]` | `PageTarget.refreshMenu()` (re-render del menu completo). |
| `[particle]` | `[particle] TYPE [count] [offX offY offZ] [extra] [key=value...]` | Spawnea en el mundo del jugador, en su location +1.0 en Y. Todo token con `=` es una opcion (`color`, `size`, `to`, `block`, `item`; key lowercase, split en el primer `=`); el resto son posicionales con los umbrales de siempre: `count` default 1 con >= 1 posicional, los tres offsets solo si hay >= 4 posicionales, `extra` solo con >= 5. Ver resolucion de tipo y de data abajo. |
| `[potion]` | `[potion] EFFECT [segundos] [amplifier]` | `player.addPotionEffect(new PotionEffect(type, segundos * 20, amplifier))`. Defaults: 10 segundos, amplifier 0. Efecto invalido: WARN-once "Efecto de pocion invalido". Resolucion: `NamespacedKey.fromString(lowercase)` contra `Registry.EFFECT` primero, fallback al deprecado `PotionEffectType.getByName` para configs viejas. |
| `[remove-item]` | `[remove-item] [n] [selector]` | Sin selector: saca `n` (default 1) del item en MANO PRINCIPAL, byte-identico a v1.0.0 (mano vacia = no-op silencioso; si la cantidad en mano es mayor a `n` decrementa; si no, vacia el slot con `setItemInMainHand(null)`). Un solo token que parsea como entero es `n`; si no parsea, es el selector con `n` = 1. Selectores: `offhand` (case-insensitive, misma logica espejada sobre la offhand), `id:<item-id>` (descuenta stacks que `ctx.items().is(stack, id)` barriendo los slots de storage 0-35 y despues la offhand; id vacio o no registrado = WARN-once y linea ignorada) y cualquier otro token como nombre de Material (`Material.matchMaterial`; null o `!isItem()` = WARN-once y linea ignorada; matchea por `getType()` ignorando meta PERO excluye todo stack tagueado por SnLib de CUALQUIER contexto: para items custom existe `id:`). Remocion parcial permitida en todos los modos: si hay menos de `n` unidades se saca lo que haya, sin error ni WARN. |

Detalles de `[particle]`:

- Resolucion del tipo (`resolveParticle`): uppercase, strip del prefijo `MINECRAFT:`, `.` y `-` se vuelven `_`, y `Particle.valueOf`. Como Particle es un set abierto, hay alias leniente `REDSTONE` <-> `DUST` (WARN-once "usando alias '...'") para que specs escritas antes o despues del rename de 1.20.5 funcionen en ambos lados. Tipo invalido: WARN-once "Particula invalida"; se ignora.
- Datos de particula, resueltos por `particle.getDataType()` contra las opciones `key=value`:
  - `Void`: data null, como siempre. Si el usuario paso `color=`/`size=`/`to=`/`block=`/`item=`, WARN-once por opcion incompatible (key `"particle-opt:TYPE:key"`) y la opcion se ignora; la linea corre igual.
  - `Particle.DustOptions` (DUST): `color=#RRGGBB` o `color=R,G,B` (default `Color.RED`) y `size=F` float (default `1.0f`). Sin opciones el resultado es identico a v1.0.0 (`Color.RED`, `1.0f`).
  - `Particle.DustTransition` (DUST_COLOR_TRANSITION): `from` = opcion `color` (default `Color.RED`), `to` = opcion `to` (mismos dos formatos de color; default = `from`), `size` default `1.0f`.
  - `BlockData` (BLOCK, BLOCK_MARKER, FALLING_DUST, DUST_PILLAR): requiere `block=MATERIAL` (`Material.matchMaterial`; material null o `!isBlock()` = WARN-once y la LINEA se ignora; data = `mat.createBlockData()` con catch defensivo de `IllegalArgumentException`). Sin `block=`: WARN-once "requiere block=MATERIAL; se ignora" y la linea se salta.
  - `ItemStack` (ITEM): requiere `item=MATERIAL` (`matchMaterial` + `isItem()`; data = `new ItemStack(mat)`); misma politica de errores que `block=`.
  - Cualquier otro `dataType`: WARN-once "requiere datos no soportados; se ignora", igual que antes.
- Colores (`parseColor`): `#RRGGBB` (6 hex) o `R,G,B` (tres enteros 0-255); valor invalido = WARN-once "Color invalido" y se usa el default. `size=` invalido = WARN-once y `1.0f`. Key de opcion desconocida (ni color/size/to/block/item) = WARN-once "Opcion desconocida"; la linea corre igual.

Paginacion: los cinco tags de pagina pasan por `withPagination(...)`: con `context.pageTarget()` null o `paginationEnabled()` false son NO-OP con nota de debug "paginacion no habilitada (opt-in por menu)". La paginacion es opt-in por menu.

#### Registro de acciones custom

`register(tag, handler)` acepta el tag con o sin corchetes y case-insensitive; el handler reemplaza al anterior, incluyendo built-ins (override intencional permitido por contrato). El handler recibe el argumento YA resuelto (locals + PAPI) y siempre corre en el main thread.

#### Notas y gotchas

- `[right-click]` usa `ClickType.isRightClick()`, que tambien es true para SHIFT_RIGHT; si se necesita distinguir, existen los guards shift exactos. Idem `[left-click]` con `isLeftClick()`.
- `warnOnce` deduplica por clave dentro de CADA instancia del motor (o sea, por plugin consumidor): el mismo error en dos plugins loguea dos veces, una por logger de cada plugin.
- `[player-as-op]` abre una ventana temporal de OP; el `finally` garantiza el `setOp(false)` incluso si el comando lanza, pero solo cuando el jugador NO era op de antemano.
- En `[particle]`, dar `TYPE count offX` (sin los tres offsets) ignora el offset silenciosamente: los offsets solo se leen con >= 4 tokens POSICIONALES (las opciones `key=value` no cuentan para ese umbral).
- El orden de resolucion del argumento es locals PRIMERO, PAPI DESPUES: un placeholder local puede expandirse a un token `%...%` que PAPI luego resuelve.

### ActionContext
`src/main/java/com/sn/lib/action/ActionContext.java`

Record inmutable con el contexto de ejecucion de una corrida de acciones.

- `public record ActionContext(Player player, Sn ctx, @Nullable PageTarget pageTarget, @Nullable ClickType clickType, Ph[] phs)` - componentes: jugador destino, contexto SnLib dueño, target de paginacion (una sesion de GUI, o null fuera de un menu paginado), click que disparo la corrida (null fuera de un click de GUI; los guards de click omiten su linea cuando es null), y pares de placeholders locales aplicados a cada argumento.
- Constructor compacto: `phs = phs == null ? new Ph[0] : phs` - normaliza `phs` null a array vacio.

Los accessors generados del record (`player()`, `ctx()`, `pageTarget()`, `clickType()`, `phs()`) son la API publica.

### ActionHandler
`src/main/java/com/sn/lib/action/ActionHandler.java`

Interfaz funcional (`@FunctionalInterface`) detras de cada tag, built-in o registrado por un consumidor via `ActionEngine#register`.

- `void run(Player player, String arg, ActionContext context)` - corre la accion para el jugador, SIEMPRE en el main thread. `arg` es el argumento posterior al tag con locals y PAPI ya resueltos.

### PageTarget
`src/main/java/com/sn/lib/action/PageTarget.java`

Controles de paginacion a los que delegan las acciones de pagina; lo implementan las sesiones de GUI. La paginacion es opt-in por menu: con `paginationEnabled()` false el `ActionEngine` convierte cada accion de pagina en no-op con nota de debug.

- `void nextPage()` - avanza a la pagina siguiente.
- `void previousPage()` - vuelve a la pagina anterior.
- `void setPage(int page)` - salta a la pagina dada; las implementaciones clampean valores fuera de rango.
- `void refreshPage()` - re-renderiza la pagina actual.
- `void refreshMenu()` - re-renderiza el menu completo.
- `boolean paginationEnabled()` - true cuando el menu declaro paginacion; si no, las acciones de pagina no hacen nada.

### RequirementEngine
`src/main/java/com/sn/lib/action/RequirementEngine.java`

Clase `final` estatica (constructor privado) que parsea expresiones de requirements sobre placeholders en arboles inmutables de `Requirement`. El parseo ocurre UNA vez al load; los placeholders quedan como tokens crudos y se resuelven en cada `Requirement#test` a traves del resolver del caller.

- `public static Requirement parse(List<String> lines)` - parsea las lineas (AND implicito entre lineas) mandando los warnings al logger compartido `Logger.getLogger("SnLib")`.
- `public static Requirement parse(@Nullable List<String> lines, @Nullable Consumer<String> warn)` - idem con sink de warnings delegable (logger del plugin, SnDebug, etc.); con `warn` null usa el logger compartido deduplicado por contenido del mensaje. Input null, vacio o solo lineas en blanco produce un requirement que SIEMPRE pasa.

Enum interno (privado) `Op` con los operadores de comparacion: `GE(">=")`, `LE("<=")`, `EQ_STRICT("==")`, `NE("!=")`, `GT(">")`, `LT("<")`, `EQ("=")`. Los simbolos de dos caracteres estan declarados primero para que el escaneo los prefiera sobre `>`, `<` y `=`. Constante estatica privada `WARNED` (set server-wide): el contrato de SnLib permite este static porque solo deduplica logs, no guarda datos de consumidores.

#### Gramatica y precedencia

- Por linea: comparaciones `left OP right` unidas por `&&` y `||`, con AND ligando MAS fuerte que OR, y agrupables con parentesis. Descenso recursivo sobre la lista de tokens: `expr := and ('||' and)*` (-> `AnyOf`), `and := primary ('&&' primary)*` (-> `AllOf`), `primary := '(' expr ')' | comparacion`.
- Entre lineas de una lista: AND implicito.
- Quoting: un operando puede envolverse en `'` o `"`; dentro de la region quoted los conectores, los parentesis y los simbolos de operador quedan LITERALES (tanto el tokenizer como el scan de operadores trackean el estado de comillas). Al parsear la comparacion se quita UN par de comillas balanceadas que envuelva el operando completo (primer char == ultimo char, ambos `'` o ambos `"`, largo >= 2); el contenido interno NO se re-trimea (un operando quoted preserva espacios internos y de borde). Una comilla sin cerrar extiende la region lenientemente hasta el fin de la linea, sin WARN propio.
- Deteccion del operador (`parseComparison`): se escanea el texto de izquierda a derecha FUERA de comillas y en cada indice se prueban los operadores en orden de declaracion del enum, asi `>=` gana sobre `>`. Operando vacio (tras el strip de comillas) a cualquier lado, o ausencia de operador, hacen la comparacion invalida.
- Fail-open: cualquier malformacion (comparacion invalida, `(` sin cierre, `)` suelto o tokens sobrantes al terminar la expresion, parentesis vacios, conector colgante) convierte la LINEA ENTERA en always-true con un solo WARN ("Requirement malformado: '<linea>'; se evalua como true"), para que una config rota nunca deje jugadores afuera.
- Cambio de interpretacion respecto de 1.0.0 (aceptado): un operando que contenia `(`, `)`, `&&` o `||` literales sin comillas ahora requiere quoting; sin quotear, la linea cae en fail-open (true + WARN), nunca bloquea jugadores. Toda expresion sin comillas ni parentesis produce un arbol IDENTICO al de 1.0.0.

#### Coercion en evaluacion

En cada `test`, ambos tokens se resuelven via el resolver (resolver null o resultado null dejan el token crudo). Luego:

- Si AMBOS lados parsean como `Double`, la comparacion es numerica (`Double.compare`).
- Si no: `=`/`==` y `!=` comparan lexicograficamente case-insensitive (`equalsIgnoreCase`); `=` y `==` son semanticamente identicos.
- Los relacionales (`>`, `<`, `>=`, `<=`) sobre valores no numericos evaluan a FALSE con warn ("Comparacion no numerica con '<op>': ... se evalua como false").

#### Logica interna

Los nodos del arbol son records privados: `AllOf` (AND con short-circuit, copia defensiva `List.copyOf`), `AnyOf` (OR con short-circuit) y `Comparison` (hoja que retiene los tokens crudos ya des-quoteados y el sink de warn). El parseo por linea es un tokenizer de UNA pasada (tokens `LPAREN`/`RPAREN`/`AND`/`OR`/`TEXT`, quote-aware; el whitespace se conserva dentro de los runs TEXT pero los runs de solo whitespace entre tokens estructurales se descartan) seguido del descenso recursivo con indice mutable; las malformaciones cortan via una excepcion interna (`MalformedLineException`) que `parseLine` traduce al WARN unico. `ALWAYS_TRUE` es el requirement constante para inputs vacios y lineas malformadas.

### Requirement
`src/main/java/com/sn/lib/action/Requirement.java`

Interfaz funcional (`@FunctionalInterface`) de un requirement inmutable pre-parseado, evaluado contra valores de placeholders en runtime. Las instancias se construyen una vez al load via `RequirementEngine#parse` y mantienen los placeholders como tokens crudos: cada evaluacion los resuelve de nuevo, por lo que UNA instancia sirve para cualquier jugador.

- `boolean test(@Nullable Player player, @Nullable Function<String, String> resolver)` - evalua el requirement. `player` puede ser null para chequeos a nivel servidor; `resolver` resuelve cada token de operando a su valor actual (tipicamente locals + PAPI atados a `player`); resolver null deja los tokens intactos.

### SnPapi
`src/main/java/com/sn/lib/papi/SnPapi.java`

Servicio PlaceholderAPI de un contexto consumidor, alcanzado via `sn.papi()`. Clase `final`; el aislamiento vive en el holder interno: con PlaceholderAPI ausente el texto vuelve intacto y NUNCA se carga una clase de PAPI.

- `public SnPapi(Sn ctx)` - crea el servicio para el contexto; construye un `PapiHolder(ctx.plugin())`. La presencia de PAPI se sondea lazy.
- `public String apply(@Nullable Player viewer, String text)` - resuelve tokens PAPI en `text` contra el viewer, o contra el servidor cuando el viewer es null. Fast-path: texto null o sin `'%'` retorna tal cual sin tocar el holder. PAPI ausente: texto intacto. Resolucion main-thread ONLY: fuera del primary thread los tokens quedan intactos y el skip se registra via el servicio de debug del contexto ("PAPI omitido fuera del main thread; tokens intactos: ...").
- `public List<String> apply(@Nullable Player viewer, List<String> lines)` - overload de lista, resuelve linea por linea; lista null o vacia retorna la misma referencia.
- `public boolean available()` - true cuando el plugin PlaceholderAPI esta presente y habilitado (delegado al holder, cacheado).
- `public void invalidate()` - descarta el probe de presencia cacheado; el proximo apply o register vuelve a sondear (util cuando el plugin PAPI se togglea).
- `public ExpansionBuilder expansion(String identifier)` - arranca una expansion declarativa bajo `identifier`. Defaults del builder: autor = autores del plugin unidos con ", " (o el nombre del plugin si la lista esta vacia), version = version del `plugin.yml`.
- `public void unregisterAll()` - desregistra toda expansion que este contexto registro; lo invoca el teardown del contexto.
- `boolean registerExpansion(String identifier, String author, String version, Map<String, Function<OfflinePlayer, String>> exact, Map<String, BiFunction<OfflinePlayer, String, String>> prefixed)` - (package-private) puente del builder hacia `PapiHolder.register`.

### ExpansionBuilder
`src/main/java/com/sn/lib/papi/ExpansionBuilder.java`

Builder declarativo de una expansion PlaceholderAPI, obtenido via `SnPapi#expansion(String)`. La expansion construida reporta `persist() = true` (sobrevive los reloads de expansiones de PlaceholderAPI y solo la remueve el teardown del contexto) y null-chequea el `OfflinePlayer` solicitante antes de tocar cualquier resolver: player null deja el token sin resolver. Contrato cache-only: los resolvers corren en el main thread dentro del parse de PAPI, asi que deben leer estado precomputado en memoria y jamas tocar disco, base de datos o red.

- `public ExpansionBuilder placeholder(String param, Function<OfflinePlayer, String> resolver)` - liga `%<identifier>_<param>%` al resolver. Matching case-insensitive (la clave se lowercasea con `Locale.ROOT`); los placeholders exactos GANAN sobre los prefijados.
- `public ExpansionBuilder prefixed(String prefix, BiFunction<OfflinePlayer, String, String> resolver)` - liga cada `%<identifier>_<prefix><rest>%` al resolver, que recibe el resto despues del prefijo como segundo argumento. Los prefijos se prueban EN ORDEN DE REGISTRO (LinkedHashMap), despues de los exactos.
- `public ExpansionBuilder author(String author)` - autor reportado a PlaceholderAPI; default los autores del plugin.
- `public ExpansionBuilder version(String version)` - version reportada a PlaceholderAPI; default la version del plugin.
- `public boolean register()` - registra la expansion en PlaceholderAPI, desregistrando primero cualquier previa bajo el mismo identifier (lookup-before-register: un segundo enable del consumidor nunca falla). Los mapas se copian defensivamente (`new LinkedHashMap<>(...)`) al registrar. La instancia registrada queda trackeada en el contexto para desregistro en shutdown. Retorna false con WARN cuando PlaceholderAPI esta ausente o rechaza el registro.

### PapiHolder (internal)
`src/main/java/com/sn/lib/papi/internal/PapiHolder.java`

Capa de aislamiento lazy de PlaceholderAPI de UN contexto consumidor. Diseño clave del classloader: TODA referencia bytecode a clases de PAPI vive en las clases anidadas `Bridge` y `BuiltExpansion`, que se cargan solo despues de que el probe de presencia da positivo; la clase externa no referencia ningun tipo PAPI (la lista de registradas es `List<Object>`), asi un servidor sin PlaceholderAPI nunca dispara `NoClassDefFoundError`. El flag de presencia se sondea lazy, se cachea en un `volatile Boolean`, y se descarta via `invalidate()` cuando el plugin objetivo se togglea.

- `public PapiHolder(JavaPlugin owner)` - crea el holder para el plugin dueño (su logger recibe los WARNs).
- `public boolean available()` - true cuando el plugin `"PlaceholderAPI"` esta presente y habilitado (`getPlugin` + `isEnabled`); sondeo lazy, resultado cacheado.
- `public void invalidate()` - descarta el flag cacheado; la proxima llamada vuelve a sondear.
- `public String apply(@Nullable OfflinePlayer player, String text)` - resuelve tokens PAPI via `Bridge.setPlaceholders`, o retorna el texto intacto cuando PAPI no esta disponible. Un `LinkageError` marca el modulo como roto (`present = FALSE`, WARN "PlaceholderAPI inaccesible (...); modulo papi degradado") y retorna el texto intacto.
- `public boolean register(String identifier, String author, String version, Map<String, Function<OfflinePlayer, String>> exact, Map<String, BiFunction<OfflinePlayer, String, String>> prefixed)` - registra una expansion declarativa, desregistrando primero cualquier previa con el mismo identifier (lookup-before-register). PAPI ausente: WARN "PlaceholderAPI ausente: expansion '<id>' no registrada" y false. Registro rechazado por PAPI: WARN "PlaceholderAPI rechazo la expansion '<id>'" y false. La instancia queda trackeada (CopyOnWriteArrayList) para `unregisterAll()`. `LinkageError` degrada el modulo y retorna false.
- `public void unregisterAll()` - desregistra cada expansion registrada por este holder (teardown del contexto); drena la lista primero y tolera `LinkageError` degradando el modulo.

#### Logica interna

- `Bridge` (estatica, privada): junto con `BuiltExpansion` es la UNICA clase cuyo constant pool referencia tipos PAPI; se carga exclusivamente detras de un probe de presencia exitoso. Metodos: `setPlaceholders(player, text)` delega en `PlaceholderAPI.setPlaceholders`; `register(...)` busca la expansion existente via `PlaceholderAPIPlugin.getInstance().getLocalExpansionManager().getExpansion(identifier.toLowerCase(Locale.ROOT))`, la desregistra si existe (este es el lookup-antes-de-registrar concreto), construye la `BuiltExpansion` y retorna null si `expansion.register()` falla; `unregister(expansion)` castea a `PlaceholderExpansion` y desregistra.
- `BuiltExpansion` (estatica, privada, extiende `PlaceholderExpansion`): expansion construida desde los mapas declarativos. `getIdentifier()`/`getAuthor()`/`getVersion()` devuelven lo configurado. `persist()` retorna TRUE: sobrevive los reloads de expansiones de PlaceholderAPI (ej `/papi reload`) y solo la remueve el teardown del contexto. `onRequest(player, params)`: player null retorna null (token sin resolver); la clave se lowercasea; primero busca resolver EXACTO, despues recorre los prefijos en orden de registro con `startsWith`, pasando el resto despues del prefijo como argumento; sin match retorna null. `resolveSafe` envuelve cada resolver en try/catch de `Throwable`: una excepcion loguea WARN "Placeholder '%<id>_<params>%' fallo al resolver: <t>" y retorna null en vez de romper el parse de PAPI.

#### Notas y gotchas

- El patron holder existe porque un `import` normal de PAPI en una clase que siempre se carga romperia en servidores sin PlaceholderAPI; el probe (`available()`) usa solo la API de Bukkit y la carga de `Bridge`/`BuiltExpansion` queda gated detras de el.
- `persist() = true` + lookup-antes-de-registrar juntos garantizan que reiniciar/rehabilitar el plugin consumidor o recargar PAPI nunca deje expansiones duplicadas ni registros fallidos por identifier tomado.
- `markBroken` en `LinkageError` es defensa contra mismatches de version de PAPI en runtime: degrada el modulo a no-op (texto intacto) en vez de spamear errores.

### TODOs y limitaciones

No hay marcadores TODO/FIXME explicitos en ninguno de los archivos del alcance. Limitaciones documentadas en el codigo:

- `[particle]` soporta los dataTypes `Void`, `Particle.DustOptions` (opciones `color=`/`size=`, defaults Color.RED y 1.0f), `Particle.DustTransition` (`color=`/`to=`/`size=`), `BlockData` (`block=MATERIAL` obligatorio) e `ItemStack` (`item=MATERIAL` obligatorio); cualquier otro `dataType` (ej. Vibration, Trail) sigue ignorandose con WARN ("requiere datos no soportados").
- `[remove-item]` cubre mano principal (default), `offhand`, material (`MATERIAL`, excluyendo stacks tagueados por SnLib) e `id:<item-id>`; el barrido por selector alcanza los slots de storage 0-35 mas la offhand (no toca armadura ni cursor) y no hay soporte de slot arbitrario.
- La gramatica de requirements soporta agrupamiento con `( )` y quoting de operandos con `'` o `"`; la contracara es que un operando con `(`, `)`, `&&`, `||` o simbolos de operador literales ahora DEBE quotearse (sin comillas la linea cae en fail-open con WARN, nunca bloquea jugadores).
- La resolucion PAPI es main-thread only por diseño: fuera del primary thread los tokens quedan intactos (con nota de debug), no hay cola ni fallback async.
- Los resolvers de expansiones tienen contrato cache-only (memoria precomputada); el holder no ofrece variante async para resolvers con I/O.

---

# 09

## 09. Multi-tenant, cleanup y reload

SnLib es una libreria compartida: un unico jar (un unico classloader) sirve a ~57 plugins consumidores a la vez. Este modulo garantiza que el estado de cada consumidor (GUIs, comandos, cooldowns, callbacks, inventarios abiertos) viva bajo su `Plugin` owner y muera con el, sin filtrar classloaders ni tocar a los demas consumidores. Los pilares son: `TenantRegistry` (contenedor base por owner con sweep), `ListenerHub` (punto unico de registro de eventos), `TenantSweeper` (doble red al deshabilitarse un plugin), `QuitCleanupListener` (unico listener de quit/kick), `Cooldowns` (estado por jugador sin boxing y con politica de relog) y `ReloadManager` (reload por contexto en 7 fases estrictas).

### Regla dura del classloader compartido

Documentada en el Javadoc de `TenantRegistry`: los estaticos SIN namespace de owner solo se permiten para datos server-wide (SnVersion/SnCompat). Todo lo que contenga datos de plugin, jugador o sesion pasa por una instancia de `TenantRegistry` con un `Plugin` owner explicito. Las instancias de `TenantRegistry` son campos estaticos de clases de la libreria y viven lo que vive la libreria; nunca se crea una por contexto. Cada estatico server-wide del modulo lleva un comentario "Server-wide static justified: ..." que justifica la excepcion (la enumeracion `REGISTRIES` para el sweep, los `OPEN_HOLDERS` del sweeper, los `CALLBACKS` de quit-cleanup).

### Criterio de NO-interferencia

Regla transversal del modulo: toda operacion de limpieza o reload afecta EXCLUSIVAMENTE al owner involucrado.

- `TenantRegistry.removeOwner(owner)` toca solo la key de ese owner; las registraciones de todos los demas plugins quedan intactas.
- El sweep per-consumidor de `TenantSweeper` barre unicamente las registraciones de ese owner.
- `ReloadManager.reloadPlugin()` reconstruye solo los modulos del plugin dueño del contexto: jamas toca el estado de otros consumidores ni el de la libreria misma.

### TenantRegistry

`src/main/java/com/sn/lib/tenant/TenantRegistry.java`

Registro multi-tenant generico keyed por el plugin owner: el contenedor base de todo estado per-plugin que la libreria mantiene (GUIs, comandos, cooldowns, callbacks de listeners, expansiones, recetas, hologramas, bossbars, soft dependencies). Internamente es un `Map<Plugin, Set<T>>` sobre `ConcurrentHashMap`, con un `Consumer<T> onSweep` opcional. Cada instancia se auto-inscribe en el set estatico `REGISTRIES` para que `sweepOwner` pueda enumerar todos los registros existentes.

- `public TenantRegistry()` - registro sin callback de sweep.
- `public TenantRegistry(@Nullable Consumer<T> onSweep)` - registro cuyos valores reciben `onSweep` cuando su owner key se remueve via `removeOwner`; permite que un sweep libere recursos (force-disable de hooks, cierre de inventarios) aun cuando el owner nunca limpio.
- `public void add(Plugin owner, T value)` - registra un valor bajo su owner. La mutacion va dentro de `compute`: atomica por key contra el drop de `remove()`, asi un alta concurrente jamas cae en un set cuya key acaba de removerse.
- `public void remove(Plugin owner, T value)` - desregistra un valor, dropeando la key del owner cuando su set queda vacio.
- `public Set<T> forOwner(Plugin owner)` - vista no modificable de los valores del owner; vacia cuando no tiene ninguno.
- `public Set<T> removeOwner(Plugin owner)` - remueve la key COMPLETA del owner y devuelve los valores que tenia, aplicando el callback de sweep (si esta configurado) a cada uno. Cada fallo del callback se atrapa como `Throwable` y se loguea WARN "Sweep de una registracion fallo: ...". Solo la key de ese owner se toca (no-interferencia).
- `public void forEachOwner(BiConsumer<Plugin, Set<T>> action)` - aplica la accion a cada owner con una vista no modificable de sus valores.
- `public static void sweepOwner(Plugin owner)` - barre un owner de TODOS los registros existentes; cada registro pierde solo la key de ese owner.

#### Notas y gotchas

- Por que `removeOwner` remueve la key completa y no solo los valores: mantener la key `Plugin` en el map mantendria alcanzable el classloader del plugin deshabilitado (el Javadoc lo llama "the ManticCommand leak"). Remover la key entera corta esa referencia.
- El `onSweep` es defensivo por diseño: se ejecuta valor por valor con try/catch individual, de modo que un valor roto no impide barrer el resto.

### OwnedHolder

`src/main/java/com/sn/lib/tenant/OwnedHolder.java`

Interfaz marcadora (`extends InventoryHolder`) de todo inventario que la libreria crea en nombre de un plugin consumidor. El tenant sweeper y el quit cleanup listener compilan contra esta interfaz para identificar inventarios de la libreria y su owner sin depender del modulo GUI; el holder del modulo GUI la implementa.

- `Plugin owner()` - plugin al que pertenece el inventario.

### ListenerHub (internal)

`src/main/java/com/sn/lib/tenant/internal/ListenerHub.java`

Punto de inscripcion unico de todos los listeners compartidos de la libreria. Mecanica fija: cada modulo inscribe su listener compartido aca (acumulado en una `CopyOnWriteArrayList` desde el inicializador estatico, antes de cualquier llamada de bootstrap) y `registerAll` realiza la UNICA llamada `registerEvents` de toda la libreria, invocada una vez desde el bootstrap de `SnLibPlugin`. Ningun codigo de la libreria puede registrar eventos en otro lado.

- `public static void inscribe(Listener listener)` - agrega un listener compartido al hub; queda dormido hasta `registerAll`.
- `public static void registerAll(SnLibPlugin plugin)` - registra cada listener inscripto contra el plugin SnLib. Idempotente: primero hace `HandlerList.unregisterAll(plugin)` para dropear las registraciones previas de SnLib, asi una doble llamada o un re-enable nunca duplican handlers (un disable de SnLib tambien los desregistra a todos).

#### Enumeracion canonica de los 11 listeners (con su paquete de origen)

Orden exacto del inicializador estatico:

1. `new HookListener(SoftDependency::forEachRegistered)` - `com.sn.lib.hook` (hooks / soft dependencies).
2. `new TenantSweeper()` - `com.sn.lib.tenant.internal` (sweep por disable de plugin).
3. `new QuitCleanupListener()` - `com.sn.lib.internal` (quit/kick cleanup).
4. `new ArmourEquipListener()` - `com.sn.lib.event.internal` (eventos de armadura).
5. `new ItemPropertyListener()` - `com.sn.lib.item.internal` (propiedades de items).
6. `new ItemInteractListener()` - `com.sn.lib.item.internal` (interacciones de items).
7. `new LockedItemListener()` - `com.sn.lib.item.internal` (items bloqueados).
8. `new GuiClickListener()` - `com.sn.lib.gui.internal` (clicks de GUI).
9. `new GuiProtectionListener()` - `com.sn.lib.gui.internal` (proteccion de GUI).
10. `PlayerDataCache.joinListener()` - `com.sn.lib.db` (cache de datos de jugador, join).
11. `new HologramChunkListener()` - `com.sn.lib.hologram.internal` (carga/descarga de chunks para hologramas).

### TenantSweeper (internal)

`src/main/java/com/sn/lib/tenant/internal/TenantSweeper.java`

Listener compartido de doble red, owned por SnLib: cuando un consumidor se deshabilita, se barre toda registracion per-owner EXCLUSIVAMENTE de ese owner (no-interferencia), se cierran sus inventarios de libreria abiertos y se remueve su key de contexto; cuando SnLib mismo se deshabilita, la cascada completa apaga todos los contextos vivos en orden inverso de registracion.

Interfaz interna `ContextAccess` (acceso al registro de contextos, instalado por el inicializador estatico de SnLib):

- `boolean detach(Plugin owner, Sn expected)` - remueve la key de contexto del owner solo si todavia mapea a `expected`.
- `List<Sn> detachAllReversed()` - remueve y devuelve todos los contextos, en orden inverso de registracion.

Metodos publicos:

- `public static void bindContexts(ContextAccess access)` - instala el acceso al registro de contextos de SnLib; llamado desde el static init de SnLib.
- `public static void trackInventory(OwnedHolder holder)` - trackea un inventario de libreria abierto para que un disable de su owner lo cierre (va al `TenantRegistry<OwnedHolder> OPEN_HOLDERS` estatico, cuyo callback de sweep cierra el inventario expulsando a cada viewer).
- `public static void untrackInventory(OwnedHolder holder)` - deja de trackear un inventario de libreria una vez cerrado.
- `public static void forEachOpenInventory(Consumer<OwnedHolder> action)` - aplica la accion a cada inventario de libreria abierto trackeado, cruzando todos los owners.
- `public void onPluginDisable(PluginDisableEvent event)` - handler `@EventHandler(priority = EventPriority.MONITOR)`. Si el plugin deshabilitado es `SnLibPlugin`, dispara `cascadeAll()` de inmediato. Si es un consumidor con contexto (`SnLib.context(owner) != null`), difiere el sweep 1 tick.
- `public static void cascadeAll()` - cascada de shutdown completa: detacha y apaga cada contexto vivo en orden inverso de registracion. Idempotente (el registro se drena en la primera pasada); la dispara el evento de disable de SnLib y se invoca de nuevo desde el `onDisable` del bootstrap como doble red por si el listener nunca llego a registrarse. Por cada contexto: WARN de shutdown forzado si no estaba cerrandose (y no es SnLib mismo), `shutdownQuietly(ctx)`, `OPEN_HOLDERS.removeOwner(owner)` y `TenantRegistry.sweepOwner(owner)`.

#### Logica interna

- Red diferida 1 tick: Bukkit dispara `PluginDisableEvent` ANTES del `onDisable` propio del plugin. Por eso el sweep per-consumidor se difiere un tick (via `Bukkit.getGlobalRegionScheduler().run(...)` en Folia o `Bukkit.getScheduler().runTask(...)` en Bukkit, siempre con SnLib como plugin agendador): el teardown prolijo (el `onDisable` del consumidor llamando `Sn.shutdown()`) corre primero sobre modulos vivos, y la pasada diferida solo repasa lo que el owner dejo atras. Si SnLib ya no esta enabled, o el scheduler no esta disponible (carrera de shutdown del server), el defer se aborta en silencio y los restos los atrapa la cascada de SnLib, que corre despues de que ya se deshabilito cada consumidor que hard-depende de la lib.
- `sweep(Plugin owner, Sn captured)` (privado): compara el contexto actual contra el capturado en el momento del disable; si el owner ya re-registro un contexto NUEVO (re-enable dentro del mismo tick), no hace nada. Si el contexto sigue vivo y no esta en shutdown, loguea el WARN "Contexto SnLib no cerrado en onDisable; shutdown forzado por el sweeper (doble red)" y lo apaga. Luego `OPEN_HOLDERS.removeOwner(owner)`, `TenantRegistry.sweepOwner(owner)`, `SoftDependency.targetDisabled(owner.getName())` y `access.detach(owner, captured)` (detach condicionado al contexto capturado).
- `shutdownQuietly(Sn ctx)` (privado): `ctx.shutdown()` con catch de `Throwable` y WARN "Shutdown del contexto fallo: ...".
- `closeHolder(OwnedHolder holder)` (privado, callback de sweep de `OPEN_HOLDERS`): cierra el inventario iterando una copia de sus viewers (`List.copyOf(holder.getInventory().getViewers())`) y llamando `viewer.closeInventory()`; en fallo, WARN "No se pudo cerrar un inventario de la lib: ...".

#### Notas y gotchas

- El WARN de "shutdown forzado por el sweeper (doble red)" es el sintoma de un consumidor que no llamo `Sn.shutdown()` en su `onDisable`: la lib lo cubre, pero avisa.
- El detach condicionado (`detach(owner, expected)`) evita que un sweep diferido pise un contexto recien re-registrado por un re-enable rapido del mismo plugin.

### QuitCleanupListener (internal)

`src/main/java/com/sn/lib/internal/QuitCleanupListener.java`

El UNICO listener de `PlayerQuitEvent`/`PlayerKickEvent` de toda la libreria, owned por SnLib. Los modulos registran callbacks de cleanup per-owner via `register`; en quit o kick, el listener fuerza el cierre del inventario abierto del jugador cuando su holder es un `OwnedHolder` de la libreria y despues corre los callbacks de todos los owners con el UUID del jugador.

- `public static void register(Plugin owner, Consumer<UUID> callback)` - registra un callback que corre con el UUID del jugador que se va; se barre per-owner (los callbacks viven en un `TenantRegistry<Consumer<UUID>>` estatico).
- `public void onQuit(PlayerQuitEvent event)` - handler `@EventHandler(priority = EventPriority.HIGHEST)`; delega en el cleanup privado.
- `public void onKick(PlayerKickEvent event)` - handler `@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)`; delega en el cleanup privado.

#### Logica interna

- `cleanup(Player player)` (privado): primero cierra el inventario de libreria si el jugador esta mirando uno, despues recorre los callbacks de todos los owners con try/catch individual; en fallo WARN "Callback de quit-cleanup fallo: ...".
- `closeLibraryInventory(Player player)` (privado): detecta si el jugador esta viendo un inventario de la lib recorriendo los `OwnedHolder` trackeados y sus viewers (via `TenantSweeper.forEachOpenInventory`) en vez de usar la API de open-view del jugador, cuyo tipo de vista es binariamente incompatible a traves del limite 1.20.4/1.21 y esta prohibida en la codebase. Si esta viendo uno, `player.closeInventory()`.

#### Notas y gotchas

- Un jugador kickeado dispara AMBOS eventos (kick y quit), por lo que los callbacks registrados DEBEN ser idempotentes (documentado en el Javadoc de la clase).

### Cooldowns

`src/main/java/com/sn/lib/cooldown/Cooldowns.java`

Store de cooldowns per-contexto, keyed por categoria y jugador. El estado es `Map<String, Map<UUID, long[]>>` donde cada celda `long[]` de un elemento contiene el epoch millis de expiracion: sin boxing de `Long` en el hot path. Constante privada `SWEEP_PERIOD_TICKS = 5L * 60L * 20L` (5 minutos).

- `public Cooldowns(Sn ctx)` - constructor; registra `this::clearSession` en `QuitCleanupListener` bajo el plugin del contexto.
- `public boolean tryUse(UUID player, String category, Duration cooldown)` - arma el cooldown de la categoria para el jugador salvo que siga corriendo. Devuelve true cuando la accion puede correr (cooldown armado o re-armado); false mientras el jugador sigue en cooldown.
- `public boolean tryUseTicks(UUID player, String category, long cooldownTicks)` - variante en ticks de `tryUse` (1 tick = 50 ms).
- `public long remainingMillis(UUID player, String category)` - milisegundos restantes del cooldown del jugador; 0 cuando expiro o nunca se armo. Purga lazy: una entrada expirada se remueve en la lectura (con `remove(player, expiry)` condicionado al mismo array, seguro ante carreras).
- `public void registerSessionCategory(String category)` - marca una categoria como session-scoped: sus entradas se limpian cuando el jugador hace quit o es kickeado. Las entradas de toda otra categoria sobreviven relogs por diseño.
- `public void clearSession(UUID player)` - dropea las entradas del jugador en cada categoria de sesion; las categorias persistentes quedan.
- `public void clearAll()` - dropea toda entrada de toda categoria y frena la task de sweep (cancel con catch de `Throwable`: el scheduler puede ya no existir durante el shutdown).

#### Logica interna

- Politica de relog: las entradas no expiradas NUNCA se dropean cuando un jugador hace quit, asi un relog no resetea cooldowns. La unica excepcion explicita son las categorias registradas via `registerSessionCategory`, limpiadas en quit/kick por el quit cleanup listener.
- `tryUseMillis` (privado): con `cooldownMillis <= 0` devuelve true directo. El arme es atomico via `entries.compute(player, ...)`: si existe una expiry vigente (`expiry[0] > now`) gana la existente y devuelve false; si no, gana el array nuevo `armed` y devuelve true (comparacion por identidad `winner == armed`).
- `ensureSweepScheduled` (privado): double-checked (volatile + synchronized) y no agenda si el contexto esta en shutdown. Agenda `ctx.scheduler().timerAsync(SWEEP_PERIOD_TICKS, SWEEP_PERIOD_TICKS, this::sweepExpired)` en el primer uso; `sweepScheduled` pasa a true SOLO tras agendar con exito. Si falla, `sweepScheduled` queda en false (el proximo `tryUse*` reintenta agendar) y el WARN "No se pudo agendar el sweep de cooldowns; queda solo la purga lazy: ..." se emite una unica vez (flag `sweepWarned`, accedido solo bajo `synchronized(this)`; `clearAll` lo resetea junto con `sweepScheduled`).
- `sweepExpired` (privado, corre ASYNC cada 5 minutos): `removeIf(expiry -> expiry[0] <= now)` sobre cada categoria. Seguro porque los maps son `ConcurrentHashMap`.

#### Notas y gotchas

- Doble estrategia de purga: lazy en lectura (`remainingMillis`) mas sweep async periodico; si el sweep no pudo agendarse, queda la purga lazy y el proximo `tryUse*` reintenta agendar (WARN unico).
- El `long[]` de un elemento es una decision deliberada de performance: evita el boxing `Long` en el hot path de `tryUse`/`remainingMillis` y a la vez da una identidad de objeto para el CAS logico de `compute` y el `remove` condicionado.

### ReloadManager

`src/main/java/com/sn/lib/reload/ReloadManager.java`

Orquestador de reload de un contexto consumidor, alcanzado via `sn.reload()`. Lo invocan el subcomando `reload` por defecto, `/snlib reload <plugin>` y codigo programatico; `Sn.reloadAll()` delega aca. Main-thread only. Un reload NUNCA recarga clases: actualizar SnLib.jar requiere restart del server. La relectura sincronica de I/O se acepta SOLO porque reload es un comando administrativo que nunca corre durante gameplay.

- `public ReloadManager(Sn ctx)` - crea el manager para el contexto dado; lo instancia el contexto.
- `public void register(Reloadable reloadable)` - registra un componente del consumidor para re-despacharlo (re-cache tipado) en cada `reloadPlugin()` de este contexto. Ignora null. Lista interna `CopyOnWriteArrayList`.
- `public ReloadManager reopenGuis(boolean reopen)` - opt-in del paso final del reload: cuando esta habilitado, las GUIs abiertas al momento del reload se re-abren para sus viewers en su pagina despues. Default off: las GUIs recargadas quedan cerradas. Devuelve `this` (fluido).
- `public void reloadPlugin()` - recarga cada modulo del plugin owner en el orden estricto documentado (ver abajo).

#### Orden estricto de las 7 fases de `reloadPlugin()`

Antes de la fase 1, si `reopenGuis` esta activo se captura un snapshot inmutable de las sesiones abiertas (viewer UUID, guiId, pagina) via el record privado `OpenGui`.

1. Cerrar las GUIs de este contexto ANTES de releer ymls (`guis.closeAll()`); cerrar cada sesion per-viewer tambien cancela sus `TaskHandle` de render/update.
2. Cancelar las tasks de render/update per-contexto restantes (`ctx.items().cancelTasks()`, el timer de held-effects).
3. Releer ymls en orden: config primero (re-merge managed, `yml.reloadAll()`), despues lang (`lang.reload()`), guis (`guis.load()`) e items (`ctx.items().reload()`).
4. Re-registrar los command roots de este owner (`ctx.commands().reregisterAll()`); cada pasada de registro refresca los arboles cliente via `player.updateCommands()`.
5. Re-despachar los `Reloadable` registrados (re-cache tipado), cada uno con try/catch de `Throwable` y log SEVERE "Un Reloadable registrado fallo durante el reload"; los hooks per-file `onReload` ya se dispararon durante la relectura.
6. Ciclo de recetas en el main thread (`ctx.items().reloadRecipes()`): desregistrar cada recipe key de este owner y re-agregar las recetas de las definiciones recargadas.
7. Re-abrir las GUIs capturadas solo con opt-in; por default quedan cerradas. Solo se reabre si el viewer sigue online y la GUI recargada todavia existe (`gui.open(player, open.page())`).

#### Logica interna

- `guisOrNull()` / `ymlOrNull()` / `langOrNull()` (privados): acceden a los modulos del contexto atrapando `UnsupportedOperationException`; un modulo no declarado por el consumidor simplemente se saltea en el flujo (el reload no exige que el plugin use GUI, yml o lang).
- `capture(List<GuiSession>)` (privado estatico): snapshot inmutable de las sesiones abiertas antes de que el reload las cierre.
- Record privado `OpenGui(UUID viewer, String guiId, int page)`.

#### Notas y gotchas

- El orden config -> lang -> guis -> items en la fase 3 no es casual: las capas posteriores dependen de valores de las anteriores.
- Fase 5 aclara que los hooks `onReload` per-file ya corrieron durante la relectura de la fase 3; los `Reloadable` son para re-caches tipados del consumidor, no para releer archivos.

### Reloadable

`src/main/java/com/sn/lib/reload/Reloadable.java`

Interfaz funcional: un componente capaz de reconstruir su estado desde sus fuentes (archivos, registros, caches).

- `void reload()` - reconstruye el estado del componente; lo invoca el flujo de reload de su contexto owner.

### Registrable

`src/main/java/com/sn/lib/reload/Registrable.java`

Interfaz: un componente que puede attachearse y detachearse de un registro server-side.

- `void register()` - attachea el componente a su registro.
- `void unregister()` - detachea el componente de su registro; seguro de llamar cuando no esta registrado.

### TODOs y limitaciones

No hay marcadores TODO/FIXME/placeholder en los archivos de este modulo. Limitaciones documentadas en Javadoc/codigo:

- Un reload NUNCA recarga clases: actualizar SnLib.jar requiere restart del server (`ReloadManager`).
- El I/O sincronico del reload se acepta solo por ser comando administrativo; no debe correr durante gameplay (`ReloadManager`).
- Un jugador kickeado dispara kick y quit: los callbacks de `QuitCleanupListener.register` deben ser idempotentes.
- Si el sweep async de `Cooldowns` no puede agendarse, queda la purga lazy en lectura y el proximo `tryUse*` reintenta agendar; el WARN se emite una sola vez.
- Cuando el scheduler no esta disponible (carrera de shutdown del server), la red diferida de 1 tick del `TenantSweeper` se aborta en silencio y los restos quedan a cargo de la cascada de SnLib.
- La API de open-view del jugador esta prohibida en la codebase por incompatibilidad binaria 1.20.4/1.21; la deteccion de inventarios abiertos se hace recorriendo los `OwnedHolder` trackeados.

---

# (Seccion generada - documentacion SnLib v1.0.0)

## 10. Eventos custom

El paquete `com.sn.lib.event` define la infraestructura de eventos propios de SnLib: dos bases abstractas auto-disparables (`SnEvent` y `SnPlayerEvent`, ambas `Cancellable`, con el metodo `call()` que despacha via `PluginManager` y devuelve si el evento sobrevivio), el evento concreto `SnArmourEquipEvent` (equip/unequip de armadura por cualquier vector) y el enum `EquipMethod` con los 8 vectores de entrada. La sintesis del evento de armadura la hace el listener compartido `internal/ArmourEquipListener`, propiedad de SnLib: se inscribe una unica vez en el `ListenerHub` (`src/main/java/com/sn/lib/tenant/internal/ListenerHub.java`, `inscribe(new ArmourEquipListener())`) y el `registerEvents` ocurre UNICAMENTE en el bootstrap de `SnLibPlugin`, de modo que los ~57 plugins consumidores escuchan `SnArmourEquipEvent` sin registrar fuentes propias. Todo el flujo corre en el main thread (los eventos fuente de Bukkit/Paper son sincronicos); las bases exponen ademas constructores `async` para subclases que lo necesiten.

### SnEvent
`src/main/java/com/sn/lib/event/SnEvent.java`

Base abstracta auto-disparable y cancelable para eventos custom de la libreria. Extiende `org.bukkit.event.Event` e implementa `Cancellable`. Las subclases concretas se disparan a si mismas via `call()` y deben proveer igualmente el par de handler-list de Bukkit: el metodo de instancia `getHandlers()` mas el estatico `getHandlerList()` (la base no los provee).

- `protected SnEvent()` - constructor sincronico (evento de main thread, el default de Bukkit).
- `protected SnEvent(boolean async)` - constructor que delega en `Event(boolean)` para marcar el evento como asincronico cuando la subclase lo requiere.
- `public boolean call()` - despacha el evento via `Bukkit.getPluginManager().callEvent(this)` y devuelve `!isCancelled()`: `true` si el evento "sobrevivio" (nadie lo cancelo), `false` si algun listener lo cancelo.
- `public boolean isCancelled()` - devuelve el flag de cancelacion interno.
- `public void setCancelled(boolean cancelled)` - setea el flag de cancelacion interno.

### SnPlayerEvent
`src/main/java/com/sn/lib/event/SnPlayerEvent.java`

Base abstracta gemela de `SnEvent` para eventos que siempre llevan un jugador: extiende `org.bukkit.event.player.PlayerEvent` (hereda `getPlayer()`) e implementa `Cancellable`. Mismo contrato que `SnEvent`: las subclases se disparan via `call()` y deben proveer el par de handler-list.

- `protected SnPlayerEvent(Player who)` - constructor sincronico con el jugador portador.
- `protected SnPlayerEvent(Player who, boolean async)` - variante que permite marcar el evento como asincronico.
- `public boolean call()` - despacha via `Bukkit.getPluginManager().callEvent(this)` y devuelve `!isCancelled()`.
- `public boolean isCancelled()` - devuelve el flag de cancelacion interno.
- `public void setCancelled(boolean cancelled)` - setea el flag de cancelacion interno.

### SnArmourEquipEvent
`src/main/java/com/sn/lib/event/SnArmourEquipEvent.java`

Evento `final` que se dispara cuando un jugador equipa o desequipa una pieza de armadura por cualquier vector. Lo sintetiza el listener compartido de la libreria (`ArmourEquipListener`); ningun plugin consumidor lo construye a mano en el flujo normal. La cancelacion es vinculante SOLO cuando la fuente subyacente es cancelable (`EquipMethod.DISPENSER`, donde cancelar el SnArmourEquipEvent cancela el `BlockDispenseArmorEvent`); los eventos que reportan un cambio ya aplicado (fuente primaria `PlayerArmorChangeEvent` y `EquipMethod.DEATH`) exponen la cancelacion solo como señal a nivel de consumidores (un listener puede marcarlo cancelado para que otros lo ignoren, pero el cambio de armadura no se revierte).

- `public SnArmourEquipEvent(Player player, EquipMethod method, EquipmentSlot slot, @Nullable ItemStack oldPiece, @Nullable ItemStack newPiece)` - construye el evento: jugador cuya armadura cambio, vector de entrada, slot afectado, pieza que sale del slot (o `null` si el slot estaba vacio) y pieza que entra (o `null` si el slot se vacia).
- `public EquipMethod getMethod()` - vector de entrada del cambio.
- `public EquipmentSlot getSlot()` - slot de armadura afectado.
- `public @Nullable ItemStack getOldPiece()` - pieza que sale del slot, o `null` si el slot estaba vacio.
- `public void setOldPiece(@Nullable ItemStack oldPiece)` - reemplaza la pieza saliente reportada (mutador expuesto a listeners; no altera el inventario real).
- `public @Nullable ItemStack getNewPiece()` - pieza que entra al slot, o `null` si el slot se vacia.
- `public void setNewPiece(@Nullable ItemStack newPiece)` - reemplaza la pieza entrante reportada (mutador expuesto a listeners; no altera el inventario real).
- `public HandlerList getHandlers()` - devuelve la `HandlerList` estatica compartida de la clase.
- `public static HandlerList getHandlerList()` - par estatico requerido por Bukkit; misma instancia que `getHandlers()`.

Nota: el contrato de `normalize` en el listener garantiza que `oldPiece`/`newPiece` nunca llegan como aire; el aire se normaliza a `null`, y un evento con ambos `null` directamente no se dispara.

### EquipMethod
`src/main/java/com/sn/lib/event/EquipMethod.java`

Enum publico con el vector de entrada por el cual una pieza de armadura fue equipada o desequipada. Constantes (8):

- `SHIFT_CLICK` - shift-click de la pieza entre el inventario y su slot de armadura.
- `DRAG` - arrastre de la pieza al slot de armadura dentro del inventario.
- `PICK_DROP` - pick-up con el cursor y drop dentro/fuera del slot de armadura. Es tambien el vector manual generico reportado cuando la fuente sintetizada no expone el input exacto.
- `HOTBAR` - equip con click derecho de la pieza en mano, sin abrir el inventario.
- `HOTBAR_SWAP` - swap por tecla numerica mientras se hace hover sobre el slot de armadura.
- `DISPENSER` - auto-equip por un dispenser.
- `BROKE` - la pieza se rompio al agotar su durabilidad.
- `DEATH` - la pieza dejo su slot porque el jugador murio.

Sintetizables HOY vs constantes de API:

| EquipMethod | Sintetizable hoy | Fuente real |
|---|---|---|
| `PICK_DROP` | Si | `PlayerArmorChangeEvent` (vector manual generico best-effort: la fuente primaria no expone el input exacto) |
| `BROKE` | Si | `PlayerArmorChangeEvent` cuando `newPiece == null` y la pieza saliente agoto su durabilidad (`Damageable.getDamage() >= getMaxDurability()`) |
| `DISPENSER` | Si | `BlockDispenseArmorEvent` (fuente dedicada, cancelacion vinculante) |
| `DEATH` | Si | `PlayerDeathEvent` (una emision por pieza equipada, salvo keepInventory) |
| `SHIFT_CLICK` | No | Constante de API: ninguna fuente actual la emite |
| `DRAG` | No | Constante de API: ninguna fuente actual la emite |
| `HOTBAR` | No | Constante de API: ninguna fuente actual la emite |
| `HOTBAR_SWAP` | No | Constante de API: ninguna fuente actual la emite |

Los cuatro vectores manuales finos (`SHIFT_CLICK`, `DRAG`, `HOTBAR`, `HOTBAR_SWAP`) existen en la API para que los consumidores puedan hacer switch exhaustivo y para una futura sintesis mas fina, pero hoy todo cambio manual llega colapsado en `PICK_DROP` (o `BROKE`).

### ArmourEquipListener (internal)
`src/main/java/com/sn/lib/event/internal/ArmourEquipListener.java`

Listener compartido `final`, propiedad de SnLib, que sintetiza `SnArmourEquipEvent` desde tres fuentes reales. Se inscribe en el `ListenerHub` y el `registerEvents` sucede UNICAMENTE en el bootstrap de `SnLibPlugin`: hay una sola instancia para todo el server, sin estado por consumidor.

Fuente primaria: `com.destroystokyo.paper.event.player.PlayerArmorChangeEvent`. Ese evento es `@ApiStatus.Obsolete` (~1.21.4) pero NO fue removido: esta presente y funcional en todo el rango 1.20.4-1.21.8+ (SnGens lo usa en produccion). Su uso aca es DELIBERADO; el Javadoc indica migrar a `io.papermc.paper.event.entity.EntityEquipmentChangedEvent` SOLO cuando suba el piso/baseline de versiones (esa clase no existe ni en 1.21.1 ni en 1.20.4).

Tipos y estado interno:

- `private record DispenseMark(EquipmentSlot slot, int tick)` - marca de dedupe de dispenser: slot que un dispenser equipo y el tick en que ocurrio.
- `private final Map<UUID, DispenseMark> dispensed = new ConcurrentHashMap<>()` - estado de dedupe transitorio, acotado por jugadores online; no es data por consumidor.

Metodos:

- `@EventHandler(priority = EventPriority.MONITOR) public void onArmorChange(PlayerArmorChangeEvent event)` - fuente primaria (best-effort). Ignora jugadores muertos (`player.isDead()`: DEATH se sintetiza desde `PlayerDeathEvent`), resuelve el slot con `slotOf` (si da `null`, no emite), descarta el evento si hay una `DispenseMark` vigente para ese slot (ya se reporto como DISPENSER), normaliza old/new (aire -> `null`; si ambos quedan `null` no emite) y dispara `SnArmourEquipEvent` con el metodo que devuelve `classify` (`BROKE` o `PICK_DROP`). Corre en prioridad MONITOR: observa el cambio ya decidido, por eso su cancelacion no es vinculante.
- `@EventHandler(ignoreCancelled = true) public void onDispense(BlockDispenseArmorEvent event)` - fuente dedicada de `DISPENSER`. Solo actua si el target es `Player`; resuelve el slot con `matchType(event.getItem())` (si da `null`, no emite); lee `oldPiece` del inventario del jugador en ese slot (el equip todavia no se aplico). Dispara el `SnArmourEquipEvent` y, si algun listener lo cancela (`!equip.call()`), cancela el `BlockDispenseArmorEvent`: es el UNICO vector con cancelacion vinculante. Si sobrevive, registra `DispenseMark(slot, Bukkit.getCurrentTick())` para dedupear el `PlayerArmorChangeEvent` que Paper disparara a continuacion por el mismo cambio.
- `@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onDeath(PlayerDeathEvent event)` - fuente dedicada de `DEATH`. Si el evento tiene `getKeepInventory()` activo no emite nada; si no, recorre HEAD/CHEST/LEGS/FEET del inventario del jugador y delega en `fireDeath` una emision por pieza presente.
- `private static void fireDeath(Player player, EquipmentSlot slot, @Nullable ItemStack piece)` - normaliza la pieza y, si no es `null`, dispara `SnArmourEquipEvent(player, DEATH, slot, oldPiece, null).call()` (informativo: la cancelacion no revierte nada).
- `static @Nullable EquipmentSlot matchType(@Nullable ItemStack item)` - (package-private, testeable) mapea un item a su slot de armadura por sufijo del nombre de `Material` (`_HELMET`/`_HEAD`/`_SKULL`/`CARVED_PUMPKIN` -> HEAD; `_CHESTPLATE`/`ELYTRA` -> CHEST; `_LEGGINGS` -> LEGS; `_BOOTS` -> FEET). `Material` se trata como enum abierto: chequeos por nombre, nunca switch/EnumSet sobre sus constantes; un tipo desconocido devuelve `null` en vez de fallar.
- `private static @Nullable EquipmentSlot slotOf(PlayerArmorChangeEvent event)` - mapea `event.getSlotType().name()` (HEAD/CHEST/LEGS/FEET) a `EquipmentSlot` por nombre, manteniendo abierto el enum de slot de la fuente: ante un valor desconocido cae a `matchType` sobre el item nuevo y luego el viejo; si nada matchea devuelve `null` y el evento se descarta en silencio.
- `private static EquipMethod classify(@Nullable ItemStack oldPiece, @Nullable ItemStack newPiece)` - `BROKE` si la pieza salio (`newPiece == null`) y `broke(oldPiece)` es true; en cualquier otro caso `PICK_DROP` (vector manual generico).
- `private static boolean broke(@Nullable ItemStack oldPiece)` - true si el `Material` tiene `getMaxDurability() > 0` y el meta es `Damageable` con `getDamage() >= max` (la pieza agoto su durabilidad).
- `private boolean consumeDispenseMark(UUID uuid, EquipmentSlot slot)` - dedupe por tick: si no hay marca devuelve `false`; si la marca tiene mas de 1 tick de antiguedad (`Bukkit.getCurrentTick() - mark.tick() > 1`) la remueve (con `remove(uuid, mark)` condicional) y devuelve `false` (expirada); si el slot no coincide devuelve `false` sin consumirla; si coincide dentro de la ventana, la consume (remueve) y devuelve `true` (el cambio ya fue reportado como DISPENSER).
- `private static @Nullable ItemStack normalize(@Nullable ItemStack item)` - normaliza `null` y aire (`getType().isAir()`) a `null`; cualquier otra pieza pasa tal cual.

#### Logica interna

- Dedupe por tick (dispenser vs fuente primaria): cuando un dispenser equipa una pieza, Paper dispara `BlockDispenseArmorEvent` y despues `PlayerArmorChangeEvent` por el mismo cambio. `onDispense` emite primero el `SnArmourEquipEvent` con `DISPENSER` y deja una `DispenseMark(slot, tick)`; `onArmorChange` consulta `consumeDispenseMark` y, si la marca es del mismo slot y tiene a lo sumo 1 tick de antiguedad, suprime la emision duplicada. La ventana de tolerancia de 1 tick cubre el caso de que el `PlayerArmorChangeEvent` llegue al tick siguiente. Una marca vieja se limpia lazy en la proxima consulta.
- El mapa `dispensed` es `ConcurrentHashMap` aunque todos los handlers corren en el main thread: es defensa barata para estado transitorio compartido acotado por jugadores online.
- DEATH nunca sale de la fuente primaria: `onArmorChange` corta con `player.isDead()` justamente para que las piezas que se vacian al morir se reporten solo desde `PlayerDeathEvent`, una emision por pieza y con `newPiece = null`.

#### Notas y gotchas

- Nota de obsolescencia: `PlayerArmorChangeEvent` esta marcado `@ApiStatus.Obsolete` (~1.21.4) pero NO removido; funciona en todo el rango 1.20.4-1.21.8+ soportado y su uso es una decision deliberada documentada en el Javadoc. La migracion a `EntityEquipmentChangedEvent` queda explicitamente diferida hasta que el baseline de versiones lo permita (esa clase no existe en 1.20.4 ni en 1.21.1).
- La fuente primaria NO expone el vector de entrada: todo cambio manual se reporta como `PICK_DROP` (o `BROKE` cuando la pieza agoto la durabilidad). `SHIFT_CLICK`, `DRAG`, `HOTBAR` y `HOTBAR_SWAP` hoy son constantes de API sin fuente que las emita.
- Semantica de cancelacion asimetrica: cancelar el evento solo tiene efecto real con `DISPENSER` (cancela el dispense fisico). Con `PICK_DROP`/`BROKE`/`DEATH` el cambio ya ocurrio; la cancelacion es solo una señal entre consumidores.
- Enums de terceros tratados como abiertos: tanto `Material` (en `matchType`) como el slot type de `PlayerArmorChangeEvent` (en `slotOf`) se resuelven por nombre con fallback, para que una constante nueva de Paper nunca rompa el listener; lo desconocido se ignora en silencio.
- `onDeath` respeta `keepInventory`: si el inventario se conserva, no se emite ningun `DEATH`.
- Los setters `setOldPiece`/`setNewPiece` de `SnArmourEquipEvent` solo mutan lo que ven los listeners posteriores; no escriben en el inventario.
- Este modulo no loguea nada: no hay WARNs ni mensajes propios.

#### TODOs y limitaciones

No hay marcadores TODO/FIXME literales en los archivos del modulo. Limitaciones documentadas en codigo/Javadoc:

- Los vectores manuales finos (`SHIFT_CLICK`, `DRAG`, `HOTBAR`, `HOTBAR_SWAP`) no son sintetizables hoy: la fuente primaria colapsa todo en `PICK_DROP`/`BROKE`.
- Migracion pendiente (diferida a proposito) de `PlayerArmorChangeEvent` a `io.papermc.paper.event.entity.EntityEquipmentChangedEvent` cuando el baseline de versiones soportadas suba por encima de 1.21.4.
- La cancelacion no es vinculante para los vectores ya aplicados (`PICK_DROP`, `BROKE`, `DEATH`); solo `DISPENSER` puede revertirse.

---

## 11. Items

Modulo de items fisicos de SnLib, accesible via `sn.items()`. Cubre la spec dorada completa (`docs/item-example.yml`): apariencia (SnItem, builder fluido con probes de compatibilidad 1.20.4+), definicion de comportamiento (ItemDef, builder universal 100% programatico o parseado de YML), registro por contexto (ItemRegistry, con tag PDC `snlib_item_id` namespaceado por plugin owner), serializacion binaria que sobrevive amounts over-stacked (ItemSerializer) y el modo de obtencion (ObtainMode). La ejecucion en runtime la hacen las clases de `internal/`: dos listeners compartidos de propiedades e interacciones, el enforcement de modo locked con 7 vectores, durabilidad custom por PDC, backup write-through del equipamiento desplazado, timer de held-effects y loader de las 7 variantes de recetas. Los listeners compartidos son propiedad de SnLib (registrados UNA sola vez en el bootstrap de SnLibPlugin via ListenerHub) y resuelven el owner de cada stack por el namespace de su key PDC.

### SnItem
`src/main/java/com/sn/lib/item/SnItem.java`

Builder fluido de stacks fisicos que cubre toda la seccion de apariencia de la spec dorada. Los strings (name, lore) pasan por el pipeline de texto de SnLib (`[rgb]`, `[center]`, codigos legacy, MiniMessage) y se renderizan no-italicos salvo que el input pida italica. Materiales, encantamientos, efectos de pocion y trims se resuelven de forma leniente via Registry/NamespacedKey con fallback a nombres legacy; un id irresoluble loguea UN WARN (dedup por `Set` estatico concurrente `WARNED`) y se saltea, nunca lanza excepcion.

- `public static SnItem builder(Material material)` - Arranca el builder; material null cae a `STONE`.
- `public static SnItem fromConfig(SnYml yml, @Nullable Player viewer, Ph... phs)` - Lee todos los campos de apariencia desde la raiz del yml; delega en la sobrecarga con path.
- `public static SnItem fromConfig(SnYml yml, @Nullable String path, @Nullable Player viewer, Ph... phs)` - Mapea cada campo de apariencia de la spec bajo `path`: display-name, material (con la convencion de cabezas `texture-`/`basehead-`/`base64-` detectada via `HeadUtil.extractTextureValue`, que fuerza `PLAYER_HEAD`), custom-model-data (solo si esta seteado), amount, glow, lore, enchantments, flags, color, trim-pattern/trim-material, potion-effects, unbreakable, max-stack-size y equipment-slot. Los strings resuelven con `viewer` mas los placeholders locales `phs`.
- `public SnItem name(String name)` - Display name; renderizado por el pipeline de texto, no-italico salvo pedido.
- `public SnItem lore(List<String> lines)` - Agrega lineas de lore (null se convierte en ""); cada una pasa por el pipeline de texto.
- `public SnItem lore(String... lines)` - Conveniencia varargs de la anterior.
- `public SnItem amount(int amount)` - Cantidad del stack, con piso 1 al construir.
- `public SnItem glow()` - Brillo de encantamiento. Usa `setEnchantmentGlintOverride` si existe (1.20.5+, via `SnCompat.probe`); en 1.20.4 degrada a un encantamiento vanilla real (`LURE` nivel 1, solo si no hay encantamientos) mas `HIDE_ENCHANTS`, con UN WARN.
- `public SnItem enchant(String id, int level)` - Agrega un encantamiento por id leniente (key de Registry o nombre legacy de Bukkit).
- `public SnItem flags(List<String> names)` - Agrega ItemFlags por nombre. `HIDE_ALL` expande a `ItemFlag.values()` de este server; un nombre desconocido intenta el alias `HIDE_POTION_EFFECTS`/`HIDE_ADDITIONAL_TOOLTIP` (bidireccional) antes de UN WARN.
- `public SnItem hideAllTooltipFlags()` - Agrega cada flag de ocultado de tooltip conocido por este server (array `TOOLTIP_FLAGS` de 10 nombres, resueltos uno por uno con `valueOf` en try/catch; los ausentes se saltean sin WARN).
- `public SnItem color(String color)` - Tinte para metas con color (armadura de cuero, pociones). Acepta `"R, G, B"` y hex `"RRGGBB"`/`"#RRGGBB"`; un material sin soporte de tintado o un color mal formado logue UN WARN y se ignora.
- `public SnItem trim(String pattern, String material)` - Trim de armadura; los dos valores deben venir juntos, `NONE` o vacio desactiva. El lookup prefiere `RegistryAccess` (RegistryKey.TRIM_PATTERN/TRIM_MATERIAL, solo si `SnVersion.supports(20, 6)`) con los campos legacy `Registry.TRIM_*` (deprecados desde 1.20.6) como fallback leniente; un meta que no es `ArmorMeta` o un trim invalido WARNea una vez y se ignora.
- `public SnItem potionEffects(List<String> effects)` - Efectos de pocion custom para items con `PotionMeta`. Forma plana de la spec `[effect-id, level, duration]`; level default 1 (el amplifier es `level - 1`) y duration default 200 ticks.
- `public SnItem modelData(int modelData)` - Custom model data; solo estampa el meta cuando fue seteado explicitamente.
- `public SnItem headBase64(String value)` - Textura de cabeza aceptada por `HeadUtil.extractTextureValue`; requiere `PLAYER_HEAD` (otro material WARNea y se ignora).
- `public SnItem unbreakable(boolean unbreakable)` - Flag vanilla unbreakable.
- `public SnItem maxStackSize(int maxStackSize)` - Max stack size via probe de `setMaxStackSize` (1.20.5+); en 1.20.4 el valor se omite con UN WARN (que ya emite el probe). El valor se clampa a 1..99 al aplicar.
- `public SnItem equipmentSlot(String slot)` - Slot declarado de la spec (MAINHAND, OFFHAND, HEAD, CHEST, LEGS, FEET). Validado lenientemente en `build()` con UN WARN ante typos; el stack en si no se altera, el enforcement pertenece a la capa de definicion.
- `public ItemStack build()` - Construye el stack aplicando cada campo configurado con degradacion leniente; si el meta es null (materiales sin meta) devuelve el stack pelado.
- `public static @Nullable EquipmentSlot parseEquipmentSlot(String raw)` - Nombre de spec a `EquipmentSlot` leniente: `MAINHAND` mapea a `HAND` y `OFFHAND` a `OFF_HAND`; nombre desconocido devuelve null.

#### Logica interna
- `readEnchantments` y `applyPotionEffects` caminan la forma plana `[id, nivel, ...]` de la spec: `tokenize` parte cada entrada por espacios/comas/punto-y-coma, asi la forma plana y la inline parsean igual. Un numero sin id previo WARNea con el formato esperado y se ignora.
- `resolveMaterial`: `Material.matchMaterial` primero, despues `Registry.MATERIAL` por NamespacedKey; invalido WARNea y usa `STONE`.
- `resolveEnchant`/`resolveEffect`: Registry por key y fallback `getByName` legacy (deprecado a proposito, resuelve nombres tipo `FAST_DIGGING`).
- `ItemFlag` se trata como enum abierto: `valueOf` individual en try/catch, nunca switch/EnumSet, para tolerar ramas de version distintas.
- `warnOnce(tag, message)` dedupica los WARN en el set estatico `WARNED` (prefijo `[SnLib] ` via `Bukkit.getLogger()`). Estatico server-wide permitido por el contrato de SnLib: registra hechos de los registries de ESTE server, no de un consumidor.

#### Notas y gotchas
- El campo `TOOLTIP_FLAGS` es privado; incluye nombres de varias ramas de version (`HIDE_POTION_EFFECTS` y `HIDE_ADDITIONAL_TOOLTIP` a la vez) porque no todos existen en todos los servers.
- `glow()` sobre un item ya encantado en 1.20.4 no agrega `LURE` (solo si `!meta.hasEnchants()`).

### ItemDef
`src/main/java/com/sn/lib/item/ItemDef.java`

Definicion inmutable de un item fisico que cubre la spec dorada completa: apariencia, propiedades de comportamiento (droppable, moveable, placeable, tradeable, despawnable, keep-on-death, cooldown), campos de modo locked (locked, no-drop, no-manual-equip, obtain-via), durabilidad custom, las 8 listas de acciones de interaccion con sus callbacks Java, requirements de interaccion con deny-actions, acciones de pickup/drop, held effects, equipment slot y receta. `builder()` es un constructor universal de primera clase: todo campo es seteable programaticamente sin archivo YML. Las definiciones respaldadas por YML re-leen su seccion de apariencia en cada `ItemRegistry.create`, asi los placeholders resuelven por viewer. `max-stack-size` pertenece a la capa de apariencia (`SnItem.maxStackSize`); no se duplica aca. Los interact-requirements se parsean UNA vez en construccion a un arbol `Requirement` inmutable via `RequirementEngine.parse`.

- `public static Builder builder()` - Arranca el builder programatico universal; no requiere YML.
- `static @Nullable ItemDef fromYml(SnYml yml, String path, Consumer<String> warn)` - (package-private) Parsea la definicion completa de la seccion en `path`; los warnings van a `warn`. Devuelve null si la seccion no existe (con WARN "item ignorado"). Defaults: droppable/moveable/placeable/tradeable/despawnable true, keep-on-death false, cooldown 0, locked/no-drop/no-manual-equip false, obtain-via "".
- `ItemStack buildStack(@Nullable Player viewer, Ph... phs)` - (package-private) Construye el stack fisico sin el tag de id: definicion YML re-lee su seccion de apariencia con viewer y phs; definicion programatica renderiza su `SnItem` capturado o clona su template; sin nada de eso devuelve `new ItemStack(Material.STONE)`.
- `public boolean droppable()` - Si el jugador puede dropear el item.
- `public boolean moveable()` - Si el item puede moverse en inventarios.
- `public boolean placeable()` - Si el item puede colocarse como bloque.
- `public boolean tradeable()` - Si el item puede tradearse con aldeanos.
- `public boolean despawnable()` - Si el item despawnea al quedar en el suelo.
- `public boolean keepOnDeath()` - Si el item se conserva al morir y se devuelve al respawnear.
- `public int cooldownTicks()` - Cooldown entre interacciones en ticks; 0 lo desactiva (piso 0 en construccion).
- `public boolean locked()` - Si el item esta clavado a su slot (modo locked).
- `public boolean noDrop()` - Alias duro de `droppable: false`; bloquea drops y arrastres afuera.
- `public boolean noManualEquip()` - Si el equipado manual a slots de armadura u offhand esta bloqueado.
- `public ObtainMode obtainVia()` - Como puede entrar legitimamente en circulacion.
- `public int durabilityMax()` - Maximo de durabilidad custom; 0 desactiva el sistema (piso 0).
- `public int durabilityDamagePerUse()` - Durabilidad perdida por uso; piso 1.
- `public String durabilityLoreFormat()` - Formato de la linea de lore con `%durability%`/`%max_durability%`; vacio la oculta.
- `public List<String> breakActions()` - Acciones que corren cuando la durabilidad custom llega a 0.
- `public List<String> rightClickActions()` - Acciones de click derecho.
- `public List<String> leftClickActions()` - Acciones de click izquierdo.
- `public List<String> shiftRightClickActions()` - Acciones de shift + click derecho.
- `public List<String> shiftLeftClickActions()` - Acciones de shift + click izquierdo.
- `public List<String> rightClickBlockActions()` - Acciones de click derecho sobre bloque.
- `public List<String> rightClickAirActions()` - Acciones de click derecho al aire.
- `public List<String> leftClickBlockActions()` - Acciones de click izquierdo sobre bloque.
- `public List<String> leftClickAirActions()` - Acciones de click izquierdo al aire.
- `public List<String> interactRequirements()` - Lineas crudas de requirements tal como se declararon.
- `public Requirement interactRequirement()` - Arbol de requirements parseado una vez; nunca null.
- `public List<String> denyActions()` - Acciones que corren cuando los requirements no se cumplen.
- `public List<String> pickupActions()` - Acciones al recoger el item.
- `public List<String> dropActions()` - Acciones al dropear el item.
- `public List<String> heldEffectsMainhand()` - Lineas de efecto (`"EFFECT amplifier"`) aplicadas con el item en mano principal.
- `public List<String> heldEffectsOffhand()` - Lineas de efecto aplicadas con el item en offhand.
- `public List<String> heldEffectsArmor()` - Lineas de efecto aplicadas con el item puesto como armadura.
- `public String equipmentSlotName()` - Nombre de slot declarado en la spec; vacio permite cualquier slot.
- `public @Nullable EquipmentSlot equipmentSlot()` - Slot parseado, o null cuando no hay restriccion o el nombre era invalido.
- `public @Nullable Recipe recipe()` - Receta de crafteo del item, o null.
- `public @Nullable BiConsumer<Player, ItemStack> onRightClick()` - Callback Java de click derecho, o null.
- `public @Nullable BiConsumer<Player, ItemStack> onLeftClick()` - Callback Java de click izquierdo, o null.
- `public @Nullable BiConsumer<Player, ItemStack> onShiftRightClick()` - Callback Java de shift + click derecho, o null.
- `public @Nullable BiConsumer<Player, ItemStack> onShiftLeftClick()` - Callback Java de shift + click izquierdo, o null.
- `public @Nullable BiConsumer<Player, ItemStack> onRightClickBlock()` - Callback Java de click derecho sobre bloque, o null.
- `public @Nullable BiConsumer<Player, ItemStack> onRightClickAir()` - Callback Java de click derecho al aire, o null.
- `public @Nullable BiConsumer<Player, ItemStack> onLeftClickBlock()` - Callback Java de click izquierdo sobre bloque, o null.
- `public @Nullable BiConsumer<Player, ItemStack> onLeftClickAir()` - Callback Java de click izquierdo al aire, o null.
- `public @Nullable BiConsumer<Player, ItemStack> onApply()` - Hook Java que corre despues de que `ItemRegistry.apply` inyecta el item, o null.
- `public @Nullable BiConsumer<Player, ItemStack> onRemove()` - Hook Java que corre despues de que `ItemRegistry.unapply` remueve el item, o null.

#### ItemDef.Builder (clase interna publica)

Builder universal: cada campo de la spec es seteable programaticamente. La apariencia viene de un `SnItem` capturado (renderizado en cada create) o de un `ItemStack` template fijo (clonado en cada create); setear uno anula el otro.

- `public Builder item(SnItem item)` - Apariencia desde un builder SnItem, renderizado fresco en cada create.
- `public Builder item(ItemStack stack)` - Apariencia desde un stack fijo, clonado en cada create.
- `public Builder droppable(boolean droppable)` - Default true.
- `public Builder moveable(boolean moveable)` - Default true.
- `public Builder placeable(boolean placeable)` - Default true.
- `public Builder tradeable(boolean tradeable)` - Default true.
- `public Builder despawnable(boolean despawnable)` - Default true.
- `public Builder keepOnDeath(boolean keepOnDeath)` - Default false.
- `public Builder keepOnDeath()` - Atajo de `keepOnDeath(true)`.
- `public Builder locked()` - Clava el item a su slot: ninguno de los 7 vectores de extraccion (click, drag, equip manual, hand swap, drop, drops de muerte, movimiento por hopper) puede sacarlo. Los stacks creados llevan el flag PDC `snlib_locked`.
- `public Builder locked(boolean locked)` - Version parametrizada; default false.
- `public Builder noDrop()` - Bloquea dropear el item (alias duro de `droppable: false`). Los stacks creados llevan el flag PDC `snlib_no_drop`.
- `public Builder noDrop(boolean noDrop)` - Version parametrizada; default false.
- `public Builder noManualEquip()` - Bloquea el equipado manual a slots de armadura. Los stacks creados llevan el flag PDC `snlib_no_manual_equip`.
- `public Builder noManualEquip(boolean noManualEquip)` - Version parametrizada; default false.
- `public Builder obtainVia(ObtainMode mode)` - Como puede entrar en circulacion; default `UNRESTRICTED` (null tambien cae ahi). Los stacks restringidos llevan la key PDC `snlib_obtain_via`.
- `public Builder cooldownTicks(int cooldownTicks)` - Cooldown entre interacciones en ticks; default 0 (desactivado).
- `public Builder customDurability(int max, int damagePerUse, String loreFormat, List<String> breakActions)` - Durabilidad custom separada de la vanilla: `max` 0 la desactiva, `loreFormat` renderiza `%durability%`/`%max_durability%` y `breakActions` corre al llegar a 0.
- `public Builder rightClickActions(List<String> actions)` - Acciones de click derecho.
- `public Builder leftClickActions(List<String> actions)` - Acciones de click izquierdo.
- `public Builder shiftRightClickActions(List<String> actions)` - Acciones de shift + click derecho.
- `public Builder shiftLeftClickActions(List<String> actions)` - Acciones de shift + click izquierdo.
- `public Builder rightClickBlockActions(List<String> actions)` - Acciones de click derecho sobre bloque.
- `public Builder rightClickAirActions(List<String> actions)` - Acciones de click derecho al aire.
- `public Builder leftClickBlockActions(List<String> actions)` - Acciones de click izquierdo sobre bloque.
- `public Builder leftClickAirActions(List<String> actions)` - Acciones de click izquierdo al aire.
- `public Builder interactRequirements(List<String> requirements)` - Expresiones de requirement chequeadas antes de correr cualquier accion de interaccion.
- `public Builder denyActions(List<String> actions)` - Acciones cuando los requirements no se cumplen.
- `public Builder pickupActions(List<String> actions)` - Acciones al recoger el item.
- `public Builder dropActions(List<String> actions)` - Acciones al dropear el item.
- `public Builder heldEffectsMainhand(List<String> effects)` - Lineas de efecto (`"EFFECT amplifier"`) en mano principal.
- `public Builder heldEffectsOffhand(List<String> effects)` - Lineas de efecto en offhand.
- `public Builder heldEffectsArmor(List<String> effects)` - Lineas de efecto como armadura.
- `public Builder equipmentSlot(String slotName)` - Restriccion de slot (MAINHAND, OFFHAND, HEAD, CHEST, LEGS, FEET).
- `public Builder recipe(Recipe recipe)` - Receta de crafteo del item.
- `public Builder onRightClick(BiConsumer<Player, ItemStack> callback)` - Callback Java de click derecho, corre junto a la lista de acciones YML.
- `public Builder onLeftClick(BiConsumer<Player, ItemStack> callback)` - Callback Java de click izquierdo.
- `public Builder onShiftRightClick(BiConsumer<Player, ItemStack> callback)` - Callback Java de shift + click derecho.
- `public Builder onShiftLeftClick(BiConsumer<Player, ItemStack> callback)` - Callback Java de shift + click izquierdo.
- `public Builder onRightClickBlock(BiConsumer<Player, ItemStack> callback)` - Callback Java de click derecho sobre bloque.
- `public Builder onRightClickAir(BiConsumer<Player, ItemStack> callback)` - Callback Java de click derecho al aire.
- `public Builder onLeftClickBlock(BiConsumer<Player, ItemStack> callback)` - Callback Java de click izquierdo sobre bloque.
- `public Builder onLeftClickAir(BiConsumer<Player, ItemStack> callback)` - Callback Java de click izquierdo al aire.
- `public Builder onApply(BiConsumer<Player, ItemStack> callback)` - Hook Java con el stack inyectado despues de `ItemRegistry.apply`.
- `public Builder onRemove(BiConsumer<Player, ItemStack> callback)` - Hook Java con el stack removido despues de `ItemRegistry.unapply`.
- `public ItemDef build()` - Construye la definicion inmutable.

#### ItemDef.Recipe (clase interna publica)

Declaracion inmutable de receta de la spec dorada. Los nombres de material se guardan crudos y los resuelve lenientemente la capa de carga de recetas (RecipeLoader).

- `public static Recipe shaped(List<String> shape, Map<Character, String> ingredients)` - Receta SHAPED de hasta tres filas y mapa simbolo-a-material.
- `public static Recipe shapeless(List<String> ingredients)` - Receta SHAPELESS de lista plana de materiales.
- `public static Recipe cooking(String type, String input, double experience, int cookingTimeTicks)` - Receta de coccion: `type` es FURNACE, SMOKING, BLASTING o CAMPFIRE.
- `public static Recipe stonecutting(String input)` - Receta STONECUTTING de un unico material de entrada.
- `static @Nullable Recipe fromSection(ConfigurationSection sec, Consumer<String> warn)` - (package-private) Parsea la seccion `recipe:`; tipo vacio o desconocido devuelve null (con WARN para el desconocido). SHAPED exige shape e ingredients; SHAPELESS exige ingredients; los tipos de coccion exigen input (defaults: experience 0.0, cooking-time 200); STONECUTTING exige input.
- `public String type()` - Tipo: SHAPED, SHAPELESS, FURNACE, SMOKING, BLASTING, CAMPFIRE o STONECUTTING.
- `public List<String> shape()` - Filas de shape de una SHAPED; vacia en otro caso.
- `public Map<Character, String> ingredients()` - Mapa simbolo-a-material de una SHAPED; vacio en otro caso.
- `public List<String> shapelessIngredients()` - Lista plana de materiales de una SHAPELESS; vacia en otro caso.
- `public @Nullable String input()` - Material de entrada de recetas de coccion y stonecutting, o null.
- `public double experience()` - Experiencia otorgada por recetas de coccion.
- `public int cookingTime()` - Tiempo de coccion en ticks.

#### Notas y gotchas
- El constructor privado de ItemDef clona el template (`b.template.clone()`) y copia defensivamente cada lista via `copy()` (que filtra nulls y devuelve `List.copyOf`), garantizando inmutabilidad real.
- El Javadoc de la clase incluye el checklist campo-por-campo de la spec dorada indicando donde parsea y quien ejecuta cada bloque (SnItem/ItemPropertyListener/LockedItemListener/DurabilityTracker/ItemInteractListener/RequirementEngine/HeldEffectsTask/RecipeLoader).

### ItemRegistry
`src/main/java/com/sn/lib/item/ItemRegistry.java`

Registro de definiciones de items por contexto, alcanzado via `sn.items()`. Funciona con CERO archivos: definiciones 100% programaticas via `ItemDef.builder()`, desde una seccion YML via `register(String, SnYml)`, o en bloque desde el archivo de items via `loadAll`. Los stacks creados se taggean con la key PDC namespaceada por owner `snlib_item_id` (via `TagIo`), que es como el listener compartido resuelve cualquier stack de vuelta a su contexto duenio. El constructor crea el `EquipmentBackup`, el `RecipeLoader` y el `HeldEffectsTask` del contexto y se trackea en `ItemPropertyListener.track`.

Constantes publicas:
- `public static final String TAG_KEY = "snlib_item_id"` - Key PDC con el id del item; namespaceada por plugin owner via TagIo.
- `public static final String TAG_LOCKED = "snlib_locked"` - Flag PDC de stacks de una definicion locked.
- `public static final String TAG_NO_DROP = "snlib_no_drop"` - Flag PDC de stacks no-drop.
- `public static final String TAG_NO_MANUAL_EQUIP = "snlib_no_manual_equip"` - Flag PDC de stacks no-manual-equip.
- `public static final String TAG_KEEP_ON_DEATH = "snlib_keep_on_death"` - Flag PDC de stacks keep-on-death.
- `public static final String TAG_OBTAIN_VIA = "snlib_obtain_via"` - Key PDC con el modo de obtencion de definiciones restringidas.

Metodos:
- `public ItemRegistry(Sn ctx)` - Crea el registro del contexto dado y lo trackea para la resolucion de owner.
- `public void register(String id, SnYml yml)` - Parsea y registra la definicion de la seccion top-level `id` del yml; seccion faltante loguea UN WARN y no registra nada. Re-registrar un id reemplaza la definicion previa.
- `public void register(String id, ItemDef def)` - Registra una definicion bajo `id`, reemplazando la previa. Id o def nulos WARNean y se ignoran. Una receta declarada se agrega al server bajo `snlib_recipe_<id>` (con lookup previo, asi re-registros nunca lanzan) y las lineas de held-effects arrancan el timer per-contexto de forma lazy.
- `public void loadAll(SnYml itemsFile)` - Registra cada seccion top-level de `itemsFile` como una definicion; guarda la fuente para `reload()`.
- `public void cancelTasks()` - Cancela las tareas per-contexto del modulo (el timer de held-effects); el flujo de reload las reinicia despues de re-leer las definiciones.
- `public void reload()` - Re-registra cada definicion del archivo cargado por `loadAll` (parse fresco del yml recargado) y reinicia el timer de held-effects si queda alguna definicion trackeada. Las definiciones programaticas quedan registradas intactas.
- `public void reloadRecipes()` - Ciclo de recetas del flujo de reload, SOLO main thread: remueve del server cada key de receta de este owner y re-agrega las recetas de cada definicion registrada.
- `public @Nullable ItemDef def(String id)` - Definicion registrada bajo `id`, o null.
- `public @Nullable ItemStack create(String id, @Nullable Player viewer, Ph... phs)` - Construye el stack fisico de `id`, taggeado con `snlib_item_id` namespaceado por owner, mas los flags de modo locked que declare la definicion. Los placeholders de apariencia resuelven contra `viewer` mas los locales `phs`. Definiciones con durabilidad custom salen sembradas a durabilidad completa con su linea de lore renderizada. Un id desconocido loguea UN WARN y devuelve null.
- `public boolean apply(Player player, String id, EquipmentSlot slot)` - Inyecta el item registrado bajo `id` en el slot de equipamiento del jugador (el camino comando/API de `obtain-via: COMMAND_ONLY`). El item real desplazado se respalda write-through en el equipment backup, cuya restauracion corre en quit y en shutdown. Dispara un `SnArmourEquipEvent` cancelable (`EquipMethod.PICK_DROP`, marcado programatico via `LockedItemListener.markProgrammatic`) ANTES de tocar el slot y el hook `onApply` de la definicion despues. Devuelve true cuando el item quedo equipado.
- `public boolean unapply(Player player, String id)` - Remueve cada instancia aplicada de `id` de los slots de equipamiento del jugador (los 6 de `PLAYER_SLOTS`), restaurando el item real respaldado de cada slot (null lo vacia). Dispara un `SnArmourEquipEvent` cancelable por slot y el hook `onRemove` despues de cada remocion. Devuelve true cuando al menos un slot fue restaurado.
- `public int durability(ItemStack item)` - Durabilidad custom restante del stack; un stack sin tag de un item con durabilidad cuenta como llena. Devuelve -1 cuando el stack no fue creado por este contexto o su definicion no tiene durabilidad custom.
- `public int damage(ItemStack item, int amount)` - Resta `amount` de durabilidad custom (piso 0), actualizando el tag y re-renderizando la linea de lore. Devuelve la durabilidad restante (0 = roto), o -1 si el stack no es de este contexto o no tiene durabilidad custom. Las break-actions y la remocion de la mano SOLO corren por el flujo de interaccion (que tiene al jugador usando); una rotura programatica queda a cargo del caller.
- `public @Nullable String idOf(ItemStack item)` - Id registrado del stack cuando este contexto lo creo, o null.
- `public boolean is(ItemStack item, String id)` - Si el stack es instancia del item registrado bajo `id`.
- `public void give(Player player, String id, int amount)` - Da `amount` unidades del item, partiendo en chunks de max-stack; lo que no entra se dropea a los pies del jugador (via `InvUtil.giveItems`).

#### Notas y gotchas
- `PLAYER_SLOTS` es una lista fija de los 6 slots de jugador (HAND, OFF_HAND, HEAD, CHEST, LEGS, FEET): mantiene abierto el enum fuente (no itera `EquipmentSlot.values()`, que en versiones nuevas incluye BODY/SADDLE).
- `defs` es un `ConcurrentHashMap` y `itemsSource` es volatile; el registro tolera lecturas concurrentes.
- `apply` normaliza el item desplazado (null si es aire) antes de pasarlo al evento; `unapply` usa `backup.peek` para el evento y recien `backup.take` si el evento no fue cancelado.

### ItemSerializer
`src/main/java/com/sn/lib/item/ItemSerializer.java`

Serializacion binaria de stacks que sobrevive amounts over-stacked. `ItemStack.serializeAsBytes()` clampa el amount al max stack size del material, perdiendo silenciosamente los amounts over-stacked (gotcha de SnLootBoxes). Por eso el amount real se escribe como prefijo big-endian de 4 bytes y el cuerpo se serializa con amount 1, asi `deserialize` restaura el amount original exacto. Clase final con constructor privado (solo estaticos).

- `public static byte[] serialize(ItemStack stack)` - Serializa a bytes: prefijo de amount de 4 bytes mas la forma byte de Paper de la copia con amount 1. Lanza `IllegalArgumentException` con stacks null o AIR, que no tienen forma byte.
- `public static ItemStack deserialize(byte[] data)` - Restaura un stack de la salida de `serialize`, reaplicando el amount real aun cuando excede el max stack size del material (piso 1). Lanza `IllegalArgumentException` si data es null o tiene 4 bytes o menos.
- `public static String serializeBase64(ItemStack stack)` - Forma Base64 de `serialize`, para almacenamiento en texto (yml, columnas de base de datos).
- `public static ItemStack deserializeBase64(String data)` - Inversa de `serializeBase64`; lanza `IllegalArgumentException` con data null o en blanco.

### ObtainMode
`src/main/java/com/sn/lib/item/ObtainMode.java`

Enum de como un item registrado puede entrar legitimamente en circulacion (campo de spec `obtain-via`).

Valores:
- `UNRESTRICTED` - Sin restriccion; todo camino de adquisicion permitido. Default de la spec (`""`).
- `COMMAND_ONLY` - Solo via comando o API del plugin; los demas caminos (crafteo, pickup de mobs y similares) los cancela la capa de enforcement de items locked.

Metodos:
- `static ObtainMode parse(@Nullable String raw, @Nullable Consumer<String> warn)` - (package-private) Parse leniente: null o blank da `UNRESTRICTED`; normaliza mayusculas y `-` a `_`; un valor desconocido manda UN warning a `warn` y tambien da `UNRESTRICTED`.

### ItemPropertyListener (internal)
`src/main/java/com/sn/lib/item/internal/ItemPropertyListener.java`

Listener unico compartido, propiedad de SnLib, que enforcea las propiedades de comportamiento de los items registrados (droppable/no-drop, moveable, placeable, tradeable, despawnable, keep-on-death), la restriccion best-effort de `equipment-slot` (click directo a slot incompatible, auto-equip por shift-click y equips por dispenser; el vector de auto-equip por click derecho vive en el interact listener) y corre las listas de acciones de pickup/drop. Inscripto en el ListenerHub; el `registerEvents` ocurre UNICAMENTE en el bootstrap de SnLibPlugin. La resolucion de owner es por PDC: el namespace de la key `snlib_item_id` mapea de vuelta al plugin consumidor y su `ItemRegistry` trackeado en un `TenantRegistry` multi-tenant estatico (el sweeper de tenants borra la key entera al deshabilitarse el owner). Contrato de hot-path: este listener ve CADA evento de inventario del server de todos los consumidores, asi que cada handler hace quick-exit en capas: null/air primero, despues `hasItemMeta()`, despues el tag PDC, despues la logica. `ItemSpawnEvent` filtra por `hasItemMeta()` antes que nada.

- `public static void track(JavaPlugin owner, ItemRegistry registry)` - Trackea el registro de un contexto para que los tags PDC resuelvan a su owner.
- `public void onDrop(PlayerDropItemEvent event)` - Cancela el drop si `!droppable()` o `noDrop()`; si es dropeable corre las drop-actions.
- `public void onInventoryClick(InventoryClickEvent event)` - Cancela el click si el item actual, el cursor, el hotbar (NUMBER_KEY via `getHotbarButton`, SWAP_OFFHAND via el item de offhand) o el enforcement de equipment-slot lo deniegan (no-moveable, o no-tradeable dentro del inventario MERCHANT incluyendo `MOVE_TO_OTHER_INVENTORY`).
- `public void onDispenseArmor(BlockDispenseArmorEvent event)` - Vector dispenser del enforcement de equipment-slot: cancela equips cuyo destino vanilla no coincide con el slot declarado.
- `public void onInventoryDrag(InventoryDragEvent event)` - Cancela el drag si `!moveable()`, o si `!tradeable()` y algun raw slot cae en el top de un inventario MERCHANT.
- `public void onBlockPlace(BlockPlaceEvent event)` - Cancela la colocacion si `!placeable()`.
- `public void onDeath(PlayerDeathEvent event)` - (priority HIGH) Saca de los drops los stacks keep-on-death y los guarda en el stash `keptOnDeath` por UUID; respeta `getKeepInventory()`.
- `public void onRespawn(PlayerRespawnEvent event)` - (priority MONITOR) Devuelve el stash keep-on-death al jugador via `InvUtil.giveItems`.
- `public void onItemSpawn(ItemSpawnEvent event)` - Si el item spawneado no es despawnable, setea `setUnlimitedLifetime(true)` en la entidad.
- `public void onPickup(EntityPickupItemEvent event)` - Cancela el pickup por entidades no-jugador de items registrados; para jugadores corre las pickup-actions.
- `record Match(JavaPlugin owner, ItemRegistry registry, ItemDef def, String id)` - (package-private) Item registrado detras de un stack: plugin owner, su registro, definicion e id.
- `static @Nullable Match match(@Nullable ItemStack item)` - (package-private) Resolucion con quick-exit en capas: null/air, `hasItemMeta`, tag PDC (recorre las keys buscando `snlib_item_id`), lookup en el registro del owner cuyo nombre en minusculas coincide con el namespace.
- `static @Nullable EquipmentSlot vanillaEquipSlot(@Nullable ItemStack stack)` - (package-private) Destino de auto-equip vanilla del material, matcheado por sufijo de nombre (`_HELMET`/`_HEAD`/`_SKULL`/`CARVED_PUMPKIN` -> HEAD, `_CHESTPLATE`/`ELYTRA` -> CHEST, `_LEGGINGS` -> LEGS, `_BOOTS` -> FEET); Material tratado como enum abierto, nunca switch sobre sus constantes.

#### Notas y gotchas
- El enforcement de equipment-slot en clicks solo aplica en la vista de inventario propio (`InventoryType.CRAFTING`); los raw slots 5-8 son armadura y el 45 es offhand.
- Las acciones se corren via `SnLib.context(match.owner()).actions().run(...)`: el contexto del OWNER del item, no el del server.
- El stash `keptOnDeath` esta acotado por jugadores muertos esperando respawn; no es data per-consumidor (por eso el estado de instancia esta permitido).

### ItemInteractListener (internal)
`src/main/java/com/sn/lib/item/internal/ItemInteractListener.java`

Listener unico compartido, propiedad de SnLib, que despacha las interacciones de items. Inscripto en el ListenerHub; `registerEvents` UNICAMENTE en el bootstrap de SnLibPlugin. Solo consulta `PlayerInteractEvent.getItem()` y `getHand()`, asi cada despacho pertenece al evento cuya mano lleva el item y un dual-fire (mano principal + offhand) nunca corre doble un item. Mismo contrato de hot-path con quick-exit en capas.

- `public void onInteract(PlayerInteractEvent event)` - (priority HIGH) Flujo por interaccion: (0) ignora `Action.PHYSICAL`, item null/air/sin meta, mano null, stack sin match o contexto caido; deniega el auto-equip incompatible (vector click derecho del equipment-slot); (1) el cooldown del item (categoria `"item:" + id` via `ctx.cooldowns().tryUseTicks`) retorna silenciosamente mientras enfria; (2) los interact-requirements evaluan con un resolver de locals mas PAPI, corriendo las deny-actions si no se cumplen; (3) despachan las variantes que correspondan, cada una corriendo su lista de acciones YML por el ActionEngine Y su callback Java. Un uso exitoso resta durabilidad custom despues; a 0 corren las break-actions y el stack sale de la mano que lo uso.

#### Logica interna
- `dispatch`: corre la variante generica (una variante shift CON comportamiento tiene prioridad sobre su click generico; shift sin acciones ni callback cae al generico) y despues la variante posicional block/air, que corre EN ADICION a la generica. El `ActionContext` lleva el `ClickType` calculado (RIGHT/LEFT/SHIFT_RIGHT/SHIFT_LEFT segun `player.isSneaking()`).
- `denyIncompatibleAutoEquip`: en right-click, si el destino de auto-equip vanilla del material no es el slot declarado, setea `setUseItemInHand(Event.Result.DENY)` (best-effort; las acciones de interaccion igual despachan, independiente del cooldown).
- `applyDurability`: resta `damage-per-use` via `DurabilityTracker.damage`; si queda > 0 re-setea el item en la mano usada, si llega a 0 corre las break-actions y vacia esa mano (`setItem(hand, null)`).

### DurabilityTracker (internal)
`src/main/java/com/sn/lib/item/internal/DurabilityTracker.java`

Estado de durabilidad custom de items registrados, separado del danio vanilla. La durabilidad restante vive en la key PDC namespaceada por owner `snlib_durability` (int), sembrada a `custom-durability.max` al crear el stack. Cada aplicacion de danio re-renderiza la linea `lore-format` con `%durability%`/`%max_durability%` resueltos; la posicion de la linea se recuerda en una segunda key PDC int (`snlib_durability_line`, privada) para que los re-renders reemplacen in-place. Llegar a 0 se reporta al caller: el interact listener corre las break-actions y saca el stack de la mano. Clase final de solo estaticos.

Constantes publicas:
- `public static final String DURABILITY_KEY = "snlib_durability"` - Key PDC con la durabilidad restante; namespaceada por plugin owner.

Metodos:
- `public static void initialize(JavaPlugin owner, ItemDef def, ItemStack stack)` - Siembra el tag a max completo y renderiza la linea de lore inicial. No-op si la definicion no tiene durabilidad custom o el stack ya lleva el tag.
- `public static int durability(JavaPlugin owner, ItemDef def, ItemStack stack)` - Durabilidad restante; un stack sin tag cuenta como llena. Devuelve -1 si la definicion no tiene durabilidad custom o el stack no tiene meta.
- `public static int damage(JavaPlugin owner, ItemDef def, ItemStack stack, int amount)` - Resta `amount` (piso 0), actualiza el tag y re-renderiza la linea de lore. Devuelve la restante (0 = roto), el valor actual intacto si `amount` no es positivo, o -1 sin durabilidad custom.

#### Notas y gotchas
- `renderLore` reemplaza la linea en el indice recordado solo si sigue siendo valido (`0 <= index < lore.size()`); si no, la agrega al final y recuerda la nueva posicion.
- La linea de lore se renderiza por `SnText.color(SnText.applyLocals(...))` con `Ph.of("durability", ...)` y `Ph.of("max_durability", ...)`, no-italica salvo pedido.

### HeldEffectsTask (internal)
`src/main/java/com/sn/lib/item/internal/HeldEffectsTask.java`

Timer sync per-contexto que aplica los held-effects de los items registrados. Es un TIMER, no un listener: nunca pasa por el ListenerHub. Lazy por disenio: solo arranca cuando una definicion trackeada declara al menos una linea de held-effect. Cada 40 ticks (`PERIOD_TICKS`, constante privada) revisa mano principal, offhand y armadura puesta de cada jugador online contra las definiciones de su propio contexto (id PDC namespaceado por owner, asi los contextos nunca interfieren) y aplica los `PotionEffect` que matcheen, ambient y sin particulas, con duracion 80 ticks (`DURATION_TICKS`, privada: 60 mas margen) que sobrevive al periodo de barrido, asi el efecto es continuo mientras se sostiene y expira solo al soltarlo.

- `public HeldEffectsTask(Sn ctx, ItemRegistry registry)` - Constructor; no arranca nada.
- `public void track(String id, ItemDef def)` - Trackea (o re-trackea) una definicion: parsea sus lineas de held-effect UNA vez (nunca por tick) y arranca el timer lazy con la primera definicion que tenga alguna. Una definicion sin held-effects borra cualquier tracking previo del mismo id.
- `public synchronized void stop()` - Cancela el timer; la proxima definicion trackeada con held-effects lo reinicia.
- `public synchronized void restart()` - Reinicia el timer cuando queda alguna definicion trackeada; el camino de re-track del reload.

#### Logica interna
- Las lineas tienen forma `"EFFECT amplifier"`; ids de efecto o amplificadores invalidos WARNean UNA vez (set `warned` propio del task, logger del plugin del contexto) y se saltean; el amplificador invalido usa 0.
- `apply` hace el quick-exit en capas: null/air, `hasItemMeta`, id PDC de este contexto (`registry.idOf`), efectos trackeados.
- `resolveEffect`: Registry por NamespacedKey con fallback `getByName` legacy.
- El timer se crea via `ctx.scheduler().timer(PERIOD_TICKS, PERIOD_TICKS, this::tick)` (main thread).

### RecipeLoader (internal)
`src/main/java/com/sn/lib/item/internal/RecipeLoader.java`

Loader per-contexto de las recetas declaradas por definiciones de items: SHAPED, SHAPELESS, FURNACE, SMOKING, BLASTING, CAMPFIRE y STONECUTTING (7 tipos). Cada receta registra bajo `NamespacedKey(plugin, "snlib_recipe_" + itemId)` con el plugin consumidor como owner, y SIEMPRE hace lookup de la key antes de registrar (gate `Bukkit.getRecipe(key) == null`) para que un segundo enable nunca lance. Las keys registradas se trackean en un `TenantRegistry` estatico cuyo callback de sweep remueve la receta del server (`Bukkit::removeRecipe`): el sweeper de tenants limpia las recetas de un owner al deshabilitarse, y `unregisterAll()`/`registerAll` le dan al reload manager su ciclo de unregister/re-register. Los materiales de ingredientes resuelven lenientemente con WARN.

- `public RecipeLoader(JavaPlugin plugin, ItemRegistry registry)` - Constructor con el owner y su registro.
- `public void register(String itemId, ItemDef def)` - Registra la receta declarada por `def` bajo `snlib_recipe_<itemId>`; una definicion sin receta es no-op. Si la key ya existe en el server solo la trackea (nunca re-agrega). El stack resultado es el item creado por el registro (id PDC incluido, `registry.create(itemId, null)`). Declaraciones invalidas WARNean y no registran nada; `addRecipe` rechazado o `IllegalStateException`/`IllegalArgumentException` tambien WARNean.
- `public void registerAll(Map<String, ItemDef> defs)` - Registra la receta de cada definicion; la mitad re-register del reload manager.
- `public void unregisterAll()` - Remueve del server cada key de receta de este owner.
- `public static void unregisterAll(Plugin owner)` - Remueve del server cada key de receta de `owner` (camino de teardown).

#### Logica interna
- `build` despacha por tipo: SHAPED aplica shape e ingredientes (un ingrediente invalido descarta la receta ENTERA); SHAPELESS omite ingredientes invalidos individualmente pero descarta la receta si no quedo ninguno valido; coccion mapea a `FurnaceRecipe`/`SmokingRecipe`/`BlastingRecipe`/`CampfireRecipe` con `cookingTime` piso 1; STONECUTTING crea `StonecuttingRecipe`.
- `keyFor` normaliza el id al charset de keys (`[^a-z0-9/._-]` -> `_`); un id que igual no genera key valida WARNea y se ignora.
- `resolveMaterial`: `matchMaterial` directo y despues con mayusculas y espacios/guiones normalizados a `_`.

### LockedItemListener (internal)
`src/main/java/com/sn/lib/item/internal/LockedItemListener.java`

Listener unico compartido, propiedad de SnLib, que enforcea el modo locked de items registrados (patron EdToolsArmors 2.0): una pieza locked queda clavada a su slot y no puede salir por ninguno de los siete vectores de extraccion. Tambien escucha `SnArmourEquipEvent` para bloquear equips ajenos de items `COMMAND_ONLY`: solo `ItemRegistry.apply` (que marca el cambio como programatico por una ventana de un tick) puede equiparlos. Inscripto en el ListenerHub; `registerEvents` UNICAMENTE en el bootstrap de SnLibPlugin. Mismo contrato de hot-path via `ItemPropertyListener.match`. El keep-on-death de items NO locked lo enforcea el property listener; el vector de muerte de aca cubre piezas locked, que nunca entran en circulacion por drops.

- `public static void markProgrammatic(UUID uuid, EquipmentSlot slot)` - Marca el slot del jugador como cambiado programaticamente para la ventana del tick actual (mapa estatico `PROGRAMMATIC` de `ApplyMark(slot, tick)`).
- `static boolean isProgrammatic(UUID uuid, EquipmentSlot slot)` - (package-private) Si el cambio de slot matchea una marca programatica viva. Las marcas expiran despues de UN tick: la fuente primaria de armadura hace eco de un setItem programatico al tick siguiente, asi la ventana cubre el evento sintetico y su eco.
- `public void onInventoryClick(InventoryClickEvent event)` - Vector 1: un locked en el cursor o bajo el click (slots de armadura incluidos) nunca se mueve; tambien deniega hotbar (NUMBER_KEY/SWAP_OFFHAND) y equip manual (drop de cursor a slot ARMOR o shift-click de pieza de armadura en la vista propia) de piezas no-manual-equip.
- `public void onInventoryDrag(InventoryDragEvent event)` - Vector 2: drags de una pieza locked, o de una no-manual-equip hacia los raw slots 5-8 (armadura de la vista propia, constantes privadas `ARMOR_RAW_FIRST`/`ARMOR_RAW_LAST`).
- `public void onInteract(PlayerInteractEvent event)` - Vector 3: equip manual por click derecho de una pieza de armadura no-manual-equip o locked; deniega con `setUseItemInHand(Event.Result.DENY)`.
- `public void onSwapHands(PlayerSwapHandItemsEvent event)` - Vector 4: cancela swaps de mano que involucren una pieza locked en cualquiera de las dos manos.
- `public void onDrop(PlayerDropItemEvent event)` - Vector 5: piezas no-drop y locked se quedan en el inventario.
- `public void onDeath(PlayerDeathEvent event)` - Vector 6 (priority HIGH): las piezas locked se sacan de los drops de muerte y se stashean por UUID; respeta `getKeepInventory()`.
- `public void onRespawn(PlayerRespawnEvent event)` - Vector 6, segunda mitad (priority MONITOR): las piezas locked sacadas vuelven al respawnear via `InvUtil.giveItems`.
- `public void onHopperMove(InventoryMoveItemEvent event)` - Vector 7: hoppers y cualquier movimiento contenedor-a-contenedor de una pieza locked se cancela.
- `public void onArmourEquip(SnArmourEquipEvent event)` - Bloquea equips ajenos de piezas COMMAND_ONLY (solo `ItemRegistry.apply` puede equiparlas, detectado por la marca programatica) y equips por dispenser de piezas locked o no-manual-equip; es la unica fuente cancelable vinculante del evento sintetizado.

#### Notas y gotchas
- `manualEquipDenied(def)` devuelve true tambien para `COMMAND_ONLY`: COMMAND_ONLY implica no-equip-manual. `PlayerArmorChangeEvent` llega post-hecho (no vinculante), asi que la negativa tiene que ocurrir en los vectores de click/drag/interact.
- `isArmourPiece` matchea piezas equipables por sufijo del nombre del Material (enum tratado como abierto: chequeos de nombre, nunca switch/EnumSet sobre sus constantes).
- El mapa estatico `PROGRAMMATIC` esta justificado como estatico server-wide: marcas transitorias de un tick, no data per-consumidor.

### EquipmentBackup (internal)
`src/main/java/com/sn/lib/item/internal/EquipmentBackup.java`

Backup per-contexto de los items reales desplazados por `ItemRegistry.apply`, con restauracion GARANTIZADA en quit (registrado en el QuitCleanupListener) y en shutdown (`restoreAll`, invocado por el teardown del contexto). La persistencia es write-through y default-on: cada store escribe el item desplazado en el `data/equipment-backup.yml` del contexto via `SnYml.save` (que pasa a sincronico durante el teardown por el flag shutting-down del contexto) y cada take/restore lo borra, asi un crash sin onDisable nunca pierde el item real. Las entradas persistidas se recargan en construccion; el unico opt-out es no declarar el modulo yml, que degrada a backups en memoria con UN WARN en el primer uso.

- `public EquipmentBackup(Sn ctx)` - Crea el servicio de backup de un contexto, recarga sus entradas persistidas y registra el callback de restore en quit. Monta el store via `ctx.yml().data("data/equipment-backup.yml")`; sin modulo yml (`UnsupportedOperationException`) queda null.
- `public void store(Player player, EquipmentSlot slot, @Nullable ItemStack displaced)` - Respalda el item desplazado del slot, write-through (memoria + Base64 de `ItemSerializer.serializeBase64` en el yml bajo `backups.<uuid>.<SLOT>`). Un slot vacio no guarda nada, asi una entrada persistida de un crash previo queda autoritativa; una pieza locked aplicada por la lib nunca se respalda (no es el item real del jugador).
- `public @Nullable ItemStack peek(UUID uuid, EquipmentSlot slot)` - Copia del item respaldado del slot sin consumirlo, o null.
- `public @Nullable ItemStack take(UUID uuid, EquipmentSlot slot)` - Consume el item respaldado del slot, borrando su entrada persistida (la rama del jugador entera si era la ultima), o null.
- `public void restore(UUID uuid)` - Restaura cada slot respaldado del jugador: la pieza locked aplicada por ESTE owner (o un slot vacio) se reemplaza por el item real; cualquier otro ocupante se respeta y el item real se da al inventario en su lugar (`InvUtil.giveItems`). Los jugadores offline se saltean para que sus entradas persistidas sobrevivan a la proxima sesion. Idempotente: un kick dispara kick y quit y la segunda pasada no encuentra nada. Marca cada slot como programatico antes de tocarlo.
- `public static void restoreAll(Plugin owner)` - Restaura los backups de cada jugador online del owner; el punto de entrada del teardown. Durante el teardown el save write-through corre sincronico.

#### Notas y gotchas
- El estatico `BACKUPS` (`TenantRegistry<EquipmentBackup>`) esta justificado: instancias de backup keyeadas por owner para el teardown.
- `loadPersisted` tolera entradas rotas (UUID invalido, slot invalido, Base64 corrupto) con un WARN por entrada ("Backup de equipamiento ilegible...") y las ignora.
- El WARN de degradacion a memoria ("EquipmentBackup sin modulo yml declarado...") se emite una sola vez (`AtomicBoolean.compareAndSet`).

### TODOs y limitaciones

No hay marcadores TODO/FIXME en el codigo del modulo. Limitaciones documentadas en el codigo:

- El enforcement de `equipment-slot` es best-effort: cubre click directo, shift-click auto-equip, dispenser y right-click auto-equip, pero en el vector right-click las acciones de interaccion igual despachan aunque el equip se deniegue (ItemInteractListener.denyIncompatibleAutoEquip).
- `ItemRegistry.damage` programatico NO corre break-actions ni saca el item de la mano al llegar a 0: eso solo pasa por el flujo de interaccion, que conoce al jugador usando; la rotura programatica es responsabilidad del caller.
- `PlayerArmorChangeEvent` llega post-hecho (no vinculante), por eso la negativa de equip manual de piezas COMMAND_ONLY/no-manual-equip tiene que ocurrir en los vectores de click/drag/interact (LockedItemListener.manualEquipDenied).
- Sin el modulo yml declarado, EquipmentBackup degrada a backups solo en memoria con UN WARN: un crash sin onDisable puede perder el item real desplazado.
- Compat 1.20.4: `glow()` degrada a un encantamiento real (LURE) mas HIDE_ENCHANTS, y `max-stack-size` se omite; ambos con UN WARN via probe de SnCompat.
- `ItemStack.serializeAsBytes()` clampa amounts over-stacked (gotcha SnLootBoxes); ItemSerializer existe exactamente para eso, con su prefijo de amount de 4 bytes.
- La ventana programatica de LockedItemListener dura UN tick (cubre el evento sintetico y su eco); un setItem programatico externo fuera de `ItemRegistry.apply`/`EquipmentBackup.restore` que no llame `markProgrammatic` seria cancelado para piezas COMMAND_ONLY.

---

# (Seccion 12 de la documentacion de SnLib v1.0.0)

## 12. GUI

Modulo de menus declarativos de SnLib (`com.sn.lib.gui`), accesible por contexto via `sn.guis()`. Cada archivo `.yml` de la carpeta `guis/` del plugin consumidor se parsea a una `GuiDef` inmutable (golden spec `docs/menu-example.yml`), y cada viewer que abre un menu recibe SU PROPIA `GuiSession` con su propio `Inventory`, su propio `SnGuiHolder` y su propio estado de pagina: N jugadores en el mismo GUI son N sesiones independientes, no hay inventario compartido por GUI. La identificacion de inventarios de la libreria es SIEMPRE `holder instanceof SnGuiHolder` (nunca por titulo, que se resuelve por viewer y puede colisionar entre plugins). Dos listeners compartidos e internos (registrados UNA sola vez en el bootstrap de SnLibPlugin via ListenerHub) despachan clicks/cierres y aplican la proteccion anti-robo sobre stacks marcados con la PDC key `snlib_gui_item`. Todo el modulo es main-thread only; las sesiones abiertas se registran por owner en un `TenantRegistry`, garantizando no-interferencia entre plugins consumidores.

### SnGuiHolder
`src/main/java/com/sn/lib/gui/SnGuiHolder.java`

`InventoryHolder` de todo inventario GUI que crea la libreria: uno por `GuiSession`, compartido por cada inventario que esa sesion recrea. Implementa `OwnedHolder` (modulo tenant), por lo que transporta el plugin owner: asi el tenant sweeper y el listener de quit cleanup cierran inventarios de exactamente UN owner. Constructor package-private (`SnGuiHolder(Plugin owner, String guiId, GuiSession session)`); el campo `inventory` es `volatile`.

- `public Plugin owner()` - Plugin consumidor cuyo contexto abrio este GUI (override de `OwnedHolder`).
- `public String guiId()` - Id de la definicion del GUI (nombre de archivo sin extension).
- `public GuiSession session()` - Sesion per-viewer detras de este holder.
- `public Inventory getInventory()` - Inventario actual de la sesion; lanza `IllegalStateException` ("La sesion del gui '<id>' aun no creo su inventario") si la sesion todavia no lo creo.
- `void inventory(Inventory inventory)` (package-private) - Intercambia el inventario de respaldo; solo la sesion duenia lo llama.

#### Notas y gotchas
- La sesion recrea el inventario ante cambios de titulo o tamanio manteniendo ESTE mismo holder, asi la identificacion `instanceof` sobrevive cada recreacion.

### GuiItemDef
`src/main/java/com/sn/lib/gui/GuiItemDef.java`

Un item de una definicion de GUI: toda la seccion de apariencia del golden spec mas `slots`, `update-interval` por item, requirements de vista/click y listas de acciones de click/deny. La apariencia NO se pre-construye: la definicion guarda su seccion yml y la relee en cada `render`, asi nombre, lore y todo string se resuelven por viewer via el pipeline de SnYml (locals, PAPI, `[rgb]`, `[center]`, MiniMessage). Los requirements se parsean UNA vez al load desde la seccion cruda (bypassean la resolucion de placeholders, asi los tokens llegan intactos a la evaluacion); las lineas de accion quedan crudas para el action engine, que las resuelve en runtime.

Enum package-private `NavKind` (rol de navegacion, detectado de las click actions al parsear): `NONE`, `PREVIOUS`, `NEXT`.

- `static @Nullable GuiItemDef parse(SnYml yml, String path, String id, Consumer<String> warn)` (package-private) - Parsea el item en `path`; los warnings van a `warn`. Devuelve null cuando la seccion no existe ("No existe la seccion '<path>' en <archivo>; item ignorado"). Parsea slots via `SlotParser`, `update-interval` (clamp a >= 0), `view-requirements`/`click-requirements` via `RequirementEngine`, `click-actions`/`deny-actions`; detecta `NavKind` buscando `[previous-page]`/`[next-page]` en las click actions y, si es item de navegacion y existe la subseccion `nav-disabled`, la parsea recursivamente como override.
- `public String id()` - Id del item (su key dentro de `items:` o `templates:`).
- `public int[] slots()` - Slots donde renderiza (copia defensiva via `clone()`); vacio para templates, cuyos slots vienen de los binds.
- `boolean hasSlots()` (package-private) - True cuando el item declaro al menos un slot.
- `public int updateInterval()` - Intervalo de re-render por item en ticks; 0 desactiva el timer del item.
- `public Requirement viewRequirement()` - Requirement que decide si el item renderiza para un viewer; pasa siempre cuando esta ausente.
- `public Requirement clickRequirement()` - Requirement que gatea clicks; si falla se corren los `denyActions()` en su lugar.
- `public List<String> clickActions()` - Lineas de accion crudas ejecutadas en un click que pasa el click requirement.
- `public List<String> denyActions()` - Lineas de accion crudas ejecutadas cuando el click requirement falla.
- `NavKind navKind()` (package-private) - Rol de navegacion; las sesiones gatean flechas deshabilitadas a traves de el.
- `@Nullable GuiItemDef navDisabled()` (package-private) - Override de apariencia renderizado EN LUGAR de este item de navegacion cuando no hay pagina a la que ir (primera pagina para previous, ultima para next), o null si no se declaro. Un item de navegacion deshabilitado nunca dispara ninguna accion.
- `public ItemStack render(@Nullable Player viewer, Ph... phs)` - Construye el stack fisico para `viewer` releyendo cada campo de apariencia de la seccion yml (via `SnItem.fromConfig(yml, path, viewer, phs).build()`), asi los placeholders resuelven por viewer mas los locals extra `phs`.

### GuiDef
`src/main/java/com/sn/lib/gui/GuiDef.java`

Definicion inmutable de UN GUI, parseada desde un archivo bajo `guis/` siguiendo el golden spec (`docs/menu-example.yml`): `title`, `rows`, `inventory-type` leniente, `open-sound`, `update-interval` de menu, el flag opt-in `pagination`, la seccion `items:` y la seccion `templates:`. El flag `pagination` se resuelve UNA vez al load y por defecto es `false`; acciones de pagina y binds paginados sobre sesiones de un GUI no paginado son no-ops. La definicion y sus templates son inmutables y compartidos por cada `GuiSession` per-viewer. El Javadoc de la clase incluye el checklist campo por campo del golden spec (que campo parsea que clase).

- `static GuiDef parse(Sn ctx, String id, SnYml yml)` (package-private) - Parsea el archivo entero; todo campo malformado emite WARN con prefijo `[gui <id>]` y hace fallback, nunca lanza. Archivo vacio/ilegible: WARN "Archivo vacio o ilegible; se usa un gui por defecto sin items" y devuelve un GUI default (titulo "Menu", 3 filas, sin items). `rows` fuera de 1-6 WARNea y usa 3. Items sin slots validos WARNean ("Item '<key>' sin slots validos; no se renderiza") y se descartan; los templates se parsean sin exigir slots.
- `public String id()` - Id del GUI: su nombre de archivo sin la extension `.yml`.
- `public String title()` - Titulo crudo; las sesiones resuelven sus placeholders por viewer al render.
- `public int rows()` - Filas del cofre (1-6); solo se usa cuando `inventoryType()` es null.
- `public @Nullable InventoryType inventoryType()` - Tipo de inventario no-cofre, o null para un cofre dimensionado por `rows()`.
- `public String openSound()` - Spec de sonido de apertura (`"SOUND_ID [vol] [pitch]"`); vacio no reproduce nada.
- `public int updateInterval()` - Intervalo de re-render del menu en ticks; 0 desactiva el timer del menu.
- `public boolean pagination()` - Si este menu opto por paginacion; default false.
- `public List<GuiItemDef> items()` - Items de la seccion `items:`, en orden de declaracion.
- `public @Nullable GuiTemplate template(String templateId)` - Template declarado bajo `templates:` con el id dado, o null (tambien null si `templateId` es null).
- `public Map<String, GuiTemplate> templates()` - Todos los templates de la seccion `templates:`, por id.

#### Notas y gotchas
- Resolucion leniente de `inventory-type`: vacio o `CHEST` devuelven null (cofre por rows); nombres desconocidos WARNean ("inventory-type invalido '<raw>'; usando CHEST") y caen a cofre. Se resuelve con `InventoryType.valueOf` individual en try/catch, nunca con switch, para que el enum quede abierto entre versiones de Minecraft.

### GuiManager
`src/main/java/com/sn/lib/gui/GuiManager.java`

Modulo GUI de un contexto consumidor, alcanzado via `sn.guis()`. `load()` crea la carpeta `guis/` y carga UN GUI por archivo (el id es el nombre sin extension). Las sesiones abiertas se registran por owner en un `TenantRegistry`, asi el disable de un consumidor cierra exactamente los GUIs de ese consumidor (no-interferencia); el quit cleanup corre por el quit listener compartido. Main-thread only, como todo el modulo.

Constantes y estaticos:
- `public static final String ITEM_TAG = "snlib_gui_item"` - Nombre de la PDC key estampada en cada stack GUI renderizado (payload `"<guiId>:<slot>"`), namespaceada por plugin owner via TagIo; el listener de proteccion anti-robo resuelve los stacks marcados a traves de ella.
- `static final TenantRegistry<GuiSession> SESSIONS` (package-private) - Estatico server-wide justificado: sesiones GUI abiertas por plugin owner. El callback de sweep (`GuiSession::close`) cierra cada sesion (cancela timers, destrackea el holder, force-cierra al viewer) cuando su owner key se remueve (disable del consumidor).

Metodos:
- `public GuiManager(Sn ctx)` - Crea el modulo para el contexto dado y engancha su quit cleanup via `QuitCleanupListener.register(plugin, this::closeSessionsOf)`.
- `public void load()` - Crea `guis/` si falta y (re)parsea un GUI por archivo `.yml` (orden alfabetico por nombre). Requiere el modulo yml: sin el, WARN "guis() declarado sin config(): la carpeta guis/ no se puede cargar y sn.guis() queda vacio" y retorna. Si no puede crear la carpeta: WARN "No se pudo crear la carpeta <path>". I/O sincronico por disenio: corre solo en onEnable y en el flujo de reload. Los mounts de archivos se cachean en un `ConcurrentHashMap` (`mounts`), asi el reload puede re-leer del disco.
- `public @Nullable Gui get(String id)` - GUI cargado bajo `id` (nombre de archivo sin extension, con `trim()`), o null.
- `public void registerAction(String tag, ActionHandler handler)` - Registra un tag de accion de click custom para este contexto; azucar sobre `sn.actions().register`.
- `public void reload()` - Recarga el modulo: cierra nativamente todo GUI abierto de este contexto (las sesiones son per-viewer, asi nadie queda con un inventario stale), re-lee del disco cada archivo montado, re-parsea las definiciones y levanta archivos nuevos (via `load()`).
- `public List<GuiSession> openSessions()` - Snapshot de las sesiones abiertas de ESTE contexto; la fuente de reapertura del flujo de reload.
- `public void closeAll()` - Cierra todas las sesiones GUI abiertas de ESTE contexto.
- `public void closeAll(Plugin owner)` - Cierra todas las sesiones registradas por `owner`; las de cualquier otro plugin quedan intactas (no-interferencia).
- `void warnOnce(String key, String message)` (package-private) - Loguea un warning de mal uso de GUI una vez por key para este contexto (gating de `bindPaged`).
- `private void closeSessionsOf(UUID viewer)` - Cleanup de quit/kick: cierra las sesiones de este contexto del viewer que se va.

### Gui
`src/main/java/com/sn/lib/gui/Gui.java`

Una definicion de GUI cargada y sus sesiones vivas per-viewer. `open(Player)` da a cada viewer su PROPIA `GuiSession` (inventario propio, holder propio, estado de pagina propio) sobre la `GuiDef` inmutable compartida; abrir de nuevo para un viewer con sesion viva re-muestra esa sesion en vez de apilar una segunda. Main-thread only.

- `public String id()` - Id del GUI: el nombre de archivo sin la extension `.yml`.
- `public GuiDef def()` - Definicion parseada inmutable compartida por todas las sesiones.
- `public void open(Player player)` - Abre el GUI para el jugador en la pagina 1.
- `public void open(Player player, int page)` - Abre el GUI en la pagina dada (clampeada a minimo 1 y forzada a 1 mientras el menu no opto por paginacion). Una sesion viva del viewer se re-muestra en esa pagina (`reopen`); si no, se crea una sesion nueva, se registra por owner (`GuiManager.SESSIONS.add`) y se renderiza. `player` null es no-op.
- `public @Nullable GuiTemplate template(String templateId)` - Template declarado bajo `templates:` con el id dado, o null.
- `public @Nullable GuiSession session(Player player)` - Sesion viva del jugador en ESTE GUI, o null cuando no tiene.
- `void removeSession(UUID viewer, GuiSession session)` (package-private) - Suelta el mapping de sesion de un viewer; solo lo llama la sesion que se cierra (remove condicional `Map.remove(key, value)` para no pisar una sesion mas nueva).

### GuiSession
`src/main/java/com/sn/lib/gui/GuiSession.java`

GUI vivo de UN viewer: cada viewer tiene su sesion con su PROPIO inventario, PROPIO `SnGuiHolder` y PROPIO estado de pagina, compartiendo la `GuiDef` inmutable y sus templates. El render es por viewer: view requirements, placeholders y titulo resuelven contra el player de esta sesion. Implementa `PageTarget` (modulo action): las operaciones de pagina estan gateadas por el flag opt-in `pagination` del menu. Main-thread only. Estado interno relevante: `baseSlots` (slot -> item declarado), `binds` (binds manuales por slot), `pagedPhs` (locals por slot paginado), `tasks` (task handles cancelables), y los volatiles `inventory`, `lastTitle`, `page`, `transitioningPage`, `closed`, `pagedBind`, `pagedSlots`.

- `void open()` (package-private) - Primera apertura: crea el inventario, renderiza, trackea el holder en el `TenantSweeper`, abre el inventario al viewer, reproduce el open-sound y arranca los timers.
- `void reopen(int targetPage)` (package-private) - Re-entrada via `Gui.open` sobre una sesion existente: setea la pagina (forzada a 1 sin paginacion), hace `refreshMenu()` y re-abre el inventario si el viewer no lo estaba viendo; el open-sound solo suena si no estaba viendo.
- `public Player viewer()` - Jugador duenio de esta sesion.
- `public UUID viewerId()` - UUID del viewer de la sesion.
- `public String guiId()` - Id del GUI de la definicion de respaldo.
- `public int page()` - Pagina actual de ESTE viewer (1-based); siempre 1 mientras la paginacion este apagada.
- `SnGuiHolder holder()` (package-private) - Holder compartido por cada inventario que esta sesion recrea.
- `public boolean transitioningPage()` - True mientras la sesion intercambia inventarios (cambio de pagina o recreacion); el manejo de close del click listener saltea la remocion durante una transicion.
- `public boolean closed()` - True una vez que la sesion se cerro y desregistro.
- `public @Nullable GuiItemDef itemAt(int slot)` - Definicion renderizada en `slot` para este viewer con precedencia: bind de API, luego entrada paginada, luego item declarado del slot. Null para slot vacio.
- `public void bind(int slot, GuiTemplate template, Ph... phs)` - Bindea un template a un slot de ESTA sesion con los placeholders locales dados y lo renderiza de inmediato. El bind sobrevive refreshes de pagina y recreaciones de inventario hasta ser sobreescrito; tiene precedencia sobre un item declarado en el mismo slot. Template null o slot negativo: no-op.
- `public <T> void bindPaged(String templateId, List<T> data, int[] slots, BiConsumer<T, PhCollector> mapper)` - Bindea un data set paginado a ESTA sesion: un snapshot inmutable de `data` se pagina de a `slots.length` entradas y la pagina ACTUAL de este viewer renderiza en `slots` usando el template, una entrada por slot en orden. El mapper llena los placeholders locales de cada entrada; los slots sobrantes de una pagina corta quedan vacios. El bind sobrevive cambios de pagina y recreaciones hasta ser rebindeado; la pagina se clampea al total de paginas del snapshot, que ademas maneja el estado `nav-disabled` de los items de navegacion del YML. Con `pagination: false` (default del menu) la llamada se ignora con UN warning por GUI ("bindPaged en gui '<id>' ignorado: pagination false (opt-in por menu)"); template desconocido o slots vacios tambien WARNean una vez e ignoran. `mapper` null lanza NPE (`Objects.requireNonNull`).
- `public void handleClick(int slot, ClickType click)` - Despacho de click invocado por el click listener compartido con un raw slot del top inventory: resuelve la definicion efectiva (bind manual, entrada paginada, item declarado), saltea items de navegacion deshabilitados y corre las acciones de click o deny con esta sesion como page target y el click type en el contexto. No-op si la sesion esta cerrada.
- `public void handleClose()` - Manejo de cierre invocado por el click listener cuando el cliente del viewer cerro el inventario: mismo teardown que `close()` pero sin force-cerrar la pantalla.
- `public void nextPage()` - (override `PageTarget`) Avanza una pagina y refresca; no-op con debug si la paginacion esta apagada, y no pasa de la ultima pagina CONOCIDA (solo un bind paginado conoce el total).
- `public void previousPage()` - (override) Retrocede una pagina si `page > 1` y refresca; no-op con debug sin paginacion.
- `public void setPage(int targetPage)` - (override) Setea la pagina (minimo 1, clampeada al total conocido si hay bind paginado) y refresca; no-op con debug sin paginacion.
- `public void refreshPage()` - (override) Re-renderiza el contenido del inventario actual; no-op con debug sin paginacion.
- `public void refreshMenu()` - (override) Re-render completo SIN gate de paginacion; recrea el inventario cuando el titulo resuelto cambio (mismo holder y sesion, preservando pagina y binds), si no solo re-renderiza contenidos.
- `public boolean paginationEnabled()` - (override) Devuelve el flag `pagination` de la definicion.
- `public void close()` - Cierra la sesion: cancela sus timers, la desregistra de los registries per-owner, destrackea el holder y force-cierra el inventario del viewer si esta sesion sigue en pantalla. Idempotente.

#### Logica interna
- Gate de paginacion (`paginationBlocked(String operation)`): con `pagination: false` toda operacion de pagina es no-op registrada por el servicio de debug del contexto: `"GUI '<id>': <operation> ignorado, pagination false (opt-in por menu)"`.
- Nav deshabilitado (`navDisabledNow`): previous se deshabilita en la pagina 1; next en la ultima pagina CONOCIDA (`knownTotalPages()` devuelve 0 sin bind paginado, o sea desconocido, y en ese caso next nunca se deshabilita). Un item deshabilitado renderiza su override `nav-disabled` (si existe) y no dispara nada.
- Recreacion (`recreate(Component title)`): setea `transitioningPage = true`, crea el inventario nuevo con el MISMO holder, re-renderiza, re-abre al viewer y baja el flag en `finally`; el guard evita que el `InventoryCloseEvent` del intercambio derribe la sesion.
- `createInventory`: intenta el `inventory-type` declarado; ante `Throwable` WARNea una sola vez por sesion (`typeWarned`) "[gui <id>] inventory-type <X> no se pudo crear (<t>); usando CHEST" y cae a cofre `rows * 9`.
- Render (`renderContents`): limpia el inventario y renderiza en tres fases con precedencia: items declarados (salteando slots tomados por binds o por el bind paginado), luego la pagina actual del bind paginado (salteando slots con bind manual, clampeando `page` al total), luego los binds manuales. El view requirement de cada definicion se testea contra el resolver del viewer (PAPI + locals); si falla, el slot queda en null.
- Marker anti-robo (`stamp`): cada stack renderizado se estampa via `TagIo.set(stack, ctx.plugin(), GuiManager.ITEM_TAG, def.id() + ":" + slot)` (PDC key `snlib_gui_item`, namespaceada por plugin owner, payload `"<guiId>:<slot>"`).
- Timers (`startTimers`): si `update-interval` de menu > 0 arranca un timer de menu; por cada item con `update-interval` > 0 arranca un timer de item. Ambos ticks primero chequean `closed`, y si el viewer ya no esta viendo el inventario cierran la sesion (auto-limpieza). El tick de menu llama `refreshMenu()`: re-evalua titulo (y por ende recrea el inventario preservando sesion, pagina y binds cuando cambio); el tick de item re-renderiza solo ese item.
- Clicks (`runClick`): construye un `ActionContext(viewer, ctx, this, click, phs)` y corre `clickActions` si el click requirement pasa, o `denyActions` si no, via `ctx.actions().run`.
- Records privados: `Binding(GuiTemplate template, Ph[] phs)` (bind manual con sus locals capturados al bind) y `PagedBind<T>(GuiTemplate template, Pagination<T> pagination, int[] slots, BiConsumer<T, PhCollector> mapper)` (bind paginado vivo).

#### Notas y gotchas
- El cierre "nativo" cubre todos los caminos: quit/kick (QuitCleanupListener -> `GuiManager.closeSessionsOf`), reload (`GuiManager.reload()` -> `closeAll()`), disable del consumidor (sweep del `TenantRegistry` con callback `GuiSession::close`) y cierre manual del cliente (`handleClose` via el click listener). En todos, timers cancelados y registries limpios.
- `renderPaged` limpia y repuebla `pagedPhs` en cada render, asi los locals que ve `handleClick` corresponden siempre a la pagina actual.

### GuiTemplate
`src/main/java/com/sn/lib/gui/GuiTemplate.java`

Item GUI reutilizable SIN slots, declarado bajo la seccion `templates:` de un archivo GUI: el usuario de config personaliza apariencia y acciones libremente mientras el plugin decide en runtime a que slot va cada template via `GuiSession.bind(int, GuiTemplate, Ph...)`. Soporta exactamente los mismos campos que un item regular excepto `slots:`, y tipicamente usa placeholders locales definidos por el plugin (por ejemplo `%index%`) provistos como pares `Ph` al bindear.

- `public String id()` - Id del template (su key dentro de la seccion `templates:`).
- `public ItemStack render(@Nullable Player viewer, Ph... phs)` - Construye el stack fisico para `viewer` con los placeholders locales dados (delega en `GuiItemDef.render`).
- `GuiItemDef item()` (package-private) - Definicion de respaldo (requirements y listas de acciones) usada por el flujo de click.

### Pagination
`src/main/java/com/sn/lib/gui/Pagination.java`

Pager inmutable 1-based sobre un snapshot fijo de datos (port del helper de paginacion de SnGens): la lista se copia una vez en la creacion y se corta por pagina on demand. Las paginas siempre se clampean: pedir una pagina menor a 1 devuelve la primera y pedir mas alla de `totalPages()` devuelve la ultima. Un data set vacio igual reporta una pagina (vacia), asi la matematica de navegacion nunca divide por cero.

- `public static <T> Pagination<T> of(List<T> data, int pageSize)` - Crea un pager sobre un snapshot de `data` (null significa vacio; los elementos deben ser no-null por `List.copyOf`) con `pageSize` entradas por pagina (valores menores a 1 se elevan a 1).
- `public int pageSize()` - Entradas por pagina.
- `public int size()` - Total de elementos del snapshot.
- `public int totalPages()` - Total de paginas; al menos 1 aun con snapshot vacio.
- `public List<T> page(int page)` - Slice de la pagina 1-based dada, clampeada al rango; posiblemente mas corta que `pageSize` (devuelve una vista via `subList`, o `List.of()` si esta vacia).

### PhCollector
`src/main/java/com/sn/lib/gui/PhCollector.java`

Acumulador de pares de placeholders locales entregado al mapper de `bindPaged`: el mapper llena un collector por entrada paginada y la sesion renderiza el template con los pares colectados.

- `public PhCollector add(String key, Object value)` - Agrega un par via `Ph.of`; keys null o vacias se ignoran. Devuelve `this` (encadenable).
- `public Ph[] toArray()` - Pares colectados en orden de insercion.

### GuiClickListener (internal)
`src/main/java/com/sn/lib/gui/internal/GuiClickListener.java`

Unico click listener compartido, propiedad de SnLib, para TODO GUI de la libreria de todos los consumidores. Inscripto en el ListenerHub; el `registerEvents` ocurre UNICAMENTE en el bootstrap de SnLibPlugin. Identificacion SIEMPRE por `holder instanceof SnGuiHolder`, nunca por titulo.

- `public void onClick(InventoryClickEvent event)` - (`@EventHandler(priority = HIGH, ignoreCancelled = true)`) Clicks sobre un GUI de la libreria: `COLLECT_TO_CURSOR` (stacking por doble click) se cancela INCONDICIONALMENTE antes que nada, cerrando el vector de extraccion por doble click; despues se cancela todo click y, si el raw slot cae dentro del top inventory (`0 <= rawSlot < size`), se despacha a `holder.session().handleClick(rawSlot, event.getClick())`.
- `public void onDrag(InventoryDragEvent event)` - (`@EventHandler(priority = HIGH, ignoreCancelled = true)`) Drags sobre un GUI de la libreria: siempre cancelados.
- `public void onClose(InventoryCloseEvent event)` - (`@EventHandler`) Cierre natural: derriba la sesion (`handleClose`) SALVO que este intercambiando inventarios de un cambio de pagina o recreacion, guardado per-viewer por `GuiSession.transitioningPage()`.

### GuiProtectionListener (internal)
`src/main/java/com/sn/lib/gui/internal/GuiProtectionListener.java`

Unico listener anti-robo compartido, propiedad de SnLib (generalizacion de la proteccion de EdToolsArmors): cualquier stack estampado con la PDC key marker `snlib_gui_item` (payload `"<guiId>:<slot>"`, namespaceada por plugin owner, por lo que la deteccion escanea las PDC keys POR NOMBRE a traves de todos los namespaces) es un item GUI renderizado y NUNCA debe circular fuera de un GUI de la libreria. Los stacks marcados se BORRAN al detectarse, no se devuelven. Inscripto en el ListenerHub; registro unico en el bootstrap de SnLibPlugin. Contrato de hot-path (este listener ve cada evento de inventario del server): cada chequeo hace quick-exit en capas: null/air primero, despues `hasItemMeta()`, despues el scan de PDC.

Estatico: `private static volatile boolean reactiveSweep` - toggle opt-in del sweep reactivo (server-wide justificado: no es dato per-consumer), apagado por defecto.

- `public static void reactiveSweep(boolean enabled)` - Habilita o deshabilita el sweep reactivo en apertura de inventario y join de jugador.
- `public void onInventoryClick(InventoryClickEvent event)` - (`priority = HIGH`) Vector 1: click sobre un stack marcado fuera de un GUI de la libreria lo borra (current item), cursor marcado se limpia, `NUMBER_KEY` borra el slot de hotbar destino si esta marcado, `SWAP_OFFHAND` borra la offhand marcada. Si actuo en algo, cancela el evento.
- `public void onInventoryDrag(InventoryDragEvent event)` - Vector 2: drags de un stack marcado (old cursor) se cancelan.
- `public void onInteract(PlayerInteractEvent event)` - (`priority = HIGHEST`) Vector 3: usar un stack marcado limpia esa mano y niega la interaccion (ambos results en `DENY`, que equivalen al cancel del evento de interaccion).
- `public void onSwapHands(PlayerSwapHandItemsEvent event)` - (`priority = HIGHEST`) Vector 4: swaps de mano que involucran un stack marcado se cancelan y el stack se borra de la mano donde vive realmente (el item que iba a la main hand vive en la offhand actual, y viceversa).
- `public void onDrop(PlayerDropItemEvent event)` - (`priority = HIGHEST`) Vector 5: los stacks marcados dropeados desaparecen (se remueve la entidad de drop); el drop NO se cancela.
- `public void onDeath(PlayerDeathEvent event)` - Vector 6: los stacks marcados nunca llegan a los death drops (`removeIf` sobre la lista de drops).
- `public void onItemSpawn(ItemSpawnEvent event)` - Vector 7, catch-all: una entidad de item marcada se remueve en el momento en que spawnea; filtra por `hasItemMeta()` antes que nada (hot-path).
- `public void onInventoryOpen(InventoryOpenEvent event)` - Sweep reactivo (flag): abrir un inventario NO perteneciente a la libreria purga los stacks marcados de ese inventario. No-op si el flag esta apagado.
- `public void onJoin(PlayerJoinEvent event)` - Sweep reactivo (flag): el inventario de un jugador que joinea se purga de stacks marcados.

#### Logica interna
- `private static void sweep(Inventory inventory)` - Recorre los contents y anula cada slot con stack marcado.
- `private static boolean insideGui(@Nullable Inventory inventory)` - True si el holder del inventario es un `SnGuiHolder`.
- `private static boolean marked(@Nullable ItemStack stack)` - Deteccion en capas con quick-exit: null/air, `hasItemMeta`, y despues un scan de PDC keys que matchea `snlib_gui_item` bajo CUALQUIER namespace (el stamp esta namespaceado por plugin owner, asi un lookup de namespace fijo lo perderia).

### TODOs y limitaciones
- No hay TODO/FIXME/placeholder en el codigo del modulo.
- Limitaciones documentadas en el codigo:
  - El total de paginas solo se conoce con un `bindPaged` vivo (`knownTotalPages()` devuelve 0 = desconocido sin bind); sin bind paginado, el item de navegacion "next" nunca se deshabilita y `nextPage()` avanza sin tope superior.
  - `bindPaged`, las operaciones de pagina y los nav items requieren `pagination: true` en el YML del menu (opt-in por menu, default false): sin el flag son no-ops (bindPaged con WARN una vez por GUI; page ops con nota de debug).
  - `GuiManager.load()` requiere el modulo yml del contexto (`config()`): sin el, `sn.guis()` queda vacio con un WARN.
  - `load()`/`reload()` hacen I/O sincronico por disenio (solo en onEnable y en el flujo de reload).
  - Todo el modulo es main-thread only.
  - El sweep reactivo del listener de proteccion es opt-in y esta apagado por defecto.

---

## 13. Comandos

Modulo de comandos del contexto consumidor, alcanzado via `sn.commands()`. Provee un builder fluido de arboles root/subcomando (`SnCommands` -> `RootBuilder` -> `SubCommandBuilder`) que se materializa en un `RootCommand` de Bukkit con chequeo de permisos primero, validacion de cantidad de argumentos contra el usage generado, parsing tipado por `Arg` y help generado paginado con `Page`. Cada root inyecta por defecto los subcomandos `reload` y `help` (y `debug` si el spec lo declaro), reemplazables u omitibles con `withoutDefaults()`. El registro contra Bukkit lo hace `internal/BukkitCommandRegistry` con ownership por `Plugin` (reload-safe, sweep de tenant al deshabilitarse el consumidor) y refresco de arboles cliente via `updateCommands()`. La propia libreria registra su comando diagnostico `/snlib` por este mismo camino (`internal/SnLibCommand`). Toda la ejecucion y el tab-complete corren en el hilo principal del servidor (dispatch estandar de Bukkit).

### SnCommands
`src/main/java/com/sn/lib/command/SnCommands.java`

Modulo de comandos de un contexto consumidor. Cada root construido aca inyecta un subcomando `reload` (permiso `<plugin>.admin.reload`, delega en `Sn.reloadAll()` y confirma con `snlib.reload-done`) y un `help` generado; ambos son reemplazables declarando subcomandos con esos nombres y removibles via `withoutDefaults()`. Si el spec declaro `debugCommand()`, se inyecta ademas un subcomando `debug` (permiso `<plugin>.admin.debug`) que togglea el servicio de debug en runtime; ese esta gateado por el spec, no por el opt-out de defaults.

- `public SnCommands(Sn ctx, @Nullable SnLang lang, boolean debugCommand)` - constructor; lo instancia el contexto. `lang` puede ser null (renderizan los templates default `snlib.*` compartidos); `debugCommand` indica si el spec declaro el comando de debug.
- `public RootBuilder root(String name)` - inicia un arbol root con ese nombre; valida no nulo y no vacio (`IllegalArgumentException` "Nombre de comando vacio").
- `public void unregisterAll()` - desregistra todos los roots del plugin owner y refresca los arboles cliente; lo invoca el teardown del contexto.
- `public void reregisterAll()` - re-registra todos los roots del plugin owner; es el paso de re-registro del flujo de reload del contexto.

#### SnCommands.RootBuilder (clase interna publica)
Builder de un arbol root.

- `public RootBuilder aliases(String... aliases)` - agrega aliases al root (trim + lowercase `Locale.ROOT`).
- `public RootBuilder permission(String permission)` - permiso del root, heredado por todo subcomando sin permiso propio. Sin permiso el root es publico.
- `public RootBuilder description(String description)` - descripcion del root (null se normaliza a "").
- `public SubCommandBuilder sub(String name)` - inicia un subcomando; se cierra con `SubCommandBuilder.and()`. Valida nombre no vacio.
- `public RootBuilder withoutDefaults()` - omite los defaults `reload` y `help`. El consumidor DEBE entonces proveer sus propios reload y help: sn-core los declara obligatorios en todo root.
- `public RootCommand register()` - construye el arbol, inyecta los defaults aplicables y lo registra contra Bukkit; devuelve el `RootCommand`.

**Notas y gotchas**
- El nombre del root se normaliza con trim + lowercase en el constructor del builder.
- `debug` NO se desactiva con `withoutDefaults()`: su unica llave es que el spec del contexto haya declarado `debugCommand()`.

### RootCommand
`src/main/java/com/sn/lib/command/RootCommand.java`

Raiz de un arbol de comandos; extiende `org.bukkit.command.Command` e implementa `Registrable` (modulo de reload). Despacha a sus subcomandos con chequeo de permiso primero, valida la cantidad de argumentos contra el usage generado, parsea tipado via cada `Arg` y genera el help. Herencia de permisos: un subcomando sin permiso propio hereda el del root; un root sin permiso es publico. El tab-complete y el help generado listan SOLO los subcomandos visibles Y cuyo permiso efectivo tenga el sender. Los mensajes resuelven por el modulo lang del contexto si fue declarado; sin lang renderizan los templates default `snlib.*` embebidos en la libreria.

Constantes (privadas, pero definen el contrato observable):
- `DEFAULT_MESSAGES` - mapa estatico de templates default que espeja `snlib-messages.yml` (static server-wide justificado por ser constante). Claves: `snlib.no-permission`, `snlib.usage`, `snlib.invalid-number`, `snlib.invalid-value`, `snlib.out-of-range`, `snlib.player-not-found`, `snlib.unknown-subcommand`, `snlib.reload-done`, `snlib.help.header`, `snlib.help.entry`, `snlib.help.footer`.
- `HELP_PAGE_SIZE = 8` - entradas por pagina del help generado.

Metodos publicos:
- `public JavaPlugin owner()` - plugin consumidor dueño de este arbol.
- `public void register()` - (override de `Registrable`) registra este root contra Bukkit bajo el plugin owner, via `BukkitCommandRegistry.register`.
- `public void unregister()` - (override de `Registrable`) desregistra este root y refresca los arboles cliente.
- `public boolean execute(CommandSender sender, String label, String[] args)` - dispatch completo (ver logica interna). Siempre devuelve `true`.
- `public List<String> tabComplete(CommandSender sender, String alias, String[] args)` - tab gateado por permiso (ver logica interna).

**Logica interna (execute)**
1. Permiso del root: sin permiso el sender recibe `snlib.no-permission` y corta.
2. Sin argumentos: envia el help (pagina 1).
3. Resuelve el subcomando por nombre o alias (lowercase); desconocido -> `snlib.unknown-subcommand` con `{value}`.
4. Permiso efectivo del sub (propio o el heredado del root); sin permiso -> `snlib.no-permission`.
5. Si `subArgs.length < requiredArgs` -> `snlib.usage` con `{usage}` (usage custom o generado).
6. Condiciones `when(index, predicate)`: cada condicion cuyo indice cae dentro de los tokens provistos se evalua sobre el token crudo; si falla -> `snlib.usage`. Una condicion sobre un opcional ausente se saltea (chequeo `at < subArgs.length`).
7. Parsing tipado en orden de declaracion: por cada `Arg` declarado, mientras queden tokens, parsea y guarda en un `LinkedHashMap`. Si el ULTIMO arg declarado es greedy, el token es el join con espacios de todos los tokens restantes. Un `Arg.ArgParseException` envia su `langKey()` con sus `phs()` y corta.
8. Sin executor declarado -> `snlib.usage`. El executor corre envuelto en try/catch `Throwable`: una falla se loguea `SEVERE` con "El subcomando '/<root> <sub>' fallo" y el stack trace, sin propagar al dispatcher de Bukkit.

**Logica interna (tabComplete)**
- Sin el permiso del root devuelve lista vacia (el sender no ve NADA del arbol).
- Con `args.length <= 1`: nombres de subcomandos visibles cuyo permiso efectivo tiene el sender, filtrados por prefijo y ordenados.
- Para argumentos: gate de permiso del sub de nuevo; `argIndex = args.length - 2`; si el indice supera los args declarados, solo un ultimo arg greedy sigue sugiriendo; si no, delega en `Arg.suggest(sender, partial)` del arg posicional. Un `suggest` que devuelva null se normaliza a lista vacia.

**Logica interna (help generado)**
Header `snlib.help.header`, una entrada `snlib.help.entry` por subcomando visible y permitido (placeholders `{usage}` y `{permission}`, vacio si publico), paginado con `Page` en paginas de 8; el footer `snlib.help.footer` (`{page}`, `{total}`, `{command}`) aparece solo con mas de una pagina. El token de pagina de `/cmd help <page>` se parsea de `context.raw(0)`; cualquier cosa no parseable cae a pagina 1, y las paginas fuera de rango se clampean.

**Notas y gotchas**
- Los defaults se inyectan en el constructor solo si no existe ya un sub con ese nombre o alias (`hasSub`): un sub del consumidor llamado `reload`/`help`/`debug` reemplaza al default.
- El permiso base de los defaults es `<nombre-del-plugin-en-lowercase>.admin.` + `reload`/`debug`. El `help` default no tiene permiso propio (hereda el del root si existe).
- Tokens extra mas alla de los args declarados se ignoran silenciosamente (salvo greedy final que los consume).
- Solo los args de la fabrica (`Args.SnArg`) pueden ser greedy: `isGreedy` hace instanceof de `Args.SnArg` y consulta `greedy()`.
- El usage generado tiene forma `/root sub <requerido> [opcional]`, con `...` anexado al nombre del ultimo arg si es greedy.

#### RootCommand.Condition (record package-private)
`record Condition(int index, Predicate<String> test)` - condicion declarativa sobre el token crudo en `index`, creada por `SubCommandBuilder.when(int, Predicate)`; un token que falla rechaza la invocacion con el mensaje de usage ANTES de cualquier parsing tipado.

#### RootCommand.Sub (clase package-private)
Nodo de subcomando inmutable construido por `SubCommandBuilder`: `name` (trim + lowercase), `aliases` (lowercased, copia inmutable), `permission` nullable, `usage` nullable, `description`, `visible`, `args` (`LinkedHashMap` inmutable, el orden de declaracion es el orden de parseo), `requiredArgs`, `conditions`, `executor` nullable. `static Sub of(String name, @Nullable String permission, String description, Consumer<CommandContext> executor)` fabrica los subs default (sin args, visibles).

### SubCommandBuilder
`src/main/java/com/sn/lib/command/SubCommandBuilder.java`

Builder de un subcomando dentro de una cadena `SnCommands.RootBuilder`; `and()` vuelve al root builder para declarar el siguiente subcomando o registrar el arbol.

- `public SubCommandBuilder aliases(String... aliases)` - agrega aliases (trim + lowercase).
- `public SubCommandBuilder permission(String permission)` - permiso propio; sin uno hereda el del root.
- `public SubCommandBuilder usage(String usage)` - linea de usage mostrada en errores de argumentos; sin una se genera desde los args.
- `public SubCommandBuilder description(String description)` - descripcion (null -> "").
- `public SubCommandBuilder visible(boolean visible)` - si aparece en tab-complete y en el help generado.
- `public SubCommandBuilder arg(String name, Arg<?> arg)` - declara el siguiente argumento posicional REQUERIDO; el orden de declaracion es el orden de parseo. Declarar uno requerido despues de un opcional lanza `IllegalStateException`. Nombre duplicado lanza `IllegalArgumentException`.
- `public SubCommandBuilder argOptional(String name, Arg<?> arg)` - declara un argumento posicional OPCIONAL al final: sugiere y parsea cuando el token esta presente pero su ausencia nunca rechaza la invocacion. Los opcionales van ultimos.
- `public SubCommandBuilder when(int index, Predicate<String> condition)` - condicion declarativa sobre el token crudo en `index` (0-based entre los argumentos del subcomando); un token que falla rechaza con el usage antes del parsing tipado. Indice negativo lanza `IllegalArgumentException`.
- `public SubCommandBuilder executes(Consumer<CommandContext> executor)` - handler que corre una vez que permiso, cantidad de argumentos, condiciones y parsing tipado pasaron todos.
- `public SnCommands.RootBuilder and()` - vuelve al root builder.

**Notas y gotchas**
- `requiredArgs` cuenta solo los `arg(...)`; los `argOptional` no incrementan el contador, asi el chequeo de cantidad minima nunca los exige.
- Un opcional ausente NO queda en el mapa de valores: `CommandContext.get(name)` sobre el lanza `IllegalArgumentException`. Para opcionales conviene chequear presencia con `context.raw(index)` antes de leer.

### Args
`src/main/java/com/sn/lib/command/Args.java`

Fabrica de implementaciones tipadas de `Arg` para `SubCommandBuilder.arg`. Todo arg construido aca lleva sugerencias de ejemplo default y acepta el decorador `suggestCurrent(Supplier)`, que antepone el valor actual real: con partial vacio (o prefijo que matchee) el valor actual y los ejemplos van primero; un partial no vacio filtra las opciones base via `StringUtil.copyPartialMatches` y las ordena. El cliente vanilla filtra las sugerencias por el prefijo tipeado, asi que un ejemplo que no matchea nunca llega a pantalla.

Constante:
- `SUGGESTION_CAP = 100` (privada) - tope de las sugerencias respaldadas por listas (jugadores online, opciones de `oneOf`).

Metodos de fabrica (todos `static`):
- `public static SnArg<Player> onlinePlayer()` - jugador online por nombre exacto (`Bukkit.getPlayerExact`); rechaza con `snlib.player-not-found` (`{value}`) y sugiere hasta 100 nombres online.
- `public static SnArg<UUID> offlinePlayerUuid()` - UUID de jugador resuelto ESTRICTAMENTE sin bloquear: primero match online exacto, despues el cache local de offline-players (`Bukkit.getOfflinePlayerIfCached`). Un nombre ausente de ambos rechaza con `snlib.player-not-found`. `Bukkit.getOfflinePlayer(String)` nunca se usa aca porque puede hacer un lookup de perfil BLOQUEANTE en el main thread; la resolucion remota le corresponde al consumidor via el scheduler async. Sugiere nombres online.
- `public static SnArg<String> oneOf(Supplier<Collection<String>> options)` - un valor de un set dinamico de opciones, matcheado case-insensitive y devuelto en su forma canonica (la de la coleccion, no la tipeada); rechaza con `snlib.invalid-value` y sugiere hasta 100 de las opciones actuales (saltea nulls).
- `public static SnArg<Integer> intRange(int min, int max)` - entero en `[min, max]` (`Integer.parseInt` sobre el token trimmeado); no-numero rechaza con `snlib.invalid-number` y fuera de rango con `snlib.out-of-range` (`{value}`, `{min}`, `{max}`). Sugiere ambos limites como ejemplos.
- `public static SnArg<Double> doubleRange(double min, double max)` - double en `[min, max]`; acepta coma decimal (reemplaza `,` por `.` antes de parsear); no-numero -> `snlib.invalid-number`; `NaN` o fuera de rango -> `snlib.out-of-range`. Sugiere ambos limites.
- `public static SnArg<Long> duration()` - duracion compacta tipo `"1d 2h 30m 15s"` parseada a milisegundos via `TimeUtil.parseMillis(String)`; cero o no parseable rechaza con `snlib.invalid-value`. Ejemplo `30m`; opciones `30s`, `5m`, `1h`, `1d`.
- `public static SnArg<Boolean> bool()` - booleano que acepta `true/yes/on` y `false/no/off` (case-insensitive); cualquier otra cosa rechaza con `snlib.invalid-value`. Sugiere `true` y `false`.
- `public static SnArg<String> string()` - token unico libre, devuelto tal cual. Ejemplo `text`.
- `public static SnArg<String> greedy()` - texto libre que consume todos los tokens restantes como un unico valor unido por espacios. Solo tiene sentido como ULTIMO argumento declarado. Ejemplo `text`.

#### Args.SnArg\<T\> (clase abstracta publica)
Arg producido por la fabrica: sugerencias de ejemplo default mas el decorador `suggestCurrent`.

- `protected SnArg(List<String> examples, boolean greedy)` - constructor con los ejemplos default y el flag greedy.
- `public final SnArg<T> suggestCurrent(Supplier<String> current)` - antepone el valor actual real (suplido) a las sugerencias, antes de los ejemplos y las opciones base. Devuelve `this` (fluido).
- `public final boolean greedy()` - si este arg consume todos los tokens restantes como un solo valor.
- `protected List<String> options(CommandSender sender)` - opciones base para el sender; vacia cuando solo aplican los ejemplos (hook que sobreescriben las fabricas con listas dinamicas).
- `public final List<String> suggest(CommandSender sender, String partial)` - implementacion final del contrato `Arg`: arma la lista en orden valor-actual -> ejemplos -> opciones base, con dedupe case-insensitive; partial vacio no filtra las opciones base, partial no vacio las filtra con `StringUtil.copyPartialMatches` y las ordena.

**Notas y gotchas**
- El supplier de `suggestCurrent` corre dentro de un try/catch `Throwable`: un supplier que falla simplemente no aporta valor actual (nunca rompe el tab).
- El cap de 100 aplica a las opciones base con respaldo de lista (nombres online, `oneOf`); el valor actual y los ejemplos no cuentan contra el cap.
- El valor actual y los ejemplos se filtran por prefijo case-insensitive (`StringUtil.startsWithIgnoreCase`) igual que las opciones, asi la lista nunca contiene entradas que el cliente descartaria.

### Arg
`src/main/java/com/sn/lib/command/Arg.java`

Interfaz de argumento tipado de comando: parsea un token crudo a `T` y provee sus sugerencias de tab. Las implementaciones vienen de la fabrica `Args` o del consumidor.

- `T parse(String raw) throws ArgParseException` - parsea el token crudo al valor tipado; lanza `ArgParseException` cuando el token es invalido (lleva la clave de lang y los placeholders locales que el flujo de comando devuelve al sender).
- `List<String> suggest(CommandSender sender, String partial)` - sugerencias de tab para el token parcial, resueltas para ese sender.

#### Arg.ArgParseException (clase publica anidada, extiende `Exception`)
Rechazo de un token crudo, expresado como clave de lang mas sus placeholders locales.

- `public ArgParseException(String langKey, Ph... phs)` - `langKey` es una clave `snlib.*` o una del consumidor; `phs` son los placeholders locales (null se normaliza a array vacio; se clona defensivamente).
- `public String langKey()` - clave de lang del mensaje de error.
- `public Ph[] phs()` - placeholders locales del mensaje (copia defensiva).

### CommandContext
`src/main/java/com/sn/lib/command/CommandContext.java`

Invocacion parseada de un subcomando: el sender mas cada argumento declarado ya parseado por su `Arg`, indexado por el nombre dado en el builder.

- `public CommandSender sender()` - el sender, jugador o consola.
- `public Player player()` - el sender como jugador; lanza `IllegalStateException` ("El sender de este comando no es un jugador") si no lo es.
- `public <T> T get(String name)` - valor parseado de un argumento declarado; lanza `IllegalArgumentException` cuando no hay valor con ese nombre (incluye el caso de un opcional ausente).
- `public int getInt(String name)` - valor parseado como int; acepta cualquier resultado numerico (`Number.intValue()`) o parsea el `toString()` trimmeado.
- `public double getDouble(String name)` - valor parseado como double; misma tolerancia que `getInt`.
- `public Player player(String name)` - valor parseado como jugador (azucar sobre `get`).
- `public @Nullable String raw(int index)` - token crudo en `index` entre los argumentos del subcomando, o null cuando esta ausente. Es la via segura para chequear presencia de opcionales.

**Notas y gotchas**
- `get` hace un cast unchecked al tipo pedido: un tipo equivocado revienta con `ClassCastException` en el call site del consumidor.

### Page
`src/main/java/com/sn/lib/command/Page.java`

Paginador generico de texto de chat sobre una lista inmutable de items; respalda el help generado de comandos y es reutilizable por los consumidores para cualquier listado paginado. Las paginas son 1-based y los pedidos fuera de rango se clampean a la pagina valida mas cercana, asi una lista vacia igual expone una pagina (vacia). El render va por Adventure: una linea `Component` por item.

- `public static <T> Page<T> of(List<T> items, int pageSize)` - pagina `items` en paginas de `pageSize` entradas (minimo 1); copia inmutable de la lista.
- `public int size()` - cantidad total de items.
- `public int pageSize()` - entradas por pagina.
- `public int totalPages()` - cantidad total de paginas, al menos 1.
- `public int clamp(int page)` - clampea una pagina pedida a `[1, totalPages()]`.
- `public List<T> page(int page)` - items de la pagina 1-based dada, clampeada al rango.
- `public void send(CommandSender sender, int page, Function<T, Component> renderer)` - renderiza y envia la pagina dada, una linea `Component` por item.

### BukkitCommandRegistry (internal)
`src/main/java/com/sn/lib/command/internal/BukkitCommandRegistry.java`

Puente entre los arboles `RootCommand` y el sistema de comandos de Bukkit, prefiriendo la API publica en dos caminos: (a) un comando declarado en el plugin.yml del owner recibe su executor y tab completer via `plugin.getCommand(name)` (adapter `PluginCommandAdapter`); (b) los roots no declarados y los aliases dinamicos van por el `Bukkit.getCommandMap()` publico de Paper, cada uno con un WARN. Despues de CADA register y unregister, los jugadores online reciben `updateCommands()` para que sus arboles cliente nunca muestren fantasmas. Los roots registrados se trackean en un `TenantRegistry<RootCommand>` (static server-wide justificado) keyed por plugin owner: el sweep de tenant desengancha cada comando y remueve la clave completa del owner cuando el consumidor se deshabilita, aunque el owner nunca haya llamado al teardown.

- `public static void register(JavaPlugin owner, RootCommand command)` - registra el root para su owner. Reload-safe: un root ya registrado por el mismo owner bajo el mismo nombre se desengancha y reemplaza primero. Camino plugin.yml si el comando esta declarado; si no, WARN ("Comando '/x' no declarado en el plugin.yml de Y; registro dinamico via CommandMap") y registro por CommandMap con prefijo = nombre del owner en lowercase. Cierra con `updateCommands()`.
- `public static void unregister(JavaPlugin owner, RootCommand command)` - desregistra un root del owner y refresca los arboles cliente.
- `public static void unregisterAll(JavaPlugin owner)` - desregistra todos los roots del owner removiendo la CLAVE COMPLETA del owner; el callback de sweep desengancha cada comando y refresca los arboles cliente.
- `public static void reregisterAll(JavaPlugin owner)` - re-registra en el lugar cada root del owner (itera una copia); es el paso de re-registro del flujo de reload. Cada pasada de register refresca los arboles cliente de los jugadores online.

**Logica interna**
- `sweep(RootCommand)` - callback del `TenantRegistry`: `detach` + `updateCommands`; tambien corre cuando el sweeper de tenants remueve la clave de un owner deshabilitado.
- `registerDynamicAliases(...)` - los aliases construidos en codigo que no forman parte de la declaracion del plugin.yml reciben entradas en `getKnownCommands()` apuntando al arbol root (con `putIfAbsent`, tanto `alias` como `owner:alias`), con un WARN ("Aliases [...] de '/x' no declarados en el plugin.yml de Y; registro dinamico via CommandMap").
- `detach(RootCommand)` - desengancha el comando del camino que lo registro: remueve por identidad las entradas de knownCommands que apuntan a el, llama `command.unregister(map)`, y si el `PluginCommand` declarado tiene como executor un `PluginCommandAdapter` de ESTE root, limpia executor y tab completer.
- `updateCommands()` - `player.updateCommands()` para cada jugador online (main thread).
- `PluginCommandAdapter` (record privado, `CommandExecutor` + `TabCompleter`) - delega `onCommand` en `root.execute` y `onTabComplete` en `root.tabComplete`; es el executor del camino plugin.yml.

**Notas y gotchas**
- `putIfAbsent` en los aliases dinamicos significa que un alias ya tomado por otro comando NO se pisa: el alias simplemente no queda operativo para este root.
- El chequeo de reemplazo en `register` compara por identidad (`existing != command`) ademas del nombre, asi `reregisterAll` puede volver a registrar la misma instancia sin desengancharse a si misma.

### SnLibCommand (internal)
`src/main/java/com/sn/lib/command/internal/SnLibCommand.java`

Comando root diagnostico de la libreria misma: `/snlib` se registra sobre el contexto propio del bootstrap (el selfCtx que `SnLibPlugin` crea via el `SnLib.init` in-package), a traves del MISMO modulo `sn.commands()` que usa todo consumidor; no hay instancia suelta de `SnCommands` ni segunda config. Cada subcomando esta gateado en tab por su permiso `snlib.admin.*` de plugin.yml. Clase final con constructor privado (solo estaticos).

- `public static void register(SnLibPlugin plugin, Sn selfCtx)` - registra el arbol `/snlib` sobre el contexto propio del bootstrap, con 5 subcomandos declarados:
  - `version` (permiso `snlib.admin.version`) - muestra la version de la libreria (`getPluginMeta().getVersion()`), el API level (`plugin.apiLevel()`) y la version del servidor (`Bukkit.getBukkitVersion()` mas la deteccion `SnVersion.MAJOR.MINOR[.PATCH]` y el flag Folia).
  - `plugins` (permiso `snlib.admin.plugins`) - lista los consumidores enganchados a SnLib, leidos del registro publico de contextos (`SnLib.context(plugin) != null`, excluyendo a SnLib mismo), ordenados y con contador; si no hay, "No consumers are hooked to SnLib.".
  - `integrations` (permiso `snlib.admin.integrations`) - lista los hooks de soft-dependency registrados via `SoftDependency.forEachRegistered` con formato `owner -> pluginName: active/inactive`, ordenados y con contador.
  - `iteminfo` (permiso `snlib.admin.iteminfo`) - solo jugadores; dumpea cada clave PDC del item en mano principal (aire rechazado con mensaje). Las claves cuyo namespace pertenece a un plugin cargado (mapa de plugins por nombre lowercased, que es exactamente el namespace de un `NamespacedKey(plugin, key)`) se leen via `TagIo` (la convencion de string-tags de la libreria); el resto cae a una lectura cruda `PersistentDataType.STRING`, y los tags no-string renderizan como `<non-string tag>`. Lineas ordenadas con contador.
  - `reload` (permiso `snlib.admin.reload`, usage `/snlib reload [plugin]`) - `argOptional("plugin", Args.oneOf(...))` cuyo set de opciones son SnLib mismo mas cada consumidor enganchado, ordenados.

**Contratos de reload**
- Sin argumentos (o con el propio nombre de SnLib): `selfCtx.reloadAll()` recarga EXCLUSIVAMENTE la superficie propia de la libreria (su `plugins/SnLib/config.yml`: las claves `debug` y `bstats`) y nunca toca ningun contexto consumidor. Confirma con "SnLib configuration reloaded (debug + bstats)." y recuerda: "A reload never reloads classes: updating SnLib.jar requires a server restart.".
- Con nombre de plugin: delega en el reload manager de ESE plugin (`targetCtx.reloadAll()`); confirma "Configuration of X reloaded.". Errores defensivos: "Plugin not found: X" si no resuelve a un `JavaPlugin`, "Plugin X is not hooked to SnLib." si no tiene contexto.
- Regla dura: un reload NUNCA recarga clases; actualizar SnLib.jar exige reinicio del servidor.

**Notas y gotchas**
- Como el arg opcional es `Args.oneOf(hookedConsumerNames)`, un nombre que no este en el set actual (SnLib + consumidores enganchados) se rechaza en el PARSING con `snlib.invalid-value` antes de llegar al handler: las ramas internas "Plugin not found" / "not hooked" son defensivas y casi inalcanzables por tipeo directo.
- El handler de reload lee el target con `context.raw(0)` (token crudo), no con el valor parseado, para tolerar la ausencia del opcional.
- Al no llamar `withoutDefaults()`, el arbol `/snlib` recibe ademas el `help` default inyectado (el `reload` default queda reemplazado por el declarado); en la practica el comando expone 6 entradas, 5 declaradas mas el help generado.
- En el codigo, el bloque Javadoc que describe el contrato de reload quedo colgado sobre `hookedConsumerNames()` (dos Javadoc consecutivos); es solo cosmetico, la semantica documentada es la del metodo `reload`.

### TODOs y limitaciones
No hay marcadores TODO/FIXME en los archivos del modulo. Limitaciones documentadas en codigo/Javadoc:
- `Args.greedy()` solo tiene sentido como ULTIMO argumento declarado de un subcomando; en otra posicion el join de tokens nunca se activa (solo el ultimo indice se trata como greedy).
- Cap fijo de 100 sugerencias (`SUGGESTION_CAP`) en las opciones respaldadas por listas (jugadores online, `oneOf`); no es configurable.
- `Args.offlinePlayerUuid()` no resuelve nombres fuera del cache local: la resolucion remota de perfiles le corresponde al consumidor via el scheduler async (decision deliberada para no bloquear el main thread).
- `withoutDefaults()` transfiere la obligacion al consumidor: sn-core declara `reload` y `help` obligatorios en todo root, la libreria no lo re-valida.
- Un reload nunca recarga clases: actualizar SnLib.jar requiere reinicio del servidor (contrato explicito de `/snlib reload`).
- Aliases dinamicos via `putIfAbsent`: si otro comando ya posee el alias en el CommandMap, el alias no queda operativo para el root (no se pisa, solo queda el WARN del registro dinamico).

---

## 14. Base de datos y Economia

Modulo dual de persistencia (SQLite/MySQL via HikariCP) y puente de economia de cada contexto consumidor (`Sn`). El contrato central de threading es que JDBC nunca toca el main thread: toda operacion corre en un executor daemon dedicado por plugin (`<plugin>-db`) y los resultados vuelven como `SnFuture`, cuya via de consumo canonica es `thenSync` (hop al main thread con guard de plugin habilitado). El pool Hikari se crea lazy en el primer uso sobre ese executor, asi que construir `SnDb` nunca abre una conexion. Sobre la base se montan `UpsertBuilder` (upsert de una fila con dialecto por backend) y `PlayerDataCache` (load-on-join, save-on-quit, flush ordenado en el teardown). Del lado de economia, `EconomyBridge` selecciona backends en orden de registro (Vault, luego command backend, luego custom) con la regla "Economy siempre main thread". HikariCP se shadea relocado a `com.sn.lib.libs.hikari`; los drivers SQLite y MySQL viajan sin relocar como copia unica server-wide.

### SnDb
`src/main/java/com/sn/lib/db/SnDb.java`

Modulo de base de datos SQLite/MySQL de un contexto consumidor, pooled con HikariCP. Cada instancia es propiedad de un plugin: el nombre del pool es `<plugin>-db` y el executor (fixed thread pool daemon; 1 hilo para SQLite, `max(1, poolSize)` para MySQL) pinea el context classloader de sus hilos al classloader del plugin consumidor y lo resetea al de `SnDb` cuando la tarea termina. El datasource Hikari se crea lazy con double-checked locking (`dataSourceLock`) en el primer `submit`, nunca en el constructor.

Interfaces anidadas publicas:

- `public interface SqlConsumer<T> { void accept(T value) throws SQLException; }` - callback SQL que consume un objeto JDBC.
- `public interface SqlFunction<T, R> { R apply(T value) throws SQLException; }` - callback SQL que mapea un objeto JDBC a un resultado.

Constantes: `SHUTDOWN_JOIN_SECONDS = 10L` (privada; timeout del join de shutdown, citada en el Javadoc de `shutdown()`).

Metodos publicos:

- `public SnDb(Sn ctx, DbConfig config)` - construye el modulo: decide la cantidad de hilos segun el tipo (SQLITE => 1), crea el executor con thread factory que pinea/resetea el context classloader y registra cada worker en un set para el reset forzado del shutdown.
- `public DbConfig config()` - settings de conexion parseados con los que corre el modulo.
- `public SnFuture<Void> bootstrap(Schema... schemas)` - crea todas las tablas async (un `CREATE TABLE IF NOT EXISTS` por schema, en un solo `Statement`). Marca la fase bootstrap (`bootstrapping = true`) hasta que el future completa; mientras esta pendiente, los joins en main thread se consideran fase de bootstrap y no logean WARN. La llamada estandar en enable es `bootstrap(...).orDisablePlugin()`.
- `public <R> SnFuture<R> query(String sql, SqlConsumer<PreparedStatement> binder, SqlFunction<ResultSet, R> mapper)` - corre una query preparada fuera del main thread y mapea su `ResultSet`.
- `public SnFuture<Integer> update(String sql, SqlConsumer<PreparedStatement> binder)` - corre un update preparado fuera del main thread; el valor es la cantidad de filas afectadas.
- `public SnFuture<Void> transaction(SqlConsumer<Connection> work)` - corre el trabajo dentro de una transaccion fuera del main thread: `setAutoCommit(false)`, commit al exito, rollback ante `SQLException | RuntimeException | Error`, y restaura el auto-commit previo en el `finally`.
- `public UpsertBuilder upsert(String table)` - builder de upsert de una fila consciente del dialecto para la tabla dada.
- `public <T> PlayerDataCache<T> playerCache(BiFunction<SnDb, UUID, T> loader, PlayerDataCache.Saver<T> saver)` - crea un cache per-player respaldado por esta base (load-on-join via listener compartido, save-on-quit cuando esta dirty) y lo registra en la lista interna para el flush ordenado. El loader corre en el pool async del plugin owner, nunca en el executor de la base, asi puede joinear queries de este modulo sin deadlock.
- `public void flushPlayerCaches()` - guarda toda entrada dirty de cada cache creado via `playerCache` y joinea las escrituras encoladas (barrier). Teardown ordenado: corre justo antes de `shutdown()` para que ninguna escritura se pierda con el cierre del pool.
- `public void shutdown()` - teardown del modulo, idempotente (`AtomicBoolean closed`): rechaza operaciones nuevas, joinea el trabajo pendiente hasta 10 segundos (`awaitTermination`), y si no termino fuerza `shutdownNow()` con WARN `"Pool <nombre> no termino en 10s; shutdownNow() forzado"` y resetea el context classloader de cada worker al de `SnDb` para que una query colgada nunca retenga (pinee) el classloader del plugin consumidor. El pool Hikari cierra al final, bajo `dataSourceLock`.

Metodos package-private (infraestructura del modulo):

- `boolean inBootstrap()` - true mientras un `bootstrap` de enable sigue pendiente; lo consulta `SnFuture.warnIfMainThreadJoin()`.
- `SnFuture<Void> fence()` - write barrier: completa cuando el executor dreno toda tarea encolada antes que ella. Exacto para el executor single-thread de SQLite; para un pool multi-thread de MySQL es best-effort y `shutdown()` joinea los rezagados. Completa inmediato si el executor ya rechazo el submit (`RejectedExecutionException`).

#### Logica interna

- `submit(...)`: si `closed` esta seteado, completa exceptionally con `IllegalStateException("SnDb cerrado: <pool>")` sin encolar. Cada tarea toma una conexion del pool con try-with-resources y completa el future con el resultado o con cualquier `Throwable`.
- `createDataSource()` (perfil SQLite): crea los directorios padre del archivo, driver `org.sqlite.JDBC`, URL `jdbc:sqlite:<absolutePath>`, `maximumPoolSize=1`, y propiedades de datasource `busy_timeout=5000` y `journal_mode=WAL` aplicadas en el primer connect.
- `createDataSource()` (perfil MySQL): driver `com.mysql.cj.jdbc.Driver`, URL `jdbc:mysql://host:port/database?useSSL=<ssl>&allowPublicKeyRetrieval=true&characterEncoding=utf8`, usuario/password, `maximumPoolSize=max(1, poolSize)` (default 4), y cache de prepared statements (`cachePrepStmts=true`, `prepStmtCacheSize=250`, `prepStmtCacheSqlLimit=2048`).

#### Notas y gotchas

- El executor pinea el context classloader al del plugin consumidor para que los drivers JDBC (que usan el TCCL) resuelvan clases contra el plugin correcto; el reset en el `finally` de cada tarea y el reset forzado del shutdown evitan leaks del classloader tras un disable.
- SQLite queda clavado en 1 conexion sin importar `pool-size` del config; el paralelismo real solo existe con MySQL.
- `dataSource()` tira `IllegalStateException` si se intenta crear el pool con el modulo ya cerrado (carrera submit/shutdown).

### DbConfig
`src/main/java/com/sn/lib/db/DbConfig.java`

Settings de conexion de la base de un consumidor, parseados de la seccion `database` de su config principal. Claves reconocidas: `type` (sqlite o mysql, default sqlite), `file` (path SQLite relativo al data folder, o absoluto), y para MySQL `host`, `port`, `database`, `username`, `password`, `pool-size` y `ssl`. Seccion ausente o `type` desconocido caen a SQLite en `<dataFolder>/database.db`.

Enum publico:

- `public enum Type { SQLITE, MYSQL }` - backends soportados.

Constantes (privadas, definen los defaults): `DEFAULT_SQLITE_FILE = "database.db"`, `DEFAULT_MYSQL_PORT = 3306`, `DEFAULT_MYSQL_POOL_SIZE = 4`.

Metodos publicos:

- `public static DbConfig load(JavaPlugin plugin, @Nullable ConfigurationSection section)` - parsea la seccion `database`; una seccion null da los defaults SQLite. Un `type` desconocido logea un WARN (`"database.type invalido: '<raw>', usando sqlite"`) y cae a SQLite. El default de `database` (MySQL) es el nombre del plugin en minusculas; `username` default `root`, `password` default vacio, `pool-size` se clampa a minimo 1, `ssl` default false.
- `public Type type()` - tipo de backend.
- `public File sqliteFile()` - archivo SQLite resuelto; solo significativo cuando `type()` es SQLITE. Si el path del config es absoluto se usa tal cual, si no se resuelve contra el data folder del plugin.
- `public String host()` - host MySQL (default `localhost`).
- `public int port()` - puerto MySQL (default 3306).
- `public String database()` - nombre de la base MySQL.
- `public String username()` - usuario MySQL.
- `public String password()` - password MySQL.
- `public int poolSize()` - tamano del pool MySQL; SQLite queda siempre pineado a una sola conexion.
- `public boolean ssl()` - si la conexion MySQL usa SSL.

### Schema
`src/main/java/com/sn/lib/db/Schema.java`

Definicion declarativa de tabla consumida por `SnDb.bootstrap`: cada schema rinde a un statement idempotente `CREATE TABLE IF NOT EXISTS`.

- `public static Schema of(String table, String... columnDefs)` - schema desde el nombre de tabla mas las definiciones de columnas; ejemplo del Javadoc: `Schema.of("players", "uuid VARCHAR(36) PRIMARY KEY", "coins BIGINT NOT NULL")`.
- `public static Schema raw(String table, String createSql)` - schema desde SQL crudo para definiciones especificas de dialecto; el SQL debe seguir siendo idempotente (`CREATE TABLE IF NOT EXISTS ...`), no se valida.
- `public String table()` - nombre de la tabla.
- `public String createSql()` - statement que ejecuta `SnDb.bootstrap`.

### UpsertBuilder
`src/main/java/com/sn/lib/db/UpsertBuilder.java`

Upsert de una sola fila consciente del dialecto, construido via `SnDb.upsert(String)`. `keys` declara las columnas de conflicto y `set` las columnas actualizables; ambos son repetibles y todos los valores bindean posicionalmente con `setObject` (primero keys, despues sets). Los nombres de tabla y columna son identificadores del lado del codigo (nunca input del usuario) y aun asi se validan contra `[A-Za-z_][A-Za-z0-9_]*` como hard stop (`IllegalArgumentException "Identificador SQL invalido: '<nombre>'"`).

- `public UpsertBuilder keys(String column, Object value)` - agrega una columna clave de conflicto con su valor; repetible.
- `public UpsertBuilder set(String column, Object value)` - agrega una columna actualizable con su valor; repetible.
- `public SnFuture<Integer> run()` - rinde el statement del dialecto y lo corre fuera del main thread via `SnDb.update`; el valor es la cantidad de filas afectadas. Tira `IllegalStateException` si no se declaro ninguna columna con `keys()` (mensaje: `"upsert(<tabla>) sin keys(): declara al menos una columna clave"`).

#### Logica interna (dialectos)

- SQLite: `INSERT INTO t (...) VALUES (...) ON CONFLICT(keys) DO UPDATE SET col=excluded.col`; sin columnas `set` degenera en `... DO NOTHING`. Requiere una constraint UNIQUE o PRIMARY KEY sobre las columnas clave.
- MySQL: `INSERT INTO t (...) VALUES (...) ON DUPLICATE KEY UPDATE col=VALUES(col)`; se apoya en los indices unicos propios de la tabla. Sin columnas `set`, como MySQL exige al menos una asignacion, emite un refresh no-op de la primera clave: `key=VALUES(key)` (comentario textual del codigo: "MySQL exige al menos una asignacion: refresco no-op de la primera clave").

### PlayerDataCache
`src/main/java/com/sn/lib/db/PlayerDataCache.java`

Cache de datos per-player atado a un `SnDb`, creado via `SnDb.playerCache` (constructor package-private). Ciclo de vida: el listener compartido de `PlayerJoinEvent` dispara `load` para cada cache registrado de cada owner, y el listener de quit cleanup (`QuitCleanupListener`) guarda la entrada si esta dirty y la remueve. El loader corre en el pool async del plugin owner (nunca en el executor de la base, asi puede joinear queries del modulo) y su resultado se instala en el main thread; el saver corre en el thread llamador y se espera que encole escrituras async como `SnDb.upsert`.

Interfaz anidada publica:

- `public interface Saver<T> { void save(SnDb db, UUID uuid, T value); }` - persiste el valor de un jugador; tipicamente un `SnDb.upsert` encolado off-main.

Metodos publicos:

- `public static Listener joinListener()` - listener compartido de `PlayerJoinEvent`, propiedad de SnLib, que dispara load-on-join para todos los caches registrados de todos los owners (via el `TenantRegistry` estatico `CACHES`). El `registerEvents` ocurre una unica vez en el bootstrap de SnLibPlugin.
- `public @Nullable T get(UUID uuid)` - valor cacheado; null mientras no esta cargado (o cuando el loader no devolvio datos).
- `public void load(UUID uuid)` - carga el valor del jugador async y lo instala en el main thread. No-op si el contexto esta en shutdown, el valor ya esta cacheado, o ya hay una carga en vuelo (dedup via `pendingLoads.putIfAbsent`).
- `public void markDirty(UUID uuid)` - marca el valor cargado del jugador como pendiente de persistencia; no-op mientras no esta cargado (`data.containsKey`).
- `public void invalidate(UUID uuid)` - descarta la entrada del jugador SIN guardar y mata cualquier carga en vuelo (remueve el ticket de `pendingLoads`, el dato y la marca dirty).
- `public SnFuture<Void> saveAll()` - guarda toda entrada dirty a traves del saver (iterando una copia del set dirty, con `dirty.remove` como claim atomico) y devuelve un barrier future (`db.fence()`) que completa cuando drenaron las escrituras encoladas hasta ese punto; el teardown ordenado lo joinea antes de `SnDb.shutdown()`.

Metodos package-private:

- `void unload(UUID uuid)` - cleanup de quit/kick registrado en `QuitCleanupListener`: remueve el ticket pendiente, saca el valor y, si estaba dirty y no era null, lo guarda. Idempotente, porque un jugador kickeado dispara kick y quit.

#### Logica interna (mutation-sequence guard)

`pendingLoads: ConcurrentHashMap<UUID, Long>` mas un `AtomicLong sequence` forman el guard de secuencia de mutacion: cada `load` toma un ticket fresco (`sequence.incrementAndGet()`) y lo mapea con `putIfAbsent` (un ticket ya mapeado dedupea cargas concurrentes en un solo intento en vuelo). El resultado async solo se instala en el main thread si `pendingLoads.remove(uuid, ticket)` remueve SU propio ticket; cualquier mutacion intermedia (invalidate, unload por quit) dropea el ticket, asi un dato que ya estaba en vuelo jamas puede pisar el estado posterior. Si el load falla, el `whenComplete` remueve el ticket para no bloquear reintentos. Fallos del saver se capturan y logean WARN `"Save de datos de jugador fallo (<uuid>): <t>"` sin propagar.

#### Notas y gotchas

- `CACHES` es un static server-wide justificado por Javadoc: caches keyeados por plugin owner, resueltos por el join listener compartido y barridos por clave completa cuando el owner se deshabilita.
- Un loader que devuelve null no instala nada: el jugador queda "no cargado" y `markDirty` sera no-op para el.
- El barrier de `saveAll()` es exacto en SQLite (executor de 1 hilo) y best-effort en MySQL multi-hilo; el shutdown de `SnDb` joinea los rezagados.

### SnFuture
`src/main/java/com/sn/lib/db/SnFuture.java`

Resultado de una operacion asincronica de base de datos; envuelve un `CompletableFuture` (campo `delegate`, package-private) junto al contexto `Sn` y el `SnDb` de origen. Constructor package-private: solo el modulo db crea instancias.

Constantes: `JOIN_WARN_FRAMES = 5` (privada; cantidad de frames del stack incluidos en el WARN de join).

- `public SnFuture<T> thenSync(Consumer<T> consumer)` - consume el valor en el main thread via el scheduler del owner; el hop se saltea cuando el plugin owner ya esta deshabilitado (guard is-enabled del scheduler), y un future fallido logea un WARN en vez de llegar al consumer. Devuelve `this` (encadenable).
- `public SnFuture<T> exceptionally(Consumer<Throwable> handler)` - observa un fallo con los wrappers de completacion (`CompletionException` / `ExecutionException`) desenvueltos hasta la causa real.
- `public T join()` - bloquea hasta que el valor este disponible y lo devuelve. Pensado SOLO para el flush de shutdown y el bootstrap de enable: cualquier otro join en el main thread (future no completado, fuera de `ctx.isShuttingDown()` y de `db.inBootstrap()`) logea un WARN `"SnFuture.join() en el main thread fuera de shutdown/bootstrap:"` con los primeros 5 frames llamadores.
- `public SnFuture<T> orDisablePlugin()` - deshabilita el plugin owner cuando este future falla; el gate estandar de `SnDb.bootstrap`. Logea SEVERE `"Operacion critica de base de datos fallo; deshabilitando <plugin>: <causa>"`. Si el fallo llega en el main thread deshabilita inline; si no, agenda el disable con `scheduler().sync(...)`, y si ese scheduling tira `IllegalPluginAccessException` (plugin ya deshabilitado durante el scheduling) logea WARN `"Disable diferido descartado: plugin ya deshabilitado durante el scheduling"` y descarta.

#### Notas y gotchas

- El WARN de join se suprime en cuatro casos: future ya completado, thread no-main, contexto en shutdown, o fase de bootstrap del `SnDb` de origen. El stack se recorta empezando en el frame 3 para saltear los frames internos de `getStackTrace`/`join`.

### EconomyBridge
`src/main/java/com/sn/lib/economy/EconomyBridge.java`

Servicio de economia de un contexto consumidor, accesible via `sn.economy()`. Las operaciones resuelven el PRIMER backend disponible en orden de registro (`LinkedHashMap`): Vault (registrado en el constructor), despues el command backend configurado con `useCommandBackend`, despues cualquier `Backend` custom via `registerBackend`. Sin backend disponible, toda operacion warnea UNA vez y reporta fallo (balance `0`, futures `false`). El acceso a economia es main-thread only: `getBalance(Player)` debe correr en el main thread (fuera de el devuelve `0` con un WARN unico), mientras que las escrituras pueden llamarse desde cualquier thread porque cada backend hace el hop al main por su cuenta.

Interfaz anidada publica:

- `public interface Backend` - backend de economia enchufable. Contrato: acceso a Economy siempre en main thread; `getBalance` solo se invoca en main, y las escrituras invocadas off-main deben hopear ellas mismas, como hacen los backends built-in.
  - `double getBalance(OfflinePlayer player)` - balance actual; main-thread only.
  - `CompletableFuture<Boolean> give(OfflinePlayer player, double amount)` - deposita; el future completa con el exito real.
  - `CompletableFuture<Boolean> tryTake(OfflinePlayer player, double amount)` - retira solo si el jugador puede pagarlo; el future completa con el exito real del retiro.
  - `default boolean available()` - true cuando el backend puede servir operaciones ahora mismo (default true).

Constantes: `VAULT = "vault"`, `COMMAND = "command"` (privadas; nombres de registro de los backends built-in).

Metodos publicos:

- `public EconomyBridge(Sn ctx)` - crea el puente y registra el backend Vault. `VaultBackend` es la clase hook aislada: su constructor linkea contra la API de Vault, asi que con Vault ausente la instanciacion tira un linkage error que se captura aca (`catch (Throwable)`, nunca propaga) y el puente arranca sin ese backend (log de debug, no WARN).
- `public double getBalance(Player player)` - balance actual a traves del backend activo; main-thread only. Fuera del main thread devuelve `0` con un WARN unico (`"getBalance llamado fuera del main thread; devolviendo 0 (Economy siempre main thread)"`); sin backend disponible, `0` con el WARN de no-backend.
- `public CompletableFuture<Boolean> give(Player player, double amount)` - deposita `amount`. El future completa con el exito real; false ante monto invalido (no finito o no positivo, logeado solo en debug) o sin backend disponible.
- `public CompletableFuture<Boolean> tryTake(Player player, double amount)` - retira `amount` solo si es pagable. El future completa con el exito REAL del retiro; false ante monto invalido, fondos insuficientes o sin backend.
- `public synchronized void registerBackend(String name, Backend backend)` - registra (o reemplaza) un backend bajo `name` (lowercased). La seleccion camina los backends en orden de primer registro, asi Vault mantiene prioridad, el command backend sigue y los custom van despues salvo que reemplacen uno de esos nombres.
- `public void useCommandBackend(String giveCommand, String takeCommand, String balancePlaceholder)` - configura el backend fallback de dispatch de comandos. Los templates aceptan los tokens `%player%` y `%amount%`; `balancePlaceholder` es el placeholder PAPI que reporta el balance del jugador (usado por `tryTake` para verificar pagabilidad y el resultado post-take).
- `public boolean available()` - true cuando al menos un backend registrado esta disponible.

#### Notas y gotchas

- El WARN de "sin backend" se emite una sola vez por instancia (`AtomicBoolean warnedNoBackend`): `"No hay backend de economia disponible: instala Vault o configura useCommandBackend(...); las operaciones devuelven false"`. Igual el WARN de off-main (`warnedOffMain`).
- `active()` es `synchronized` y re-evalua `available()` de cada backend en cada operacion, asi un Vault que aparece tarde (o se cae) cambia la seleccion dinamicamente.

### VaultBackend (internal)
`src/main/java/com/sn/lib/economy/internal/VaultBackend.java`

Backend de economia respaldado por Vault de un contexto consumidor. El provider de `Economy` se resuelve a traves de un `SoftDependency<Economy>` per-owner sobre el `RegisteredServiceProvider`, asi un Vault deshabilitado nunca filtra un linkage error y el disable del consumidor libera el hook. Es la clase hook AISLADA: solo linkea cuando las clases de la API de Vault estan presentes, por eso `EconomyBridge` la instancia bajo `catch (Throwable)` y un server sin Vault simplemente corre sin este backend. Toda escritura hopea al main thread y reporta el resultado real de `EconomyResponse.transactionSuccess()`.

- `public VaultBackend(Sn ctx)` - crea el hook: `SoftDependency.of(ctx.plugin(), "Vault", VaultBackend::resolveProvider)`, donde el resolver busca la `RegisteredServiceProvider<Economy>` en el `ServicesManager` de Bukkit.
- `public boolean available()` - true cuando el provider de Economy resuelve (con la re-resolucion on-use descripta abajo).
- `public double getBalance(OfflinePlayer player)` - balance via `economy.getBalance(player)`; `0` si no hay provider o si Vault tira cualquier `Throwable` (WARN `"Vault fallo al leer el balance: <t>"`).
- `public CompletableFuture<Boolean> give(OfflinePlayer player, double amount)` - `depositPlayer` en el main thread; el resultado es `transactionSuccess()`. False si no hay provider.
- `public CompletableFuture<Boolean> tryTake(OfflinePlayer player, double amount)` - en el main thread: false si no hay provider o `!economy.has(player, amount)`; si alcanza, `withdrawPlayer` y devuelve `transactionSuccess()`.

#### Logica interna

- Re-resolucion on-use (`economy()`): el provider de Economy puede registrar el servicio DESPUES del primer acceso (Vault ya enabled, servicio tardio); un miss cacheado por el `SoftDependency` se invalida (`vault.invalidate()`) y se re-resuelve en el siguiente uso en vez de quedar nulo toda la sesion (comentario textual del codigo).
- `onMain(...)`: si ya esta en main thread ejecuta inline; si no, agenda con `ctx.scheduler().sync(...)` y, si el scheduling tira `IllegalPluginAccessException` (owner ya deshabilitado), completa el future con `false`.
- `runSafe(...)`: cualquier `Throwable` de la operacion se logea como WARN (`"Operacion de economia Vault fallo: <t>"`) y devuelve `false`; nunca propaga al caller.

### CommandBackend (internal)
`src/main/java/com/sn/lib/economy/internal/CommandBackend.java`

Backend de economia por dispatch de comandos para servers sin Vault. Give y take corren comandos de consola construidos desde los templates configurados (tokens `%player%` y `%amount%`). `tryTake` lee el balance a traves del placeholder PAPI configurado en el main thread, rechaza retiros impagables, y verifica el balance post-take contra un epsilon para que el future complete con el exito real de la operacion. Toda operacion hopea al main thread (PAPI y el dispatch de comandos son main-thread only).

Constantes: `EPSILON = 1.0E-3` (privada; tolerancia de redondeo double al comparar balances antes y despues de un take), `SECTION = '§'` (privada; marcador de codigo de color a strippear).

- `public CommandBackend(Sn ctx, String giveCommand, String takeCommand, String balancePlaceholder)` - construye el backend con los dos templates de comando y el placeholder de balance.
- `public double getBalance(OfflinePlayer player)` - balance via el placeholder PAPI; `0` cuando la lectura da NaN (ilegible).
- `public CompletableFuture<Boolean> give(OfflinePlayer player, double amount)` - despacha el comando give como consola en el main thread; el resultado es el booleano de `Bukkit.dispatchCommand`.
- `public CompletableFuture<Boolean> tryTake(OfflinePlayer player, double amount)` - en el main thread: lee el balance previo (false si es NaN o `before + EPSILON < amount`), despacha el comando take (false si el dispatch falla), relee el balance y devuelve true solo si `after <= before - amount + EPSILON` (verificacion post-take: el exito reportado es el real, no el del dispatch).

#### Logica interna

- `readBalance(...)`: resuelve el placeholder via `ctx.papi().apply(online, balancePlaceholder)` (castea a `Player` online si puede). Devuelve NaN cuando el resultado es null o todavia contiene `%` (PAPI ausente o token sin resolver), o cuando `NumberFormatter.parseFormatted` tira `NumberFormatException` tras strippear decoraciones. El WARN de ilegible se emite una sola vez (`AtomicBoolean warnedUnreadable`): `"Balance ilegible via '<placeholder>' (resultado: '<resolved>'); el command backend no puede verificar balances"`.
- `stripDecorations(...)`: dropea codigos de color (caracter `§` mas el siguiente) y decoraciones de moneda, conservando digitos, letras (sufijos tipo k/M), `.`, `,` y `-`.
- `dispatch(...)`: si el jugador no tiene nombre conocido (`getName() == null`) logea WARN `"Jugador sin nombre conocido; comando de economia omitido"` y devuelve false. Reemplaza `%player%` y `%amount%` (monto formateado con `BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString()`, nunca notacion cientifica) y despacha como consola; cualquier `Throwable` del dispatch logea WARN y devuelve false.
- `onMain(...)` / `runSafe(...)`: mismo patron que `VaultBackend` (hop via scheduler del owner, `IllegalPluginAccessException` => false, `Throwable` => WARN `"Operacion del command backend fallo: <t>"` y false).

#### Notas y gotchas

- La verificacion post-take existe porque el exito del dispatch de un comando NO garantiza que el plugin de economia haya descontado de verdad (comando mal configurado, jugador sin cuenta, etc.); el epsilon absorbe el redondeo de doubles.
- Si el placeholder de balance no resuelve (NaN), `tryTake` siempre devuelve false: sin lectura de balance no hay forma de verificar pagabilidad ni resultado.

### TODOs y limitaciones

No existen marcadores TODO/FIXME/XXX/HACK en ningun archivo del alcance. Limitaciones documentadas en el propio codigo:

- `SnDb.fence()` (y por lo tanto el barrier de `PlayerDataCache.saveAll()` / `flushPlayerCaches()`) es exacto solo con el executor single-thread de SQLite; con un pool MySQL multi-thread es best-effort y depende de que `shutdown()` joinee los rezagados.
- SQLite queda siempre pineado a `maximumPoolSize=1`; la clave `pool-size` del config solo afecta a MySQL.
- La forma SQLite del upsert (`ON CONFLICT(keys)`) requiere una constraint UNIQUE o PRIMARY KEY sobre las columnas clave; la forma MySQL depende de los indices unicos de la tabla (las keys declaradas no se usan en la clausula `ON DUPLICATE KEY UPDATE`).
- `CommandBackend` no puede verificar balances si el placeholder PAPI configurado no resuelve a un numero legible (WARN unico y `tryTake` devuelve false siempre).
- `EconomyBridge.getBalance` fuera del main thread no lanza: devuelve `0` silenciosamente tras el primer WARN, lo que puede enmascarar bugs de threading en el consumidor.

---

## 15. BossBars, Hologramas, Leaderboards y Discord

Cuatro servicios de contexto de consumidor, accesibles como `sn.bossbars()`, `sn.holograms()`, `sn.leaderboards()` y `sn.discord()`. Los tres primeros registran sus entradas en un `TenantRegistry` estatico keyed por plugin dueño: un disable del owner barre bars, hologramas y refresh tasks aunque el plugin nunca haya limpiado, y el teardown del contexto llama `hideAll()` / `deleteAll()` / `drain()` segun corresponda. BossBars usa Adventure puro (cero packets), Hologramas usa entidades `TextDisplay` reales (1.19.4+, cero NMS) con marca PDC y purga de huerfanas, Leaderboards cachea rankings inmutables detras de una referencia volatile con lecturas lock-free aptas para PAPI, y Discord despacha webhooks por cola FIFO fuera del main thread con el `HttpClient` del JDK (cero dependencias externas).

### BossBarUtil

`src/main/java/com/sn/lib/bossbar/BossBarUtil.java`

Servicio de boss bars por contexto. Las barras son instancias Adventure `BossBar` mostradas por jugador via la Audience API. Los titulos se renderizan por el pipeline SnText (`[rgb]` y `[center]` incluidos). Un jugador que se desconecta o es kickeado se remueve automaticamente de todas las barras del contexto (via `QuitCleanupListener.register(ctx.plugin(), this::dropViewer)` en el constructor). Operaciones sobre un id desconocido logean UN solo WARN por id (`"Bossbar desconocida '<id>': la operacion se ignora (falta create(\"<id>\").build())"`) y no hacen nada.

Metodos publicos:

- `public BossBarUtil(Sn ctx)` - Constructor; registra el cleanup de quit/kick del contexto.
- `public Builder create(String id)` - Empieza la definicion de una barra bajo `id`; nada se registra hasta `Builder.build()`.
- `public void show(Player viewer, String id)` - Muestra la barra al viewer (`viewer.showBossBar`); id desconocido WARN una vez y no-op.
- `public void hide(Player viewer, String id)` - Oculta la barra solo para ese viewer; el resto la sigue viendo.
- `public void setText(String id, String text)` - Re-renderiza el titulo de la barra por el pipeline SnText (`SnText.color`).
- `public void setProgress(String id, float progress)` - Setea el progreso, clampeado a 0..1; un timer corriendo lo pisa al proximo tick.
- `public void timer(String id, Duration duration, boolean countdown)` - Anima el progreso linealmente a lo largo de `duration`: con `countdown` true drena de 1 a 0, si no llena de 0 a 1. Un timer nuevo reemplaza al anterior; al vencer la duracion el timer se detiene y la barra queda visible en su progreso final hasta que se la oculte.
- `public void cancelTimer(String id)` - Detiene el timer de la barra si hay uno; el progreso actual se conserva.
- `public void remove(String id)` - Oculta la barra a todos los viewers, detiene su timer y desregistra el id (tambien del tenant registry).
- `public void hideAll()` - Oculta todas las barras del contexto a todos los viewers y detiene sus timers; las barras quedan registradas y pueden re-mostrarse. El teardown del contexto la llama antes de liberar las registraciones del owner.

#### BossBarUtil.Builder

Builder de definicion de barra devuelto por `create(String)`. Defaults: texto `""`, progreso `1.0f`, color `BossBar.Color.WHITE`, overlay `BossBar.Overlay.PROGRESS`.

- `public Builder text(String text)` - Titulo de la barra, renderizado por SnText (`[rgb]` incluido); null se normaliza a `""`.
- `public Builder progress(float progress)` - Progreso inicial, clampeado a 0..1 (default 1).
- `public Builder color(BossBar.Color color)` - Color de la barra (default WHITE); null conserva el default.
- `public Builder overlay(BossBar.Overlay overlay)` - Overlay de la barra (default PROGRESS); null conserva el default.
- `public BossBar build()` - Construye y registra la barra bajo su id, reemplazando (y ocultando/barriendo) cualquier barra previa con el mismo id. La barra arranca sin viewers; usar `show`.

Logica interna:

- `TenantRegistry<BarEntry> BARS` estatico con sweep `BossBarUtil::sweep`: al deshabilitarse el plugin dueño, cada entry se barre (timer cancelado, todos los viewers removidos).
- El timer corre con `ctx.scheduler().timer(1L, 2L, ...)` (delay 1 tick, periodo 2 ticks) y calcula la fraccion contra reloj de pared (`System.currentTimeMillis()`), no contra ticks; la duracion minima efectiva es 50 ms.
- `dropViewer(UUID)`: si el jugador sigue resoluble por `Bukkit.getPlayer`, usa `player.hideBossBar`; si no, itera los `viewers()` de cada barra y hace `removeViewer` por UUID matching.
- `viewersOf(BossBar)` toma un snapshot de los viewers como lista de `Audience` para poder remover mientras itera sin ConcurrentModification.
- `cancelTimer(BarEntry)` traga `Throwable` del cancel: durante shutdown el scheduler puede ya no existir.

Notas y gotchas:

- `setProgress` sobre una barra con timer activo es efimero: el timer re-escribe el progreso en su proximo tick (documentado en el Javadoc del metodo).
- Cero packets: todo pasa por la Audience API de Adventure/Paper.
- El WARN de id desconocido se emite una sola vez por id (set `warnedIds`), para no spamear consola en loops.

### HologramUtil

`src/main/java/com/sn/lib/hologram/HologramUtil.java`

Servicio de hologramas por contexto. Los hologramas son entidades `TextDisplay` reales (API 1.19.4+, cero NMS y cero packets). Cada entidad lleva la marca PDC `snlib:snlib_hologram` (clave `NamespacedKey("snlib", "snlib_hologram")`, tipo `PersistentDataType.STRING`) con valor `<NombreDelPlugin>:<id>`. Una entidad marcada cuyo marker no reclama ninguna registracion viva es huerfana (run anterior, crash, o un delete que no pudo alcanzar un chunk descargado) y la purga interna la elimina; las entidades de markers vivos re-bindean su instancia fresca despues de un reload de chunk. Las lineas se renderizan por SnText con PAPI resuelto serverside (viewer null). Id desconocido WARN una vez (`"Holograma desconocido '<id>': la operacion se ignora (falta spawn(\"<id>\", ...))"`).

Metodos publicos:

- `public HologramUtil(Sn ctx)` - Constructor.
- `public void spawn(String id, Location location, List<String> lines)` - Spawnea el holograma `id` en la location, reemplazando (delete previo) un holograma anterior con el mismo id. La entidad se crea con `setPersistent(true)`, billboard CENTER por default, marca PDC y texto ya renderizado. Location con world null logea WARN (`"Holograma '<id>': location invalida, spawn ignorado"`) y no hace nada. El modelo esperado es re-spawnear en cada enable: la entidad del run anterior se purga como huerfana cuando su chunk carga.
- `public void setLines(String id, List<String> lines)` - Reemplaza las lineas y re-renderiza de inmediato.
- `public void setBillboard(String id, Display.Billboard billboard)` - Modo billboard de la entidad (default `CENTER`, el comportamiento clasico de holograma); null no-op.
- `public void refreshEvery(String id, long intervalTicks)` - Re-renderiza el holograma cada `intervalTicks` (tokens PAPI incluidos); un intervalo de 0 o menos cancela el refresh. Un task handle por holograma; un intervalo nuevo reemplaza el task previo.
- `public void showTo(Player viewer, String id)` - Vuelve a hacer visible el holograma para ese viewer (`viewer.showEntity(plugin, display)`); los hologramas son visibles por defecto.
- `public void hideFrom(Player viewer, String id)` - Oculta el holograma solo para ese viewer (`viewer.hideEntity`). La visibilidad per-viewer NO es persistente: se resetea cuando la entidad re-bindea tras un chunk reload o un re-spawn.
- `public void delete(String id)` - Elimina el holograma: cancela su refresh task y remueve la entidad. Si la entidad esta en un chunk descargado no se puede tocar aca; como su marker deja de estar vivo, la purga de huerfanas remueve la copia persistida en la proxima carga del chunk.
- `public void deleteAll()` - Elimina todos los hologramas del contexto; el teardown del contexto la llama.
- `public static boolean adopt(TextDisplay display)` - Contrato de adopcion al cargar chunks, usado por la purga de huerfanas (listener interno y scan de arranque). Un display SIN el marker de la lib es ajeno y se deja en paz (retorna true). Un display marcado cuyo marker reclama una registracion viva re-bindea como instancia fresca de ese holograma (mismo UUID tras chunk reload, o un entry nunca bindeado) y recibe de nuevo su texto actual (`lastText`) y billboard; retorna true. Retorna **false** cuando el display lleva marker de la lib que ningun holograma vivo reclama, o que ya esta bindeado a OTRA entidad (duplicado stale): el caller debe remover esas huerfanas.

Logica interna:

- `TenantRegistry<HologramEntry> HOLOGRAMS` estatico con sweep `HologramUtil::sweep`: cancela el refresh task y remueve la entidad si es alcanzable (traga `Throwable` si la entidad ya es invalida o su chunk esta descargado; la purga de huerfanas cubre ese caso).
- Render: cada linea pasa por `SnText.color(SnText.normalizePapiOutput(ctx.papi().apply(null, line)))` y las lineas se unen con `Component.join(JoinConfiguration.newlines(), ...)` en un unico `TextDisplay` multilinea. PAPI se resuelve con viewer null (serverside).
- `findByMarker(String)` recorre el tenant registry completo (`HOLOGRAMS.forEachOwner`) buscando el entry cuyo `marker` coincida; corta al primer match.
- `HologramEntry` guarda `marker` final y campos volatile: `rawLines`, `billboard`, `entity`, `lastText` (Component ya renderizado, re-aplicado al adoptar) y `refreshTask`.
- `copyOf(lines)` normaliza null a `List.of()` y hace copia inmutable (`List.copyOf`).

Notas y gotchas:

- El PDC marker hace que el sistema sobreviva crashes: no hay archivo de estado, la fuente de verdad es la propia entidad persistida mas el set de registraciones vivas en memoria.
- `adopt` es estatico y cruza contextos: resuelve contra el registry global, no contra un `HologramUtil` particular.
- El caso "bindeado a otra entidad" (retorno false) cubre duplicados stale: si ya hay una entidad viva trackeada con otro UUID para ese marker, la recien cargada es una copia vieja y se purga.

### HologramChunkListener

`src/main/java/com/sn/lib/hologram/internal/HologramChunkListener.java`

Purga compartida de entidades de holograma huerfanas, propiedad de SnLib. Inscripta en el ListenerHub; el `registerEvents` ocurre UNICAMENTE en el bootstrap de SnLibPlugin (una sola instancia server-wide, no una por consumidor).

- `@EventHandler public void onEntitiesLoad(EntitiesLoadEvent event)` - Escucha `EntitiesLoadEvent`, la señal de carga de chunk que realmente trae las entidades del chunk (ChunkLoadEvent dispara ANTES de que se adjunten); delega en `purge(event.getEntities())`.
- `public static int purge(Collection<Entity> entities)` - Purga huerfanas de holograma entre las entidades dadas: todo `TextDisplay` para el que `HologramUtil.adopt` devuelve false se remueve (`display.remove()`). Retorna cuantas removio. Las reclamadas re-bindean su instancia fresca via `adopt`.
- `public static int purgeLoadedWorlds()` - Corre la misma pasada sobre todos los `TextDisplay` de todos los mundos cargados; retorna cuantas removio. El bootstrap la ejecuta DIFERIDA al primer tick despues de habilitarse, para que los mundos esten cargados y todo consumidor haya tenido su chance de registrar sus hologramas (si corriera antes, purgaria hologramas legitimos aun no re-spawneados).

### LeaderboardCache

`src/main/java/com/sn/lib/leaderboard/LeaderboardCache.java`

Cache de leaderboards por contexto. Cada board empareja un id con una query asincronica disparada a intervalo fijo: el supplier corre en el MAIN thread y debe solo DESPACHAR el trabajo async (una query de `SnDb` ya lo hace), y el resultado fresco se pliega en un `Snapshot` inmutable swapeado detras de una referencia volatile. Las lecturas (`getTop`, `positionOf`, `valueOf`) son lookups de cache lock-free, seguras para resolvers de PlaceholderAPI bajo su contrato cache-only. Constante interna relevante: `MIN_REFRESH_TICKS = 20L` (piso de refresh de un segundo: una query de leaderboard nunca es un loop per-tick).

Metodos publicos:

- `public LeaderboardCache(Sn ctx)` - Constructor.
- `public void register(String id, Duration refreshInterval, Supplier<SnFuture<List<Entry>>> query)` - Registra el board y arma su refresh periodico (primera corrida al proximo tick, `timer(1L, periodTicks, ...)`), reemplazando (y barriendo) cualquier board previo bajo el mismo id. El intervalo se convierte a ticks redondeando hacia arriba (`(millis + 49) / 50`) y se clampea a un minimo de 20 ticks (1 segundo); `refreshInterval` null cuenta como 0 y cae al minimo. Hasta que la primera query completa, toda lectura ve un snapshot vacio. Si el owner se deshabilito mientras se armaba el timer (`IllegalPluginAccessException`), el board queda vacio sin explotar.
- `public void unregister(String id)` - Cancela el refresh periodico y olvida el id; ids desconocidos no-op.
- `public List<Entry> getTop(String id, int n)` - Top `n` entradas del snapshot actual, mejor primero; id desconocido -> lista vacia.
- `public int positionOf(String id, UUID uuid)` - Posicion 1-based del jugador; 0 cuando no esta rankeado o el id es desconocido.
- `public double valueOf(String id, UUID uuid)` - Valor cacheado del jugador; 0 cuando no esta rankeado o el id es desconocido.
- `public boolean exposePlaceholders(String identifier)` - Registra una expansion de PlaceholderAPI que expone todos los boards de este cache: `%<identifier>_top_<id>_<n>_name%`, `%<identifier>_top_<id>_<n>_value%` y `%<identifier>_pos_<id>%`. Los resolvers solo leen los snapshots en memoria. Retorna false con WARN cuando PlaceholderAPI esta ausente o rechaza la expansion (via `ctx.papi().expansion(identifier)`).

#### LeaderboardCache.Entry

- `public record Entry(UUID uuid, String name, double value)` - Una fila rankeada: uuid del jugador, nombre para mostrar y valor rankeado. El constructor compacto normaliza `name` null a `""`.

#### LeaderboardCache.Snapshot

Snapshot de ranking inmutable; logica pura, sin Bukkit. Las instancias nunca mutan: el cache swapea snapshots enteros detras de una referencia volatile, asi los lectores son lock-free.

- `public static Snapshot empty()` - Snapshot sin entradas (singleton `EMPTY`).
- `public static Snapshot of(List<Entry> entries)` - Construye el snapshot desde entradas desordenadas: las filas null se saltean, el resto ordena por valor DESCENDENTE con nombre ASCENDENTE como desempate (el sort es estable), y un uuid que aparece dos veces conserva su MEJOR (primera) posicion (`putIfAbsent` sobre el mapa de posiciones). Lista o resultado vacio -> `EMPTY`.
- `public List<Entry> top(int n)` - Primeras `n` entradas, mejor primero; el ranking completo cuando `n` lo excede; `n <= 0` -> lista vacia.
- `public int positionOf(@Nullable UUID uuid)` - Posicion 1-based del uuid; 0 cuando no esta rankeado (o uuid null).
- `public double valueOf(@Nullable UUID uuid)` - Valor rankeado del uuid; 0 cuando no esta rankeado.
- `public int size()` - Cantidad de entradas rankeadas.

Logica interna:

- `refresh(Board)`: corta si el board fue cancelado o `ctx.isShuttingDown()`. Si el supplier lanza, WARN `"Query del leaderboard '<id>' lanzo un error: <t>"` y se omite el ciclo; si devuelve null, WARN `"Query del leaderboard '<id>' devolvio null; refresh omitido"`. El resultado se pliega con `future.thenSync(...)` (vuelta al main thread) y solo se swapea el snapshot si el board sigue vivo y las entries no son null.
- `resolveTop(String rest)` parsea `<id>_<n>_(name|value)` desde el FINAL (dos `lastIndexOf('_')`), asi el id del board puede contener guiones bajos. Token invalido (rank no numerico, id desconocido, rank < 1, sufijo distinto de name/value) -> null (PAPI deja el token sin resolver). Rank valido pero mas alla del tamaño del snapshot -> `""` (string vacio).
- `formatValue(double)`: valores integrales renderizan sin el `.0` final.
- `sweep(Board)`: marca `cancelled = true` y cancela el task handle (tragando `Throwable` en shutdown). `TenantRegistry<Board> BOARDS` estatico garantiza el barrido al disable del owner aunque nunca haya llamado `unregister`.

Notas y gotchas:

- Contrato de threading clave: el supplier corre en el main thread; NUNCA debe bloquear, solo despachar (el Javadoc lo marca en mayusculas conceptuales: "must only DISPATCH the async work").
- El desempate por nombre es deterministico: dos jugadores con el mismo valor rankean por orden alfabetico de nombre, y como el sort es estable el orden es reproducible entre refreshes.
- `top(n)` devuelve un `subList` de la lista inmutable interna (no una copia): barato, y seguro porque el snapshot jamas muta.

### DiscordWebhook

`src/main/java/com/sn/lib/discord/DiscordWebhook.java`

Despachador de webhooks de Discord por contexto. Cero dependencias externas: los payloads se POSTean con el `HttpClient` del JDK (lazy, double-checked locking, connect timeout 5 s). El delivery es fire-and-forget sobre una cola FIFO (`ConcurrentLinkedDeque<Pending>`) procesada FUERA del main thread (encolar desde cualquier thread es no bloqueante): un HTTP 429 re-encola el mensaje AL FRENTE y espera el `Retry-After` que pidio el endpoint, cualquier otra falla descarta el mensaje con UN solo WARN por endpoint, y el token del webhook se recorta de toda linea de log. Constantes internas: `CONNECT_TIMEOUT` 5 s, `SEND_TIMEOUT` 10 s, `DRAIN_DEADLINE_MILLIS` 3000, `MAX_EMBEDS` 10.

Metodos publicos:

- `public DiscordWebhook(Sn ctx)` - Constructor.
- `public Message message(String webhookUrl)` - Empieza un mensaje para la URL del webhook; nada se encola hasta `Message.send()`.
- `public void send(String webhookUrl, String content)` - Encola un mensaje de contenido plano; atajo de `message(url).content(text).send()`.
- `public Embed embed()` - Empieza un embed standalone para adjuntar via `Message.embed(Embed)`.
- `public void drain()` - Flush sincronico best-effort de la cola en el thread que llama, invocado por el teardown del contexto despues de cancelar el scheduler. Corre bajo un deadline de 3000 ms: cada envio usa como timeout el tiempo restante; un 429 cuyo Retry-After entra antes del deadline se espera UNA vez (`Thread.sleep` + re-encolar al frente), todo lo no entregable a tiempo se descarta, y al final un WARN cuenta las perdidas (`"Drain de webhooks corto el flush: N mensaje(s) descartado(s) por el deadline de 3000ms"`). Tambien libera el `HttpClient` (`shutdown()`).

#### DiscordWebhook.Message (inner class, no estatica)

Builder de un payload de webhook; `send()` lo encola FIFO y retorna de inmediato.

- `public Message content(String content)` - Texto plano del mensaje.
- `public Message username(String username)` - Sobreescribe el nombre visible del webhook para este mensaje.
- `public Message avatarUrl(String avatarUrl)` - Sobreescribe el avatar del webhook para este mensaje.
- `public Message embed(Embed embed)` - Adjunta un embed; Discord acepta hasta 10 por mensaje, los extras se ignoran en silencio.
- `public void send()` - Encola el mensaje para delivery asincronico; payloads vacios (sin content y sin embeds) se descartan sin encolar.

#### DiscordWebhook.Embed (static nested)

Builder de un embed de Discord; se adjunta via `Message.embed(Embed)`. Constructor privado: se obtiene con `DiscordWebhook.embed()`.

- `public Embed title(String title)` - Titulo del embed.
- `public Embed description(String description)` - Descripcion del embed.
- `public Embed color(int rgb)` - Color de acento como `0xRRGGBB` (se enmascara con `& 0xFFFFFF`).
- `public Embed field(String name, String value, boolean inline)` - Agrega un field nombre/valor, opcionalmente inline; nulls se normalizan a `""`.
- `public Embed footer(String footer)` - Texto del footer.
- `public Embed timestampNow()` - Estampa el embed con el instante actual (`Instant.now().toString()`).

Logica interna:

- Cola y worker: `enqueue` hace `addLast` + `pump()`. `pump()` no arma nada si `ctx.isShuttingDown()` o si ya hay un worker corriendo (`working.compareAndSet(false, true)`); si el owner ya esta deshabilitado (`IllegalPluginAccessException` del scheduler), baja la bandera y no arma. El worker (`work()`) corre en `ctx.scheduler().async(...)`: loop FIFO con `pollFirst`; cuando la cola queda vacia baja la bandera y RE-CHEQUEA la cola (un mensaje encolado entre el poll y el flip de la bandera no debe quedar frenado: si hay algo, re-pump).
- Rate limit: `deliver` retorna 0 cuando el payload fue consumido (entregado, o descartado con su WARN) o los millis que el endpoint pidio esperar (HTTP 429). Ante 429 el worker re-encola AL FRENTE (`addFirst`, preserva el orden FIFO) y se re-arma con `ctx.scheduler().asyncLater((retryAfterMillis + 49) / 50, this::work)`.
- `retryAfterMillis(response)`: parsea el header `Retry-After` como segundos double (decimales permitidos), con piso de 1000 ms; header ausente o no numerico -> 1000 ms.
- Fallas: URL invalida (`IllegalArgumentException` al construir el request), HTTP no-2xx distinto de 429 (`"el endpoint respondio HTTP <status>"`) e `IOException` (`"fallo de red: <e>"`) descartan el mensaje con `warnOnce`; `InterruptedException` re-interrumpe el thread y descarta en silencio.
- `warnOnce(url, reason)`: un WARN por endpoint sanitizado (`"Webhook de Discord <endpoint> fallo (<reason>); errores posteriores de este endpoint se omiten del log"`); fallas posteriores del mismo endpoint quedan silenciadas.
- `sanitize(url)`: recorta el ultimo segmento del path (el token secreto del webhook) y lo reemplaza por `/***`, asi los secretos nunca llegan a consola.
- JSON a mano: `Message.toJson()` y `Embed.appendJson` construyen el JSON con `StringBuilder`; `escape` maneja comillas, backslashes, `\n` `\r` `\t` y todo control char `< 0x20` como `\uXXXX`. Campos null se omiten del payload.
- `Pending(String url, String json)` es un record privado: el JSON se serializa al momento de `send()`, no al momento del POST.

Notas y gotchas:

- `drain()` corre en el thread del teardown (el scheduler ya esta cancelado, el worker async ya no puede correr: `pump` y `work` cortan por `ctx.isShuttingDown()`), por eso el flush es sincronico y con deadline corto; los webhooks encolados "nunca se pierden en silencio" (el WARN de drop cuenta las perdidas).
- No hay confirmacion de entrega hacia el caller: `send()` retorna void de inmediato (fire-and-forget por diseño).
- El limite de 10 embeds se aplica silenciosamente en `Message.embed` (los extras ni se agregan a la lista).

### TODOs y limitaciones

No hay marcadores TODO/FIXME/HACK en el codigo de este modulo. Limitaciones documentadas en Javadoc/codigo:

- BossBarUtil: `setProgress` sobre una barra con timer activo es pisado por el timer al proximo tick; el timer anima contra reloj de pared con resolucion de 2 ticks.
- HologramUtil: la visibilidad per-viewer (`hideFrom`) no es persistente, se resetea cuando la entidad re-bindea tras un chunk reload o re-spawn; `delete` sobre una entidad en un chunk descargado no puede tocarla y difiere la remocion a la purga de huerfanas en la proxima carga del chunk.
- LeaderboardCache: piso de refresh de 1 segundo (`MIN_REFRESH_TICKS = 20`); hasta la primera query completada todas las lecturas ven un snapshot vacio; el supplier corre en el main thread y debe solo despachar (no bloquear).
- DiscordWebhook: el drain de shutdown puede descartar mensajes si no entran en el deadline de 3000 ms (con WARN contando las perdidas); un solo WARN por endpoint y despues silencio; maximo 10 embeds por mensaje (los extras se ignoran); sin confirmacion de entrega al caller (fire-and-forget).

---

# (Seccion 16 de la documentacion de SnLib v1.0.0)

## 16. Build, tests, specs golden y TODOs

Este modulo cierra la documentacion con la infraestructura que sostiene a la lib: el `pom.xml` (dependencias exactas, shading interno con relocations y exclusiones deliberadas, gate de API additive-only con japicmp y manifest con metadata Sn), los cuatro archivos de `docs/` que actuan como specs golden y plantillas para consumers (schema de menus, schema de items fisicos, pom template del consumer y reglas ProGuard del consumer), las 11 suites JUnit 5 de `src/test/java/com/sn/lib/` (104 tests, todos verdes, verificados con `mvn test` via surefire) y el inventario completo de pendientes: lo que arroja el grep de TODO/FIXME/placeholder sobre el codigo mas los pendientes conocidos del handoff (bStats, degradacion 1.20.4, repo/release, pilotos y canary). Tambien se registra el resultado del smoke gate en Paper 1.21.8 build 60 y 1.20.4 build 499 (verde en ambos).

### pom.xml (build de SnLib)
`pom.xml`
Coordenadas `com.sn:snlib:1.0.0`, packaging `jar`, nombre `SnLib`, descripcion "Common library core for Sn plugins, shipped as a standalone hard-depend plugin.". Compila con Java 21 (`maven.compiler.release=21`) y define la property `sn.api.level=1`, que el propio pom aclara como valor informativo del manifest: la constante real del handshake es `com.sn.lib.SnApi.LEVEL`.

Repositorios declarados:

- `papermc` (`https://repo.papermc.io/repository/maven-public/`)
- `extendedclip` (`https://repo.extendedclip.com/content/repositories/placeholderapi/`)
- `jitpack` (`https://jitpack.io`)

`dependencyManagement`: importa `net.kyori:adventure-bom:4.25.0` (scope `import`, type `pom`). Razon documentada en el pom: el POM de paper-api fija adventure-api 4.18.0 mientras Paper shippea serializers 4.25.0; sin este pin el pipeline MiniMessage arriesga `NoSuchMethodError`/`NoClassDefFoundError` en runtime.

Dependencias exactas:

- `io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT` (provided) - baseline de compilacion 1.21.1 (disponibilidad de `setMaxStackSize`); piso de runtime 1.20.4, target 1.21.8 via `SnCompat.probe`.
- `net.kyori:adventure-api` (provided, version del BOM 4.25.0).
- `net.kyori:adventure-text-minimessage` (provided, version del BOM 4.25.0).
- `me.clip:placeholderapi:2.11.6` (provided).
- `com.github.MilkBowl:VaultAPI:1.7.1` (provided, excluye `org.bukkit:bukkit`).
- `com.zaxxer:HikariCP:6.3.0` (compile) - se shadea relocada a `com.sn.lib.libs.hikari`.
- `org.slf4j:slf4j-api:2.0.16` (provided) - Paper ya provee slf4j-api; declararla provided la deja FUERA del jar shadeado y evita el `NoSuchMethodError` de `SLF4JServiceProvider`.
- `org.slf4j:slf4j-jdk14:2.0.16` (compile, excluye `org.slf4j:slf4j-api`) - binding shadeado SIN relocation para que la HikariCP relocada encuentre provider y no imprima "No SLF4J providers were found".
- `org.xerial:sqlite-jdbc:3.46.1.3` (compile) - SHADEADA, JAMAS RELOCAR: el binding JNI `org.sqlite.core.NativeDB` se rompe bajo relocation.
- `com.mysql:mysql-connector-j:8.4.0` (compile, excluye `com.google.protobuf:protobuf-java`) - SHADEADA, JAMAS RELOCAR: driver binario, una sola copia en el server.
- `org.bstats:bstats-bukkit:3.1.0` (compile) - se shadea relocada a `com.sn.lib.libs.bstats`.
- `org.junit.jupiter:junit-jupiter:5.10.2` (test).

Build:

- `finalName`: `SnLib-${project.version}` (produce `SnLib-1.0.0.jar`).
- Resources con `filtering=true` sobre `src/main/resources` (expansion de properties Maven en `plugin.yml`/`config.yml`).
- `maven-compiler-plugin:3.13.0` y `maven-surefire-plugin:3.2.5` sin configuracion extra.
- `maven-jar-plugin:3.4.1` - manifest con dos entradas propias: `Sn-Lib-Version: ${project.version}` y `Sn-Api-Level: ${sn.api.level}`.
- `maven-shade-plugin:3.6.0` (fase `package`, goal `shade`):
  - Relocation `com.zaxxer.hikari` -> `com.sn.lib.libs.hikari`, con excludes `org.sqlite.**` y `com.mysql.**` (JNI NativeDB / driver binario).
  - Relocation `org.bstats` -> `com.sn.lib.libs.bstats`, con los mismos excludes.
  - `ServicesResourceTransformer`: preserva/mergea `META-INF/services` (el `SLF4JServiceProvider` del binding jdk14 y los drivers JDBC de sqlite/mysql).
  - Filtro global `*:*` que excluye `META-INF/*.SF`, `META-INF/*.DSA`, `META-INF/*.RSA`, `module-info.class` y `META-INF/versions/*/module-info.class` (firmas invalidas post-shade y descriptores de modulo ajenos).
- `japicmp-maven-plugin:0.21.2` (fase `verify`, goal `cmp`) - gate de API publica additive-only:
  - `ignoreMissingOldVersion=true`: en 1.0.0 no existe artefacto previo contra el cual comparar; el comentario del pom lo dice explicito: "baseline real desde 1.0.1".
  - `ignoreMissingClasses=true`: el jar shadeado incluye mysql-connector-j sin relocar, cuyas clases X DevAPI referencian protobuf (excluido del shade adrede); japicmp no debe exigir ese classpath.
  - Excludes del analisis: `com.sn.lib.**.internal.**`, `com.sn.lib.libs.**` (relocadas), y las shadeadas SIN relocar que no son API de SnLib: `com.mysql.**`, `org.sqlite.**`, `org.slf4j.**`, `google.protobuf.**`.
  - `onlyModified=true`, `breakBuildOnBinaryIncompatibleModifications=true`, `breakBuildOnSourceIncompatibleModifications=false`: rompe el build solo ante incompatibilidad BINARIA (regla additive-only), tolera incompatibilidades de fuente.

#### Notas y gotchas

- La matriz de shading tiene tres regimenes distintos y deliberados: relocado (HikariCP, bStats), shadeado sin relocar (sqlite-jdbc, mysql-connector-j, slf4j-jdk14) y provided (paper-api, adventure, PAPI, VaultAPI, slf4j-api). Mover una dependencia de regimen rompe cosas concretas ya documentadas en los comentarios del pom (JNI, providers SLF4J, dobles copias de driver).
- El pin del `adventure-bom` 4.25.0 existe solo para alinear compile-time con lo que Paper realmente shippea; adventure sigue siendo provided y no viaja en el jar.
- `Sn-Api-Level` del manifest es informativo; el gate real de compatibilidad consumer/lib es el handshake en runtime contra `SnApi.LEVEL`.
- El exclude de `protobuf-java` en mysql-connector-j es la contraparte del `ignoreMissingClasses=true` de japicmp: se acepta un jar con referencias colgantes a protobuf porque el codigo X DevAPI nunca se ejecuta en el server.

### docs/menu-example.yml (spec golden de GUIs)
`docs/menu-example.yml`
Spec golden del schema de configuracion de menus (Menu Lib): un GUI por archivo dentro de la carpeta `guis/` del plugin consumer. Contrato explicito del archivo: todo campo documentado aca esta soportado nativamente por SnLib; si el usuario de config setea un campo soportado, YA funciona sin codigo del plugin. Documenta:

- Campos raiz: `title` (default "Menu"), `rows` 1-6 (default 3), `open-sound` (default ''), `update-interval` en ticks (0 = sin auto-update; refresca items, titulo y rows), `inventory-type` (default CHEST; CHEST, DISPENSER, DROPPER, HOPPER, FURNACE, WORKBENCH, ENCHANTING, BREWING, ANVIL, BEACON, SHULKER_BOX, BARREL, etc).
- `pagination` (opt-in por menu, default false): con `true` el GUI mantiene UNA GuiSession + UN Inventory POR VIEWER (page-state real por jugador; el mismo GUI sirve N jugadores en paginas distintas a la vez), funcionan `[next-page]`/`[previous-page]`/`[set-page]`/`[refresh-page]` y `bindPaged` llena los slots paginados via API. Con `false` (default) las acciones de paginacion son no-ops silenciosos con nota de debug y `bindPaged` WARNea una vez.
- Schema de `items`: `display-name`, `material` (resuelve basehead-base64), `custom-model-data`, `amount`, `slots` (int, rango "0-2" o mix "0, 2, 4-6"), `glow`, `enchantments` (pares id/level), `flags` (HIDE_ENCHANTS, HIDE_ATTRIBUTES, HIDE_UNBREAKABLE, HIDE_DESTROYS, HIDE_PLACED_ON, HIDE_POTION_EFFECTS, y HIDE_ALL como combinacion), `color` (RGB "235, 64, 52" o HEX), `trim-pattern`/`trim-material` (armadura), `potion-effects` (ternas effect/level/duration), `update-interval` por item, `lore`, `click-requirements` y `view-requirements` (expresiones `%placeholder% > 0 && %placeholder% < 10`, `=`, `!=`), `deny-actions` (mismas acciones, corren si NO se cumplen los click-requirements) y `click-actions`.
- Catalogo completo de acciones de click: `[player]`, `[player-as-op]`, `[right-click]`, `[left-click]`, `[shift-left-click]`, `[shift-right-click]`, `[console]`, `[message]`, `[sound]`, `[close]`, `[open] gui-id`, `[connect]` (switch BungeeCord), `[broadcastmessage]`, `[actionbar]`, `[title]` (formato `title;subtitle;fadeIn;stay;fadeOut`), `[next-page]`, `[previous-page]`, `[refresh-page]`, `[set-page]` (solo con pagination: true), `[refresh-menu]` y `[custom]` (acciones registrables por el plugin con cualquier string).
- Items de navegacion de paginacion (`previous-page`/`next-page` de ejemplo): items normales cuyas click-actions usan las acciones de paginacion, con seccion opcional `nav-disabled`: override de apariencia renderizado en los MISMOS slots EN LUGAR del item de navegacion cuando no hay pagina a donde ir (primera pagina para previous, ultima para next). `nav-disabled` soporta los mismos campos de apariencia que un item normal pero sin slots ni acciones: una flecha deshabilitada jamas dispara nada.
- Ejemplos del pipeline de texto (SnText): `[rgb]` aplica gradiente por caracter interpolado sobre 7 anchors fijos (#F300F3, #5555FF, #55FFFF, #55FF55, #FCFF21, #FF9B00, #FF5327), PISA los codigos de COLOR preexistentes y PRESERVA los de FORMATO (&l &o &n &m &k); `[center]` centra la linea a 154px sobre el string legacy ya coloreado (gradiente ya interpolado) como ULTIMO paso antes de renderizar a Component. Ambos tags prefix son componibles en CUALQUIER orden: `[center][rgb]` == `[rgb][center]`. Codigos legacy (&a, &#RRGGBB) y tags MiniMessage renderizan juntos.
- Seccion `templates`: items IDENTICOS a los normales pero SIN `slots:`; el developer del plugin decide dinamicamente el slot via API Java y el usuario de config customiza la apariencia libremente. Pueden usar placeholders locales definidos por el plugin (ej `%index%`, `%warp_name%`). Incluye el ejemplo de uso de un plugin de mochilas (el plugin asigna por jugador que template va a cada slot).

### docs/item-example.yml (spec golden de items fisicos)
`docs/item-example.yml`
Spec golden del schema de items FISICOS (Item Lib): items que se entregan a jugadores (inventario, drops, etc), NO items de GUI (para eso esta menu-example.yml). Todo item-id definido aca se puede dar via API `sn.items().give(player, "item-id", amount)`, y cualquier ItemDef tambien se puede construir 100% programaticamente via `ItemDef.builder()` sin YML. Documenta por bloques:

- APPEARANCE: `display-name`, `material` (resuelve basehead-base64), `custom-model-data`, `amount`, `glow`, `lore`, `enchantments`, `flags` (mismo set que menus, con HIDE_ALL), `color` RGB/HEX, `trim-pattern`/`trim-material`, `potion-effects`.
- PROPERTIES: `unbreakable` (default false), `max-stack-size` 1-64 (default vanilla del material), `droppable` (default true), `moveable` (default true), `placeable` (default true, solo bloques), `tradeable` (default true), `despawnable` (default true), `keep-on-death` (default false), `cooldown` en ticks (0 = sin cooldown).
- LOCKED MODE Y OBTAIN CONTROL: `locked` (default false) fija el item a su slot y bloquea la extraccion por los 7 vectores de robo (drag, number-key swap, offhand swap, shift-move, drop, cursor pickup, transferencia hopper/inventario); el item real desplazado por uno locked se restaura en quit y shutdown via backup write-through (default-on: el backup sobrevive un crash del server sin onDisable). `no-drop` es alias duro de `droppable: false` (bloquea Q/drop y drag-out). `no-manual-equip` impide equipar manualmente en armadura u offhand (right-click equip, click de inventario, number-key y drag). `obtain-via` restringe como entra el item en circulacion: "" (default) sin restriccion; `COMMAND_ONLY` solo via comando o API del plugin, cancelando crafting/mob-pickup/otros caminos.
- DURABILITY custom (separada de la vanilla, util para items sin durabilidad como sticks): `custom-durability.max` (0 = deshabilitado), `damage-per-use` (default 1), `break-actions` (acciones al llegar a 0) y `lore-format` con `%durability%`/`%max_durability%` actualizado automaticamente.
- INTERACT ACTIONS (mundo, no GUI): 8 listas: `right-click-actions`, `left-click-actions`, `shift-right-click-actions`, `shift-left-click-actions`, `right-click-block-actions`, `right-click-air-actions`, `left-click-block-actions`, `left-click-air-actions`. Ademas de las acciones comunes suma `[particle] TYPE [count] [offX offY offZ] [extra] [key=value...]`, `[potion] EFFECT duration(ticks) amplifier`, `[remove-item]` (1 unidad) y `[remove-item] [N] [offhand|id:<item-id>|MATERIAL]`.
- INTERACT REQUIREMENTS + `deny-actions` cuando no se cumplen.
- PICKUP/DROP ACTIONS: `pickup-actions` y `drop-actions`.
- HELD EFFECTS: efectos continuos mientras se sostiene o viste el item, por slot: `held-effects.mainhand`, `offhand`, `armor`; formato "EFFECT amplifier" (amplifier = level - 1).
- `equipment-slot`: restringe donde se puede colocar (MAINHAND, OFFHAND, HEAD, CHEST, LEGS, FEET; default "" = cualquiera).
- RECIPE: receta custom opcional; `type` SHAPED (con `shape` + `ingredients` mapeados por letra), SHAPELESS (`ingredients` lista), o FURNACE/SMOKING/BLASTING/CAMPFIRE/STONECUTTING (`input`, `experience`, `cooking-time` en ticks).

### docs/consumer-pom-template.xml (template de pom del consumer)
`docs/consumer-pom-template.xml`
Template de `pom.xml` para plugins Sn que consumen SnLib bajo el modelo standalone hard-depend. Su header documenta el contrato completo de consumo:

- Resolucion de `com.sn:snlib`: 1) publicar SnLib al `.m2` local con `mvn install -f <ruta>/SnLib/pom.xml`; 2) JitPack NO soportado (el repo de SnLib es privado y JitPack no construye repos privados): la UNICA via de resolucion es el `.m2` local; 3) en runtime NADA de SnLib se shadea en el consumer: el server carga `SnLib.jar` como plugin standalone en `plugins/` y el consumer declara `depend: [SnLib]` en su plugin.yml. Por eso el scope es `provided` y el template NO incluye maven-shade-plugin para la lib; si el consumer shadea dependencias propias, JAMAS incluir `com.sn:snlib` en el shade.
- Bloque `plugin.yml` minimo del consumer: `name`, `main`, `version`, `api-version: '1.20'`, `depend: [SnLib]`, comando principal y arbol de permisos `myplugin.admin` (default op) con hijo `myplugin.admin.reload`.
- Clase principal del consumer (unica via de init: extender `SnPlugin`), con las tres firmas del contrato: `protected int requiredApiLevel()` retornando `SnApi.LEVEL`, `protected SnSpec buildSpec()` (ejemplo: `SnSpec.builder().config("config.yml").lang().guis().build()`) y `protected void onInnerEnable()` donde se registran comandos, guis, items, db, etc sobre el contexto Sn.
- El pom en si: `com.sn:myplugin:1.0.0`, Java 21, repo papermc, dependencias `com.sn:snlib:1.0.0` (provided, del .m2 local; en runtime la provee `SnLib.jar` en `plugins/`) y `io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT` (provided), y solo `maven-compiler-plugin:3.13.0` en build (sin shade).

### docs/snlib-consumer-rules.pro (reglas ProGuard del consumer)
`docs/snlib-consumer-rules.pro`
Reglas ProGuard para plugins Sn que consumen SnLib y se ofuscan con sn-obfuscate. Premisa: `SnLib.jar` es una LIBRERIA en runtime (plugin standalone en `plugins/`), NUNCA se ofusca ni se shadea en el consumer; se declara como `-libraryjars` igual que paper-api. Reglas:

- `-libraryjars <user.home>/.m2/repository/com/sn/snlib/1.0.0/snlib-1.0.0.jar` (ajustar la ruta al `.m2` local o al jar del release).
- `-dontwarn com.sn.lib.**`: SnLib no viaja dentro del jar del consumer; silenciar warnings de referencias a clases de la lib.
- Keep del entrypoint: `-keep public class * extends com.sn.lib.SnPlugin` preservando `public <init>()`, `protected int requiredApiLevel()`, `protected com.sn.lib.SnSpec buildSpec()`, `protected void onInnerEnable()` y `protected void onInnerDisable()`. Razon: Bukkit instancia la clase por reflexion (main de plugin.yml) y SnLib invoca el handshake `requiredApiLevel()`.
- Keeps de clases registradas por reflexion o por el framework de Bukkit: `* implements org.bukkit.event.Listener`, `* implements org.bukkit.command.CommandExecutor`, `* implements org.bukkit.command.TabCompleter` y `* extends me.clip.placeholderapi.expansion.PlaceholderExpansion` (todas con `{ *; }`).
- `-keepclassmembers class * { @org.bukkit.event.EventHandler <methods>; }`: preserva metodos `@EventHandler` en cualquier clase, por si un listener no implementa `Listener` directamente sino via clase intermedia.

### Suites de tests (12 suites, 137 tests, verdes)

Las 12 suites viven en `src/test/java/com/sn/lib/` (paquete plano `com.sn.lib`), corren con JUnit Jupiter 5.10.2 bajo surefire 3.2.5 y son 100% JVM puras: ninguna levanta servidor ni mockea Bukkit; cubren exactamente las piezas de la lib que son logica pura (texto, parsing, cron, yml, leaderboard). Total verificado con `mvn test`: 137 tests, 0 failures, 0 errors, 0 skipped. Fixtures en `src/test/resources/yml/`: `tabs-broken.yml` (YAML indentado con tabs que YamlPreprocessor debe reparar, con tabs dentro de valores quoted y block scalars que debe preservar), `merge-resource.yml` / `merge-old.yml` / `merge-expected.yml` (trio golden del merge de YamlUpdater: resource nuevo del jar, archivo viejo del usuario con valores propios y key extra, resultado esperado) y `corrupt.yml` (YAML deliberadamente invalido: quote y flow collection sin cerrar).

### RgbGradientTest
`src/test/java/com/sn/lib/RgbGradientTest.java`
7 tests sobre `com.sn.lib.text.RgbGradientUtil.applyRgbTag(String)`: el gradiente `[rgb]` por caracter. Verifica contra los anchors extremos exactos `F300F3` y `FF5327`.

- `void emitsOneHexPerVisibleCharacter()` - emite exactamente un `&#RRGGBB` por caracter visible (5 para "Hello", 8 para "Gradient").
- `void extremesUseExactAnchors()` - el primer caracter recibe el anchor `#F300F3` y el ultimo `#FF5327`, sin interpolacion en los extremos.
- `void spacesDoNotConsumeGradientPositions()` - los espacios no consumen posiciones del gradiente: "A B" produce los mismos hex que "AB".
- `void formatPreservedAndColorOverridden()` - los codigos de formato (`&l`) se preservan y re-emiten por caracter; los de color (`&a`) se descartan.
- `void resetClearsAccumulatedFormat()` - `&r` limpia el formato acumulado: el caracter posterior sale sin `&l`.
- `void existingHexColorIsDiscarded()` - un hex legacy preexistente (`&#123456`) se descarta y se pisa con el gradiente.
- `void singleVisibleCharacterGetsFirstAnchor()` - con un solo caracter visible se usa el primer anchor.

### SemverComparatorTest
`src/test/java/com/sn/lib/SemverComparatorTest.java`
10 tests sobre `com.sn.lib.hook.SemverComparator` (metodo estatico `compareVersions(String, String)` y la clase como `Comparator<String>`), la comparacion de versiones usada por el sistema de hooks.

- `void comparesSegmentsNumericallyNotLexically()` - segmentos numericos, no lexicograficos: 1.9 < 1.10, 1.99.9 < 1.100.0.
- `void supportsSegmentsOfAnyDigitCount()` - segmentos de cualquier cantidad de digitos (1.2.345 < 1.2.1000).
- `void missingTrailingSegmentsCountAsZero()` - segmentos finales ausentes valen 0: "1.2" == "1.2.0", "1" == "1.0.0"; soporta 4 segmentos ("1.2" < "1.2.0.1").
- `void preReleaseComparesLowerThanRelease()` - un pre-release precede a la release pelada: "1.0.0-SNAPSHOT" < "1.0.0", "2.11.6-DEV-SNAPSHOT" < "2.11.6" (el pre-release "DEV-SNAPSHOT" es UN identificador: el split es por `.`).
- `void semverOrgPrecedenceTable()` - la escalera completa de semver.org par a par: alpha < alpha.1 < alpha.beta < beta < beta.2 < beta.11 < rc.1 < release.
- `void numericIdentifiersCompareNumerically()` - identificadores numericos comparan como numeros: alpha.9 < alpha.10.
- `void numericIsLowerThanAlphanumeric()` - un identificador numerico es menor que uno alfanumerico: "1.0.0-1" < "1.0.0-alpha".
- `void buildMetadataIsIgnored()` - el build metadata `+...` se ignora: "1.0.0+build.5" == "1.0.0", "1.0.0-alpha+001" == "1.0.0-alpha".
- `void equalVersionsCompareAsZero()` - versiones iguales comparan 0 ("0.0.0" == "0").
- `void comparatorInstanceSortsAscending()` - la instancia como Comparator ordena listas ascendente semver.

### SlotParserTest
`src/test/java/com/sn/lib/SlotParserTest.java`
13 tests sobre `com.sn.lib.util.SlotParser.parse(Object)` y `parse(Object, Consumer<String>)`: el parser tolerante de slots de los YML de GUIs.

- `void parsesSingleInt()` - un int suelto (incluido 0) produce ese slot.
- `void parsesNumericString()` - string numerico, con trim de espacios (" 13 ").
- `void parsesRange()` - rangos "0-8" y con espacios "10 - 12" expanden inclusive.
- `void normalizesReversedRange()` - rango invertido "2-0" se normaliza a 0,1,2.
- `void parsesCommaSeparatedMix()` - mix por comas "0,2,4-6".
- `void parsesListOfMixedElements()` - lista YAML heterogenea `[1, "3-5", "7"]`.
- `void deduplicatesKeepingFirstSeenOrder()` - dedup preservando orden de primera aparicion ("4-6,5,3" -> 4,5,6,3).
- `void invalidInputYieldsEmptyAndDelegatesWarn()` - input invalido devuelve array vacio y delega UN warn al consumer, incluyendo el texto ofensor.
- `void nullYieldsEmptyAndDelegatesWarn()` - null devuelve vacio y warnea.
- `void negativeSlotsAreWarnedAndSkipped()` - slots negativos se saltean con warn.
- `void invalidTokensAreSkippedButValidOnesKept()` - "1,x,2" conserva 1 y 2 con un solo warn (fail-soft por token).
- `void oversizedRangeIsRejected()` - un rango desmedido ("0-999999999") se rechaza entero con warn (proteccion de memoria).
- `void nullWarnConsumerIsSafe()` - la sobrecarga sin consumer no lanza ante basura.

### TimeUtilTest
`src/test/java/com/sn/lib/TimeUtilTest.java`
10 tests sobre `com.sn.lib.util.TimeUtil`: `parseMillis`, `parseTicks`, `humanize`, `humanizeShort` y la interfaz de i18n `TimeUtil.Labels` (con `longLabel(Unit, boolean)` y `shortLabel(Unit)` sobre el enum `Unit` DAY/HOUR/MINUTE/SECOND). Define un Labels de prueba en español.

- `void parsesCanonicalDurationString()` - "1d 2h 30m 15s" parsea a millis exactos y `parseTicks` divide por 50.
- `void parsesCompactAndSpacedVariants()` - variantes compacta ("1d2h30m15s") y ultra espaciada ("1 d 2 h ...").
- `void parsesFullUnitWords()` - palabras completas ("1 day 2 hours 30 minutes 15 seconds").
- `void bareNumberCountsAsSeconds()` - un numero pelado son segundos ("45" -> 45000 ms).
- `void supportsDecimalsTicksAndMillis()` - decimales ("1.5h"), ticks ("1t" -> 50 ms) y millis ("250ms").
- `void invalidInputYieldsZero()` - null, vacio, basura y unidad desconocida ("5x") devuelven 0 (nunca lanzan).
- `void humanizesLongForm()` - forma larga con plurales ingleses por default, incluyendo "0 seconds".
- `void humanizesShortForm()` - forma corta "1d 2h 30m 15s", "1m", "0s".
- `void labelsAreInjectableForI18n()` - los labels son inyectables: "1 minuto 1 segundo", "2 dias", "1min 1seg", "0seg".
- `void shortFormRoundTripsThroughParse()` - round-trip: `parseMillis(humanize(x)) == x` y lo mismo con humanizeShort para varias muestras.

### NumberFormatterTest
`src/test/java/com/sn/lib/NumberFormatterTest.java`
8 tests sobre `com.sn.lib.util.NumberFormatter`: `format(double)` (sufijos K/M/B/T/Qa/Qi) y `parseFormatted(String)` (inversa tolerante a separadores).

- `void formatsPlainNumbersBelowThousand()` - bajo 1000 sin sufijo; decimales redondeados a 2 (12.345 -> "12.35").
- `void formatsEachSuffixMagnitude()` - cada magnitud: 1.5K, 1M, 2.5B, 1T, 1Qa (1e15), 1Qi (1e18).
- `void formatsNegativesAndRoundsToTwoDecimals()` - negativos ("-1.5K") y redondeo a 2 decimales ("1.23M").
- `void promotesWhenRoundingReachesNextMagnitude()` - promocion al redondear: 999999 -> "1M", 999.999 -> "1K" (nunca "1000K").
- `void parsesSuffixedInputCaseInsensitively()` - parse case-insensitive de sufijos ("1.5k", "2m", "1qa", "2.5Qi", "-2.5B").
- `void toleratesCommaAndDotSeparators()` - tolera coma decimal ("1,5K"), miles con coma ("1,500"), formato US ("1,234,567.89") y europeo ("1.234.567,89").
- `void rejectsGarbage()` - null, vacio, letras y sufijo desconocido ("1.5X") lanzan `NumberFormatException`.
- `void roundTripsWithinSuffixPrecision()` - round-trip format->parse dentro del 0.5% para muestras de todas las magnitudes incluidas negativas.

### YamlPreprocessorTest
`src/test/java/com/sn/lib/YamlPreprocessorTest.java`
8 tests sobre `com.sn.lib.yml.YamlPreprocessor` (`preprocess(String)` que devuelve el record `Result` con `cleanText()` y `fixedLines()`, y `read(Path)`): la capa que repara YAML indentado con tabs antes de SnakeYAML. Usa el fixture `/yml/tabs-broken.yml`.

- `void rawFixtureIsRejectedBySnakeYaml()` - control del golden: el fixture crudo NO parsea con SnakeYAML sin preprocesar.
- `void preprocessedFixtureParsesAndPreservesValues()` - el texto preprocesado parsea y preserva los valores: el tab DENTRO de un valor quoted ("Sn\tLib"), la indentacion mixta tab/espacio, tabs en items de lista y el contenido de block scalars byte a byte.
- `void reportsFixedLinesOneBased()` - `fixedLines()` reporta las lineas corregidas 1-based (3, 4, 6, 8, 9 en el fixture).
- `void rewritesIndentTabsButNotBlockScalarContent()` - reescribe SOLO los tabs de indentacion; las lineas internas de un block scalar quedan intactas (incluso un tab lider dentro del bloque).
- `void normalizesCrlfToLf()` - normaliza CRLF a LF ademas de los tabs.
- `void isIdempotentOnCleanText()` - idempotencia: preprocesar texto ya limpio no cambia nada y no reporta fixes.
- `void neverThrowsOnDegenerateInput()` - null y vacio devuelven Result vacio; input degenerado ("\t: weird") se repara sin lanzar jamas.
- `void readsUtf8AndStripsBom(@TempDir Path)` - `read(Path)` lee UTF-8 y strippea el BOM inicial.

### RequirementEngineTest
`src/test/java/com/sn/lib/RequirementEngineTest.java`
22 tests sobre `com.sn.lib.action.RequirementEngine.parse(List<String>)` / `parse(List<String>, Consumer<String>)` y el arbol inmutable `com.sn.lib.action.Requirement` evaluado con `test(player, resolver)`: el motor de click/view/interact-requirements. Usa un resolver mock que reemplaza tokens `%key%`.

- `void numericAndChainWithinOneLine()` - `>` y `<` encadenados con `&&` en una linea; los limites quedan excluidos.
- `void linesJoinWithImplicitAnd()` - varias lineas de la lista se unen con AND implicito.
- `void allNumericOperators()` - los 6 operadores numericos: `>=`, `<=`, `=`, `==`, `!=` (y sus negativos).
- `void integerAndDecimalCompareNumerically()` - "5" == "5.0": la igualdad compara numericamente cuando ambos lados son numeros.
- `void equalityFallsBackToCaseInsensitiveLexicographic()` - `=`/`!=` con operandos no numericos caen a comparacion lexicografica case-insensitive ("VIP" == "vip").
- `void nonNumericRelationalIsFalseWithWarn()` - relacional (`>`) con operando no numerico evalua false y warnea (incluyendo operador y valor en el mensaje).
- `void andBindsTighterThanOr()` - `&&` liga mas fuerte que `||` (precedencia estandar).
- `void malformedLineWarnsAndEvaluatesTrue()` - linea sin operador warnea y evalua TRUE (fail-open: una config rota no bloquea el menu).
- `void emptyOperandIsMalformed()` - operando izquierdo vacio ("> 5") es malformado: warn + true.
- `void malformedBranchTurnsWholeLineTrue()` - una rama malformada dentro de una linea con `&&` vuelve TRUE la linea entera.
- `void nullEmptyAndBlankInputAlwaysPass()` - null, lista vacia y lineas en blanco siempre pasan.
- `void nullResolverLeavesTokensUntouched()` - resolver null deja los tokens intactos (los literales evaluan, los `%x%` no matchean).
- `void placeholdersResolveAtTestTimeNotParseTime()` - los placeholders se resuelven en cada `test`, no al parsear: el mismo Requirement da resultados distintos con valores distintos.
- `void parenthesesGroupOrOverAnd()` - `(a || b) && c` agrupa distinto que la misma linea sin parentesis (el grupo cambia el resultado).
- `void nestedParenthesesParse()` - parentesis anidados `((a && b) || c)` parsean y evaluan.
- `void quotedOperandKeepsConnectorsLiteral()` - `%rank% = 'VIP && MVP'`: el `&&` dentro de comillas es literal, no conector.
- `void quotedOperandKeepsParensLiteral()` - `%tag% = "(admin)"`: los parentesis dentro de comillas son literales.
- `void quotesAreStrippedFromOperand()` - las comillas se quitan del operando final (el valor resuelto matchea sin comillas).
- `void operatorInsideQuotesIsNotAnOperator()` - `%x% = 'a >= b'` evalua EQ, no GE: los simbolos de operador dentro de comillas son literales.
- `void unbalancedParenFailsOpenWithWarn()` - `(` sin cierre cae en fail-open: always-true con UN warn en el sink.
- `void strayCloseParenFailsOpenWithWarn()` - `)` suelto al final de la linea cae en fail-open con un warn.
- `void unquotedLegacyExpressionsKeepTheirTree()` - expresiones estilo 1.0.0 (sin comillas ni parentesis) producen el mismo arbol y los mismos resultados que antes.

### CronNextRunTest
`src/test/java/com/sn/lib/CronNextRunTest.java`
13 tests sobre `com.sn.lib.cron.CronExpr.parse(String)` y `nextRun(ZonedDateTime)`: cron de 5 campos mas atajos `daily HH:MM` y `hourly :MM`, con manejo real de zonas horarias y DST (probado en UTC y America/New_York).

- `void stepFieldMatchesNextMultiple()` - campos step (`*/15`) matchean el proximo multiplo, cruzando de hora si hace falta.
- `void listAndRangeFieldsCombine()` - listas y rangos combinados ("0,30 9-17 * * *"), incluyendo el salto al dia siguiente al agotar el rango horario.
- `void dayOfWeekFieldWaitsForMatchingDay()` - el campo day-of-week espera al proximo dia que matchee (viernes -> lunes).
- `void sundayMatchesBothZeroAndSeven()` - domingo matchea tanto con 0 como con 7.
- `void dailyShortcutIsStrictlyAfterFrom()` - `daily 04:00` es estrictamente posterior al `from`: en el instante exacto devuelve el dia siguiente.
- `void hourlyShortcutMatchesEveryHour()` - `hourly :30` matchea cada hora, tambien estrictamente posterior.
- `void dayThirtyOneSkipsShorterMonths()` - dia 31 saltea meses cortos (desde abril, el proximo 31 es el 31 de mayo).
- `void februaryTwentyNinthWaitsForLeapYear()` - 29 de febrero espera al proximo año bisiesto (2028 desde 2026).
- `void springForwardShortensTheRealDelay()` - inicio de DST en US: el delay real se acorta a 19h porque la hora 02:00-02:59 no existe.
- `void fallBackLengthensTheRealDelay()` - fin de DST: el delay real se alarga a 21h porque la hora 01:00-01:59 se repite.
- `void wallClockErasedByDstGapSkipsToNextDay()` - un `daily 02:30` cuya hora de pared es borrada por el gap de DST cae al dia siguiente, no a una hora corrida.
- `void invalidExpressionsThrow()` - expresiones invalidas lanzan `IllegalArgumentException`: minuto 61, 3 campos, letras, `daily 25:00`, `hourly :75`, vacio y step 0.
- `void impossibleDateNeverMatches()` - una fecha imposible (31 de febrero) lanza `IllegalStateException` en `nextRun` en vez de loopear infinito.

### LeaderboardSnapshotTest
`src/test/java/com/sn/lib/LeaderboardSnapshotTest.java`
9 tests sobre `com.sn.lib.leaderboard.LeaderboardCache.Snapshot` (`Snapshot.of(List<Entry>)`, `Snapshot.empty()`, `top(int)`, `positionOf(UUID)`, `valueOf(UUID)`, `size()`) y el record `LeaderboardCache.Entry(UUID, String name, double value)`: la vista inmutable cache-only apta para resolvers de PlaceholderAPI.

- `void ordersByValueDescending()` - ordena por valor descendente.
- `void tiesBreakByNameAscending()` - empates de valor desempatan por nombre ascendente.
- `void topClampsToSizeAndRejectsNonPositive()` - `top(n)` clampea al tamaño real y devuelve vacio para n <= 0.
- `void positionsAreOneBasedAndZeroWhenUnranked()` - posiciones 1-based; 0 para uuid no rankeado o null.
- `void valueOfReturnsCachedValueAndZeroWhenUnranked()` - `valueOf` devuelve el valor cacheado; 0.0 para no rankeado o null.
- `void duplicateUuidKeepsBestPosition()` - un uuid duplicado conserva su mejor posicion y valor.
- `void snapshotIsImmutable()` - las listas de `top` son inmutables (`UnsupportedOperationException` al mutar).
- `void emptyAndNullInputsYieldEmptySnapshot()` - lista vacia o null producen snapshot vacio; `Snapshot.empty()` responde vacio y posicion 0.
- `void nullRowsAndNullNamesAreTolerated()` - filas null se descartan y nombres null se normalizan a "".

### CenterUtilTest
`src/test/java/com/sn/lib/CenterUtilTest.java`
9 tests sobre `com.sn.lib.text.CenterUtil.center(String)`: centrado pixel-exacto contra el half-width de 154px del chat, donde los codigos de color son invisibles al medir, el bold ensancha glifos y las lineas mas anchas que la ventana pasan intactas.

- `void centersShortLineWithExactPixelMath()` - matematica de pixeles exacta: "ab" (12px) compensa 148px en espacios de 4px -> 37 espacios lideres.
- `void emptyAndNullPassThrough()` - "" y null pasan sin tocar (null devuelve la MISMA referencia).
- `void lineWiderThanWindowIsUnchanged()` - una linea mas ancha que la ventana vuelve sin cambios (misma instancia, `assertSame`).
- `void colorCodesAreIgnoredWhileMeasuring()` - los codigos `&a` y hex `&#RRGGBB` no cuentan pixeles: mismo padding que la version plana.
- `void sectionSignCodesAreIgnoredWhileMeasuring()` - idem con codigos `§`.
- `void boldWidensTheMeasuredLine()` - `&l` suma 1px por glifo no-espacio: una linea bold larga necesita menos espacios lideres.
- `void resetStopsBoldMeasurement()` - `&r` corta la medicion en bold para los caracteres posteriores.
- `void smallCapsLineMeasuresLikeUppercase()` - una linea small caps (`SnText.smallCaps`) recibe el mismo padding que su version en mayusculas: glifos small base 5 = mayusculas base 5 ("HELLO") y U+026A base 3 = 'I' base 3 ("HI").
- `void centeredGradientLineKeepsPayloadIntact()` - una linea como sale de la fase `[rgb]` (un hex por caracter) conserva el payload intacto y solo cuentan los glifos visibles (H+i+! = 10px -> 38 espacios). Documenta el orden real del pipeline: `[center]` corre despues de `[rgb]`.

### SmallCapsTest
`src/test/java/com/sn/lib/SmallCapsTest.java`
16 tests sobre `com.sn.lib.text.SmallCapsUtil.applySmallTag(String)` y la composicion del tag `[small]` en `com.sn.lib.text.SnText` (`applyPrefixTags`): la sustitucion small caps 1:1 con skip verbatim de codigos de color, secuencias section-sign y tags MiniMessage. Los glifos esperados se escriben con escapes `\uXXXX`.

- `void lowercaseAlphabetMapsToSmallCaps()` - el alfabeto a-z completo mapea exactamente al diccionario SMALL de 26 glifos.
- `void uppercaseMapsLikeLowercase()` - "ABCXYZ" produce lo mismo que "abcxyz" (la caja no existe en small caps).
- `void enyeKeepsDefaultGlyph()` - la enye minuscula queda intacta y la mayuscula baja a la enye minuscula default U+00F1.
- `void accentedVowelsLoseAccent()` - las vocales acentuadas (y la u con dieresis) de ambas cajas se des-acentuan a los glifos small.
- `void digitsSymbolsAndSpacesPassThrough()` - digitos, simbolos y espacios pasan intactos.
- `void legacyColorCodesSkipped()` - los codigos `&a`/`&l` quedan intactos (la 'a' y la 'l' del codigo no se mapean) y el texto visible se transforma.
- `void legacyHexCodesSkipped()` - `&#ff9b00` queda intacto (los 6 digitos hex minusculas no se mapean) y el texto posterior se transforma.
- `void sectionSignCodesSkipped()` - `§a` y la secuencia bungee completa de 14 chars quedan intactos; el texto visible se transforma.
- `void miniMessageTagsSkipped()` - `<bold>` y `</bold>` quedan intactos y el contenido entre tags se transforma.
- `void literalAngleBracketStillTransforms()` - un `<` sin `>` de cierre es literal y no frena la transformacion ("i<3" -> la i se mapea).
- `void outputLengthAlwaysEqualsInput()` - invariante 1:1: el output mide igual que el input para inputs representativos (alfabeto, codigos, tags, linea mixta).
- `void unchangedLineReturnsSameInstance()` - `assertSame`: una linea sin letras mapeables y un string ya en small caps devuelven la misma instancia.
- `void nullAndEmptyPassThrough()` - null devuelve null y "" devuelve la misma instancia.
- `void tagIsCaseInsensitive()` - `[SMALL]` y `[small]` rinden identico en `SnText.applyPrefixTags`.
- `void smallAndRgbComposeInAnyOrder()` - `[small][rgb]` == `[rgb][small]` (orden interno fijo de aplicacion).
- `void centerMarkSurvivesSmall()` - `[center][small]hi` re-emite la marca `[center]` lider con el resto ya en small caps.

### YamlUpdaterTest
`src/test/java/com/sn/lib/YamlUpdaterTest.java`
12 tests golden sobre `com.sn.lib.yml.YamlUpdater` (`merge(List<String>, List<String>)`, `prune(List<String>, List<String>)`, `isParseable(String)`): el updater always-merge de configs. Contrato probado: las keys faltantes aterrizan CON sus comentarios en la posicion anclada, los valores del usuario y sus keys extra quedan intactos, no existe ninguna key de version marker, y el quoting de keys se normaliza al comparar. Todas las aserciones comparan `List<String>` (texto linea a linea, no arboles parseados). Fixtures: `merge-resource.yml`, `merge-old.yml`, `merge-expected.yml`, `corrupt.yml`.

- `void mergeMatchesGoldenExpected()` - el merge del resource nuevo sobre el disk viejo reproduce byte a byte el golden `merge-expected.yml`.
- `void mergePreservesUserValuesAndExtraKeys()` - valores del usuario (`rows: 3`, titulo custom, prefix) y su key extra `custom-flag` sobreviven.
- `void mergeInsertsNewKeysWithTheirComments()` - una key nueva aterriza con su comentario pegado en la linea anterior, anclada entre sus vecinos del resource.
- `void mergeInsertsWholeMissingSubsection()` - una subseccion completa faltante (`storage:` con header, `type` y `table-prefix`) se inserta entre sus hermanos del resource (`settings:` antes, `messages:` despues).
- `void pruneIsOptInAndRemovesOnlyKeysAbsentFromResource()` - `prune` es opt-in: el merge default conserva `custom-flag`; el prune lo remueve junto a su comentario, conserva los valores del usuario en keys compartidas y el resultado sigue parseando.
- `void pruneIsANoOpWhenDiskMatchesResourceStructure()` - prune sobre estructura identica es no-op exacto.
- `void mergeIsIdempotentOnUpToDateFile()` - merge sobre un archivo ya actualizado es idempotente (igual al expected).
- `void mergedResultHasNoVersionMarkerAndStaysParseable()` - el resultado no contiene `config-version` en ninguna linea y es YAML parseable.
- `void corruptYamlIsDetectedAsUnparseable()` - `isParseable` detecta el fixture corrupto como invalido y el fixture sano como valido.
- `void quotedAndUnquotedKeysCompareEqualOnMerge()` - recurso `foo: 1` contra disco `'foo': 2`: el merge no inserta nada y el valor de disco queda intacto.
- `void quotedResourceKeyInsertsWithItsTextualForm()` - una key quoted del recurso (`"bar": 3`) ausente en disco se inserta conservando las comillas del recurso.
- `void pruneKeepsKeyWhenOnlyQuotingDiffers()` - prune con recurso `foo:` y disco `"foo":` no borra el bloque (solo difiere el quoting).

### Smoke gate de runtime

Ademas de las suites JVM, la lib paso el smoke gate manual en servidor real, en las dos puntas de la matriz soportada:

- Paper 1.21.8 build 60 (target): verde.
- Paper 1.20.4 build 499 (piso de runtime): verde.

En 1.20.4 el arranque es en modo degradado via `SnCompat.probe` (features 1.21+ apagadas con WARN); el smoke valida que SnLib como plugin standalone enciende y apaga limpio en ambas versiones.

### TODOs y limitaciones

Resultado del grep `TODO|FIXME|XXX|placeholder|PENDIENTE` sobre `src/` y `README.md`: NO existe ningun comentario TODO/FIXME/XXX en el codigo fuente. Todos los matches de "placeholder" son terminologia de dominio (PlaceholderAPI, placeholders locales), el "TODO" del README (linea 156) es el "todo" español de "arbol root/sub, TODO tab-completable", y los unicos "pendiente" son texto de mensajes WARN en runtime de `YamlUpdater` ("[update-configs] update-configs esta en false: prune pendiente en <archivo>") y prosa del README sobre el write coalescing ("a lo sumo un write pendiente por archivo"), no tareas pendientes de codigo.

Pendientes reales conocidos (handoff v1.0.0):

- bStats: el service id `26887` (`private static final int BSTATS_SERVICE_ID = 26887` en `src/main/java/com/sn/lib/SnLibPlugin.java`) es un placeholder: falta registrar el servicio SnLib en bstats.org para que ese id sea real (o ajustar la constante al id asignado).
- El WARN de degradacion en 1.20.4 (features gated por `SnCompat` apagadas) no es ejercitable de punta a punta sin un plugin consumer que use esas features: queda diferido a los pilotos.
- Repo GitHub privado + release v1.0.0: pendientes de confirmacion.
- Actualizacion post-release de `sn-core/SKILL.md` y de las skills `sn-deploy`/`sn-change` para el modelo standalone hard-depend: pendiente.
- Pilotos SnTags y SnCrates consumiendo SnLib, con canary de 48h en servidor productivo: pendientes.
- japicmp corre con `ignoreMissingOldVersion=true`: en 1.0.0 el gate es vacuo por no existir version previa publicada; la baseline real del contrato additive-only arranca en 1.0.1.
- Nota de consistencia del handoff: el handoff menciona "114 tests"; el conteo real verificado en esta documentacion (surefire, `mvn test`) es 137 tests en 12 suites, todos verdes (la baseline 1.0.0 cerro con 104 tests en 11 suites; el paso 1 de v1.1 sumo SmallCapsTest con 16 tests y 1 test nuevo en CenterUtilTest; el paso 4 sumo 9 tests a RequirementEngineTest y llevo SemverComparatorTest de 6 a 10; el paso 5 sumo 3 tests de quoting de keys a YamlUpdaterTest).
