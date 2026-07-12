# SnBridge - Especificacion de diseño (SnLib v1.2)

> Estado: Fase A (wire core, com.sn.lib.bridge.wire) IMPLEMENTADA; el resto planificado.
> Este documento es la spec de diseño aprobada el 2026-07-11;
> se ejecuta cuando el owner lo decida. No describe codigo existente (para eso esta SNLIB-DOCS.md).
> Origen: analisis multi-agente sobre SnCredits, SnKeyAll, SnStaffLink y la arquitectura de SnLib
> (4 lectores + diseño de opciones + 3 criticas adversariales: versionado, operacion, complejidad).

## 0. Decision y alcance

**Decision tomada**: SnLib pasa a ser un jar universal (el mismo `SnLib-1.2.0.jar` es plugin de Paper
Y plugin de Velocity) que provee:

- **Tier 1 - Canales tipados**: framework de mensajeria proxy<->backend que reemplaza el stack
  duplicado de SnCredits/SnKeyAll (~1840 lineas de codec/listeners/plumbing copy-paste).
- **Tier 2 - Verbos genericos**: SnLib en el backend ejecuta por si mismo un set acotado de verbos
  (comandos con allowlist, mensajes/sonidos/titulos, bossbar, actions), de modo que un plugin
  Velocity-only simple NO necesita jar Paper propio.

**Explicitamente fuera de alcance** (con trigger de revision, ver seccion 13):
- Verbo de menus remotos (GUI dirigida por el proxy).
- Redis / transporte alternativo / SnSyncMap / SnAckedQueue.
- Verbo de query PAPI round-trip.

**Limites honestos declarados** (no son bugs, son fisica del transporte):
- Plugin messaging viaja SIEMPRE sobre la conexion de un jugador. Un backend vacio es inalcanzable
  en ambas direcciones. Flujos pagos (rewards, compras) siguen necesitando persistencia en DB
  (patron `pending_commands` de SnCredits, que se conserva).
- "Resync al reconectar" significa "resync al primer join post-restart". El estado WARMING existe
  para que eso sea visible y manejable, no invisible.
- Los verbos son at-most-once. Prohibido usarlos para entregas pagas sin persistencia propia.

## 1. Por que (hallazgos del analisis)

Stack actual de cada par proxy+paper (SnCredits ~1400 lineas, SnKeyAll ~440):

1. **Wire format por `ordinal()` de enum sin handshake**: agregar un MessageType en el medio corre
   todos los ordinales; versiones mixtas proxy/backend decodifican MAL en silencio.
2. **`decode()` devuelve `Map<String,Object>`** con casts sin chequear en cada consumidor.
3. **Cero correlacion ni timeouts**: si el proxy no responde, la GUI no abre y nadie se entera.
4. **Caps de tamaño ignorados**: el config JSON de SnCredits viaja proxy -> backend, que es la
   direccion capeada del protocolo (~32KB serverbound), y `writeUTF` agrega ademas un techo
   independiente de 64KB. Hoy no hay chunking: crecer el config es una bomba de tiempo.
5. `EXECUTE_COMMAND` ejecuta consola sin whitelist ni autenticacion de envelope; la seguridad
   depende solo de que el plugin Velocity haga `ForwardResult.handled()` (y si ese plugin crashea
   o no carga, Velocity forwardea bytes del cliente directo al backend).
6. Drops 100% silenciosos; JSON parseado a mano (`JsonHelper` string-scanner) en el lado Paper.

Consumidores inmediatos: SnCredits, SnKeyAll, SnPlayerCount, ActionEngine `[connect]`.
Futuros naturales: SnStaffLink v2 (hoy polling MySQL), SnGGWave, cualquier plugin Velocity nuevo.

## 2. Distribucion: un solo jar, dos plataformas

El mismo artefacto `com.sn:snlib` contiene:

- `plugin.yml` (Bukkit, entry `com.sn.lib.SnLibPlugin`, sin cambios).
- `velocity-plugin.json` (entry `com.sn.lib.velocity.SnLibVelocity`, id `snlib`).

Deploy identico al modelo mental actual: `SnLib.jar` en `plugins/` del proxy igual que en cada
backend. Los plugins Velocity declaran `"dependencies": [{"id": "snlib"}]` como hoy declaran
`depend: [SnLib]` en Paper (los classloaders de Velocity delegan entre plugins, mismo modelo).

Reglas de carga:
- Velocity carga clases lazy: el entry de Velocity NUNCA toca paquetes Bukkit-bound y viceversa.
- Peso muerto aceptado: el jar lleva drivers sqlite/mysql etc. que el proxy no usa. Inofensivo.
- Precondiciones verificadas antes de implementar: proxy corre Java 21 (los plugins velocity
  actuales ya son Java 21); Velocity 3.3+.
- bstats: en Velocity se omite en v1.2 (el service id actual es bukkit-only). Opcional a futuro.

Ventaja clave sobre el diseño alternativo (cliente shaded `snbridge-velocity`): NO hay copia del
protocolo congelada dentro de cada plugin proxy. Un fix de framing = actualizar SnLib.jar en proxy
y backends, sin rebuild de los plugins. Y desaparece la trampa de relocacion de classloaders.

## 3. Layout de paquetes

```
com.sn.lib.bridge.wire       nucleo 100% neutral (CERO imports de Bukkit/Velocity):
                             SnBuf, SnWireType<T>, frame, HMAC, chunking, HELLO, codecs.
                             Compartido literalmente por ambos lados.
com.sn.lib.bridge            API publica lado Paper: accessor sn.bridge(), SnBridgeChannel,
                             SnDelivery, SnBridgeState. Bukkit-bound.
com.sn.lib.bridge.internal   transporte plugin-message Paper, cola de carrier, reassembly,
                             demux por namespace via TenantRegistry, listener unico en
                             ListenerHub, paso nuevo numerado en Sn.shutdown()
                             (hoy pasos 0-13; el bridge agrega el 14).
com.sn.lib.velocity          bootstrap Velocity (SnLibVelocity, @Plugin id=snlib) + API publica
                             proxy: SnProxy.init(...), SnProxyBridge, verbos cliente.
com.sn.lib.velocity.internal transporte Velocity, registro de canales, cola por backend,
                             agregacion de estado de flota.
```

- Todo `*.internal` fuera del contrato semver (regla existente).
- `com.sn.lib.bridge.*` y `com.sn.lib.velocity.*` nacen experimentales: EXCLUIDOS del gate
  japicmp y de `SnApi.LEVEL` hasta terminar la migracion de SnCredits (asi la API no se congela
  en el momento de maxima ignorancia). Mecanismo: patrones `<exclude>` por paquete en el pom,
  el estilo ya usado para `com.sn.lib.**.internal.**` (la exclusion NO es por anotacion).
  `@SnExperimental` es una anotacion NUEVA puramente documental que marca la ventana.
  Recien al congelar: quitar los excludes, `SnApi.LEVEL = 3` (hoy es 2) + baseline japicmp.
- Gate de CI nuevo: test que escanea el bytecode de `bridge.wire` y `velocity.*` y falla si
  aparece una referencia a `org.bukkit.*` (y en `bridge.wire`, tampoco `com.velocitypowered.*`).

## 4. Canales y namespaces

- Un canal por consumidor: `snlib:ext/<namespace>` (NUNCA `snlib:ext:<ns>`: el segundo `:` es
  ilegal en `NamespacedKey`/`MinecraftChannelIdentifier`).
- Verbos genericos: canal propio `snlib:bridge`.
- Registro first-claim-wins en registry compartido keyeado por owner (TenantRegistry): reclamar
  un namespace ya tomado por otro tenant es hard-error en el `channel()`, no fan-out silencioso.
- Baja del tenant barre sus suscripciones (patron existente de teardown por owner).

## 5. Formato de frame (versionado en 2 niveles)

### Header
```
magic          u8      constante fija (deteccion de basura temprana)
frameVersion   u8      version del framing (chunking/correlacion/HMAC layout)
flags          u8      bit0 = direccion (FLAG_TO_PROXY: set = backend->proxy)
msgId          u32     correlacion y reassembly de chunks
chunkIndex     u16
chunkCount     u16
hmacTag        16B     HMAC-SHA256 truncado a 16B sobre (header[0..11) + sessionNonce 8B + body)
```

- **Clave HMAC**: el forwarding secret moderno de Velocity, YA presente en ambos lados: en el
  proxy vive en el archivo `forwarding.secret` (apuntado por `forwarding-secret-file` en
  `velocity.toml`) y en cada backend en `config/paper-global.yml` (`proxies.velocity.secret`).
  Cero secretos nuevos que gestionar. Nota de implementacion: ninguna API publica lo expone, asi
  que SnLib lo lee de disco en ambos lados (resolviendo la indireccion del toml en el proxy).
  Fallback configurable: secreto dedicado en `plugins/SnLib/config.yml` por si algun dia se
  quiere desacoplar de la rotacion del forwarding secret.
- La direccion viaja como bit0 del byte flags DENTRO del header firmado (no hay byte extra en el
  input del HMAC) y el receptor EXIGE su direccion esperada en `FrameCodec.decode`: el HMAC hace
  inviolable el flag, el check del receptor es lo que rechaza un frame autentico capturado y
  reflejado a la otra pierna (con contador propio, distinto de basura).
- Los 8 bytes de nonce de sesion estan SIEMPRE en el input del HMAC: `HANDSHAKE_NONCE = 0` para
  HELLO/HELLO_ACK y todo frame pre-handshake; post-handshake `sessionNonce = nonceBackend XOR
  nonceProxy`, con lo que un frame capturado en otra sesion no verifica (anti-replay).
- Frames con HMAC invalido o recibidos antes de handshake valido se DESCARTAN con contador
  visible. Esto cierra a la vez: spoofing por conexion directa al backend, y el agujero de
  canal-sin-reclamar (si el plugin proxy no cargo, Velocity forwardea bytes del cliente; con
  HMAC son basura descartada, no comandos ejecutados).
- Espejo backend->cliente: SnLib en el proxy HUNDE (`ForwardResult.handled()`) todo canal
  snlib REGISTRADO (cada namespace reclamado + `snlib:bridge` pre-registrado al init + los
  legacy de detectLegacy), en ambas direcciones, aun con el plugin consumidor crasheado.
  Limite de plataforma declarado: Velocity NO dispara PluginMessageEvent para canales que
  nadie registro, asi que el trafico de un canal snlib SIN reclamar se forwardea tal cual;
  el piso que lo hace inerte en ambos extremos es el HMAC (basura sin la clave), y los
  nombres de canal se anuncian a los clientes via minecraft:register (no son secreto).
- Correlacion de respuestas: bit1 de flags (`FLAG_RESPONSE`, firmado) marca el frame que
  RESPONDE al msgId que lleva; sin el flag un push cuyo msgId colisione con un request en
  vuelo jamas puede ser tragado como su respuesta.

### Body
```
wireId         UTF     string estable por tipo de mensaje (ej. "sncredits:open_confirm")
msgVersion     u16     version del mensaje
bodyLen        u32     LENGTH-PREFIX del bloque de campos
campos         ...     escritos por el encoder del SnWireType
```

- El decoder recibe `(SnBuf, int version)`: puede branchear por version, y los bytes sobrantes
  de un emisor mas nuevo se saltean gracias al length-prefix (evolucion aditiva REAL).
- Codecs = lambdas posicionales explicitas. NUNCA reflection sobre records: el pipeline
  sn-obfuscate/ProGuard rompe reflection.
- Cada `SnWireType` exige `selfTest()` de round-trip que corre en unit tests: el drift de orden
  de campos entre encoder y decoder falla en CI, no en produccion.
- wireIds: strings estables, NUNCA derivados de nombres de clase, con ledger de "nunca reusar un
  ID" en este documento (seccion 12).

### Chunking (asimetrico, explicito)
- proxy -> backend: fragmenta a ~24KB (el cap serverbound real es ~32KB; el config de SnCredits
  viaja en ESTA direccion).
- backend -> proxy: hasta ~1MB.
- Buffers de reassembly: capeados por conexion y descartados en disconnect/switch del carrier
  (correctitud + defensa contra DoS de memoria por cliente spoofeado).

## 6. Handshake HELLO

Al primer carrier disponible por `(backend, namespace, sesion de conexion)`:

1. Companion (backend) manda `HELLO`: rango de frameVersion soportado, msgsetVersion del
   namespace, version de SnLib, su mitad aleatoria del nonce de sesion (i64), y para
   `snlib:bridge` el catalogo de verbos con la version de vocabulario de cada uno (ej. version
   del set de tags de ActionEngine).
2. Proxy responde `HELLO_ACK` con lo propio, incluida su mitad del nonce. Se negocia el minimo
   comun y ambos lados firman desde ahi con `sessionNonce = nonceBackend XOR nonceProxy`.
3. La cola de envios pendientes se flushea ESTRICTAMENTE despues del ACK. Nunca viaja un mensaje
   de aplicacion sin handshake negociado.

- Estado por namespace: `WARMING -> READY` con callback `onState`. El plugin decide que hacer si
  le llega interaccion antes del warmup (mensaje al jugador, retry, etc.) en vez del fallo
  silencioso actual.
- Ciclo de vida: HELLO es POR CONEXION de carrier; el estado del namespace es el agregado de las
  conexiones handshakeadas vivas (READY mientras quede al menos una). Un envio chunked cuyo
  carrier se desconecta a mitad de transferencia se ABORTA y resuelve como `EXPIRED_TTL` del
  lado emisor: nunca perdida silenciosa.
- Todas las constantes de version (frameVersion, msgsetVersion por plugin, vocabularios de verbos)
  se generan en build-time desde una sola fuente con check de igualdad en CI (precedente real de
  drift manual: SnCredits `@Plugin 1.4.0` vs pom `1.11.1`).

## 7. Semanticas de mensajeria (Tier 1)

Tres primitivas, todas at-most-once como piso documentado.

**Un solo enum de resultado para ambos tiers** (`SnDeliveryResult`), siempre TERMINAL:

```
SENT                        entregado a una conexion viva (Tier 1: aca termina; no hay ack de app)
DELIVERED                   solo verbos: el backend confirmo ejecucion (ACK)
DENIED_BY_ALLOWLIST         solo verbos: NACK del backend
UNSUPPORTED_AT_DESTINATION  solo verbos: el backend no conoce el verbo o su vocabulario
UNSUPPORTED_MSGSET          la negociacion HELLO dice que el destino no habla este msgset
EXPIRED_TTL                 murio en cola (sin carrier, sin handshake, o carrier caido a mitad)
UNKNOWN_SERVER              el nombre de server no existe
```

El ENCOLADO no es terminal y por eso NO es un valor del enum: un send sin carrier o pre-handshake
queda en cola (observable via `ch.pending()` y contadores) y su future resuelve recien cuando el
mensaje sale (`SENT`) o muere (`EXPIRED_TTL`). Regla de implementacion: el future SIEMPRE resuelve.

1. **Fire-and-forget**: `ch.send(...)`. Devuelve SIEMPRE `CompletableFuture<SnDelivery>` con
   resultado terminal, nunca void, nunca drop silencioso. Cola de carrier con TTL por clase de
   mensaje (config: minutos; comandos: segundos; configurable), cap de tamaño, callback
   `onUndeliverable` y contadores. Convierte "se perdio en silencio" en "expiro y quedo contado",
   y evita el incidente nuevo de "se ejecuto 20 minutos tarde cuando entro el primer jugador".
2. **Request/response**: correlationId (msgId) + timeout configurable. En Paper devuelve `SnFuture`
   (idioma existente: `thenSync`/`exceptionally`); en Velocity `CompletableFuture`. Reemplaza los
   pares implicitos `REQUEST_CONFIG -> SYNC_CONFIG` etc.
3. **Verbos** (Tier 2, seccion 8): mensajes predefinidos servidos por SnLib mismo.

## 8. Verbos genericos (Tier 2)

Servidos por SnLib backend en `snlib:bridge`. Catalogo v1.2 (cerrado; agregar un verbo nuevo es
propuesta propia con threat model):

| Verbo | Que hace en el backend | Consumidor inmediato |
|-------|------------------------|----------------------|
| `console` | `Bukkit.dispatchCommand(console, cmd)` filtrado por allowlist | SnKeyAll, SnCredits resend, futuros |
| `message` / `title` / `actionbar` / `sound` | via pipeline de texto y compat de SnLib | SnKeyAll, SnCredits |
| `bossbar` | glue sobre `BossBarUtil` existente: `create(id)` + `show/hide(player, id)` + `setText/setProgress(id, ...)` | SnKeyAll (mata su jar Paper) |
| `actions` | lista de action-strings al ActionEngine existente | generaliza casi todo |
| `heartbeat` | interno del handshake/diagnostico | infra |

Reglas duras de los verbos:
- **Nunca void**: cada llamada devuelve future con resultado terminal del enum unico
  `SnDeliveryResult` (seccion 7); los verbos ademas pueden resolver `DELIVERED` o
  `DENIED_BY_ALLOWLIST` porque llevan ACK/NACK de aplicacion.
- **Allowlist del verbo console**: backend-autoritativa en `plugins/SnLib/config.yml`, patrones
  ANCLADOS por argumento (no wildcards de prefijo: `crates key give <player> vote <int:1..64>`
  si, `crates key give *` no), rate limit por namespace. Rechazos = NACK visible en el proxy
  (rate-limited), no silencio.
- **Audit de drift**: comando proxy que pide y difea las allowlists efectivas de todos los
  backends (el merge de ymls por servidor de sn-deploy hace que el drift sea la norma; tiene que
  ser visible, no un ghost incident).
- **At-most-once**: documentado y prohibido para entregas pagas sin persistencia DB propia.
- Vocabularios versionados en HELLO: si el proxy manda un action-tag que el ActionEngine de ese
  backend no conoce, es `UNSUPPORTED_AT_DESTINATION`, no un warn perdido en una consola.

## 9. API sketch

### Records compartidos (modulo common del plugin, cero imports de plataforma)
```java
public record OpenConfirm(UUID player, String itemId, double price) {
  public static final SnWireType<OpenConfirm> TYPE = SnWireType.of(
      "sncredits:open_confirm",   // wireId estable, en el ledger, nunca se reusa
      2,                          // msgVersion actual
      (buf, m) -> { buf.uuid(m.player()); buf.str(m.itemId()); buf.f64(m.price()); },
      (buf, version) -> {
        UUID p = buf.uuid(); String item = buf.str();
        double price = version >= 2 ? buf.f64() : 0.0;  // aditivo real via length-prefix
        return new OpenConfirm(p, item, price);
      });
}
// en tests: OpenConfirm.TYPE.selfTest(new OpenConfirm(uuid, "key_vote", 500.0));
```

### Lado Paper (companion fino = SnPlugin consumidor normal)
```java
public final class SnCreditsBridge extends SnPlugin {
  // estado final post-congelamiento; durante la ventana experimental: return 2
  @Override protected int requiredApiLevel() { return 3; }
  @Override protected void onInnerEnable() {   // hook real de SnPlugin (no existe start(Sn))
    Sn sn = sn();
    SnBridgeChannel ch = sn.bridge().channel("sncredits", /*msgset*/ 3);
    ch.register(OpenConfirm.TYPE, ShopClick.TYPE, SyncBalance.TYPE, SyncConfig.TYPE);

    ch.on(OpenConfirm.TYPE, (player, msg) ->      // handler ya en main thread via SnScheduler
        sn.guis().open(player, "confirm", Map.of("item", msg.itemId())));
    ch.onState(state -> { if (state == SnBridgeState.READY) refreshOpenGuis(); });
    ch.detectLegacy("sncredits:main");            // ventana de migracion: loguea "proxy viejo"

    ch.send(player, new ShopClick(player.getUniqueId(), cat, item));
    ch.request(RequestConfig.INSTANCE, SyncConfig.TYPE, Duration.ofSeconds(5))
      .thenSync(cfg -> configCache.accept(cfg))
      .exceptionally(t -> { getLogger().warning("config timeout"); return null; });
  }
}
```

### Lado Velocity (plugin proxy, SnLib como dependencia de plugin, SIN shade)
```java
@Plugin(id = "sncredits", dependencies = {@Dependency(id = "snlib")})
public final class SnCreditsVelocity {
  @Subscribe void onInit(ProxyInitializeEvent e) {
    SnProxyBridge bridge = SnProxy.init(this, proxy, logger)
        .channel("sncredits", /*msgset*/ 3);
    bridge.register(OpenConfirm.TYPE, ShopClick.TYPE, SyncBalance.TYPE, SyncConfig.TYPE);

    bridge.on(ShopClick.TYPE, (src, msg) -> shop.handleClick(src.player(), msg));
    bridge.respond(RequestConfig.TYPE, (src, req) -> new SyncConfig(configBlob)); // chunkea ~24KB

    bridge.to("gens").send(new OpenConfirm(uuid, itemId, 500.0), SnSendOpts.ttl(ofSeconds(10)))
        .thenAccept(d -> { if (d.result() != SnDeliveryResult.SENT) log.warn("gens: " + d.result()); });

    // Verbos (Tier 2): sin jar Paper propio del otro lado
    SnVerbs verbs = SnProxy.verbs(this);
    verbs.on("gens").console("crates key give " + name + " vote 1")
        .thenAccept(r -> { if (r != SnVerbResult.DELIVERED) log.warn("gens console: " + r); });
    // glue nuevo sobre BossBarUtil existente (create(id)/show(player,id)/setText/setProgress)
    verbs.on("work").bossbar(playerUuid, bar -> bar.text("<red>KeyAll en 5m").progress(0.5f));

    bridge.capabilities("work").ifPresentOrElse(
        c -> { if (c.msgset() < 3) log.warn("work corre msgset " + c.msgset()); },
        () -> log.warn("work: sin handshake (backend vacio o SnLib viejo)"));
    log.info(SnProxy.statusReport());  // tabla: backend | frame | msgset | estado | cola | drops
  }
}
```

## 10. Diagnostico (deliverable 1.0, no follow-up)

Para un operador solo con 8 consolas Pterodactyl, "loguea fuerte" es operacionalmente silencio.

- Backend: `/snlib bridge status` -> estado de handshake por namespace, versiones negociadas,
  profundidad de cola, drops/expirados, NACKs, frames con HMAC invalido.
- Proxy: `/snlibv status` (o subcomando por plugin) -> tabla agregada por backend
  (frame/msgset/estado/cola/drops) via `SnProxy.statusReport()`.
- Proxy: `/snlibv allowlist-audit` -> diff de allowlists efectivas entre backends.
- NACKs rate-limited visibles del lado proxy.

## 11. Gates de CI nuevos

japicmp no protege ni un byte del wire. Se agregan:

1. Corpus de fixtures de bytes dorados por mensaje por version (encode actual == fixture;
   decode de fixtures viejos == valores esperados).
2. `selfTest()` obligatorio por `SnWireType` (round-trip en unit tests).
3. Ledger de wireIds usados (seccion 12) con test que verifica no-reuso.
4. Constantes de version generadas en build-time desde una sola fuente + check de igualdad.
5. Test de pureza de plataforma: `bridge.wire` sin referencias Bukkit NI Velocity;
   `com.sn.lib.velocity.*` sin referencias Bukkit.
6. Smoke ampliado: Paper 1.20.4/1.21.8 (existente) + arranque en Velocity con un consumer dummy.

## 12. Reglas de wire y ledger

Reglas (checklist para todo cambio de protocolo):
- Un wireId se usa UNA vez en la historia; deprecar = dejar de emitir, jamas reasignar.
- Campos solo ADITIVOS al final del body; nunca reordenar ni cambiar tipo de un campo existente.
- Cambio incompatible = wireId nuevo (`sncredits:open_confirm_v2` es valido como id nuevo).
- Todo mensaje nuevo entra con fixture dorado + selfTest en el mismo commit.

Ledger de wireIds (se llena durante la implementacion):
```
(reservados de infra) snlib:hello, snlib:hello_ack, snlib:nack, snlib:heartbeat,
                      snlib:verb/console, snlib:verb/message, snlib:verb/title,
                      snlib:verb/actionbar, snlib:verb/sound, snlib:verb/bossbar,
                      snlib:verb/actions
```

## 13. Diferidos con trigger explicito

| Diferido | Trigger para revisitar |
|----------|------------------------|
| Verbo de menus remotos | probablemente nunca: round-trips de clicks, races de doble compra, UI laggy. Las GUIs quedan en consumers Paper finos |
| Redis / transporte SPI | segundo proxy, feature cross-network, dashboard web, o tasa medida de incidentes por backends vacios. La API de canal ya no expone tipos de plugin-messaging en firmas publicas, asi que el asiento esta reservado gratis |
| Cola con acks (at-least-once) | solo junto con claves de idempotencia + dedupe persistente en el backend; sin eso, "compra perdida" se vuelve "compra duplicada" |
| Verbo PAPI query | dos consumidores concretos que lo pidan |
| bstats en Velocity | cuando haya id de servicio velocity |

## 14. Migraciones

Sin flag-day: canales viejos y nuevos coexisten. `detectLegacy` hace que el lado NUEVO escuche
tambien el canal legacy y loguee "contraparte desactualizada" cuando ve trafico viejo. El lado
viejo es codigo viejo: sigue mudo (no puede avisar); por eso la deteccion vive en el lado nuevo
de CADA mitad de la migracion, y requiere que el legacy realmente emita trafico.

**SnKeyAll (primero: chico, valida la API con libertad de romperla)**
- Borrar `common/protocol` + paquetes `messaging` de ambos lados (~440 lineas).
- Definir ~7 records tipados; el lado proxy usa canal tipado + verbos.
- El jar Paper de SnKeyAll DESAPARECE: bossbar + comandos + mensajes son verbos.
- Deploy: SnLib.jar nuevo en backends donde corre KeyAll (proximo ciclo de restarts manuales),
  despues el jar velocity nuevo. Todo hallazgo de API se corrige libremente (aun experimental).

**SnCredits (segundo: el grande, ANTES de congelar la API)**
- Mapeo: `REQUEST_*/SYNC_*` -> request/response; `OPEN_*/UPDATE_*` -> fire-and-forget tipados;
  `buildConfigJson` + `JsonHelper` -> record `SyncConfig` chunkeado (mata el riesgo 64KB).
- El jar Paper queda como consumer FINO (records + handlers de GUI); borra ~1400 lineas de stack.
- Los caches PAPI siguen siendo ConcurrentHashMaps del plugin alimentados por handlers tipados.
- `pending_commands` SE QUEDA (unico camino para flujos pagos con backend vacio), con fix puntual:
  select+delete en una sola transaccion.
- Presupuestar diferencias de timing (warmup de config, primer join post-restart) cubiertas por
  el estado WARMING.

**Cierre**
- Congelar: `SnApi.LEVEL = 3`, baseline japicmp nueva, quitar `@SnExperimental`.
- Adopcion interna: ActionEngine `[connect]` y SnPlayerCount sobre la infra compartida.
- SnStaffLink v2 (polling MySQL -> canal tipado push) como consumidor futuro post-congelamiento.

## 15. Plan de ejecucion por fases

Estimacion realista solo-dev: **7-9 semanas** calendario (multiplicador historico del repo
incluido; la v1.1 fueron 22 pasos). Durante ese lapso los demas plugins no reciben mantenimiento.

- **Fase 0 (hecha)**: spec + runbook escritos ANTES de implementar (este doc + SNBRIDGE-RUNBOOK.md).
  Funcion forzadora: si el runbook da verguenza, el diseño esta mal.
- **Fase A - wire core** (1.5-2 sem): `bridge.wire` completo (SnBuf, frame, HMAC, chunking,
  HELLO, codecs) + selfTest + fixtures dorados + ledger + test de pureza de plataforma.
- **Fase B - lado Paper** (1 sem): `sn.bridge()`, tenancy/teardown, cola de carrier con TTL/cap/
  contadores, WARMING/READY, `detectLegacy`, `/snlib bridge status`, paso nuevo en `Sn.shutdown()`.
- **Fase C - bootstrap Velocity** (1 sem): `SnLibVelocity` + `velocity-plugin.json`, `SnProxy.init`,
  canales tipados lado proxy, cola por backend, `capabilities()`, `statusReport()`, smoke Velocity.
- **Fase D - verbos** (1-1.5 sem): console+allowlist anclada+rate limit+NACKs, message/title/
  actionbar/sound, bossbar, actions con vocabulario versionado, `allowlist-audit`.
- **Fase E - docs y gate** (0.5-1 sem): seccion SnBridge en SNLIB-DOCS al implementar, golden
  spec `docs/bridge-example.yml`, suite completa + smoke 1.20.4/1.21.8 + Velocity.
- **Fase F - migracion SnKeyAll** (2-4 dias).
- **Fase G - migracion SnCredits** (4-7 dias).
- **Fase H - congelamiento**: API level 3, baseline japicmp, release 1.2.0 final.

## 16. Decisiones resueltas y restantes

Resueltas (2026-07-11):
- Distribucion: jar universal unico (Paper + Velocity), sin artefacto cliente shaded. RESUELTO.
- Alcance v1.2: Tier 1 + Tier 2 (verbos basicos, sin menus). RESUELTO.
- Ventana experimental fuera de japicmp hasta migrar SnCredits. ACEPTADA implicitamente por el
  alcance elegido; confirmar al arrancar Fase A.
- Clave HMAC: forwarding secret de Velocity (cero secretos nuevos) con fallback a secreto
  dedicado en config. Default recomendado; confirmar al arrancar Fase A.

- Comando proxy: `/snlibv` con subcomandos `status` y `allowlist-audit`. RESUELTO (el runbook
  ya opera con esos nombres).

Restantes (se deciden durante la implementacion, no bloquean la spec):
- TTLs default por clase de mensaje.
- Formato exacto de `bridge-example.yml` (golden spec).
- Nota util de implementacion: `SnFuture` vive en `com.sn.lib.db` y ya expone
  `wrap(Sn, CompletableFuture)`, el camino natural para que el bridge devuelva SnFuture en Paper.
