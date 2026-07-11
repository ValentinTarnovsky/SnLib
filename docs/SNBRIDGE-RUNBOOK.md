# SnBridge - Runbook operativo

> Compañero de SNBRIDGE-SPEC.md. Escrito ANTES de implementar, a proposito: si algo de este
> runbook resulta inaceptable en la practica, el diseño se corrige antes de escribir codigo.
> Audiencia: el operador de OkiMC (proxy Velocity + backends Gens, Gens-Old, Gens-Dev, Work,
> OkiPVP, Lobby, Worldbox) con deploys manuales via SSH/Pterodactyl y restarts a mano.

## 1. Modelo mental de versiones

Con el jar universal hay UNA version de SnLib por servidor (proxy incluido) y un msgset por
plugin. Ejes reales:

```
SnLib en proxy        1 version
SnLib por backend     7 versiones (skew de dias es lo normal, no la excepcion)
msgset por plugin     1 por par proxy/paper (viaja con los jars del plugin)
```

El handshake HELLO negocia el minimo comun por (backend, namespace). Flota mixta NO rompe:
degrada con resultado tipado visible (`UNSUPPORTED_MSGSET`, `UNSUPPORTED_AT_DESTINATION`).
Lo que SI seria roto: silencio. Si hay silencio, algo esta mal (ver seccion 5).

## 2. Reglas duras de deploy (NUNCA violar)

1. **Backends antes que proxy.** SnLib nuevo se instala primero en los backends (en el proximo
   ciclo de restarts manuales), el proxy al final.
2. **Nunca** un release de plugin proxy que EXIJA (hard-requiera, sin degradacion elegante) un
   msgset o verbo por encima del piso (floor) de la flota de backends donde corre. La ventana
   transitoria de msgset mixto que produce la regla 4 con restarts escalonados es ESPERADA y
   esta cubierta: HELLO negocia y los sends resuelven `UNSUPPORTED_MSGSET` tipado mientras dura.
3. **Nunca** rollback de SnLib en un backend por debajo del floor de verbos/msgsets que los
   plugins proxy ya usan (strippea capacidades en silencio para otros plugins).
4. Los pares proxy/paper de un plugin (ej. SnCredits velocity + su consumer paper) se despliegan
   juntos, como hoy.
5. Ningun restart automatico: los restarts los programa el operador a mano, como siempre
   (sn-deploy no reinicia nada; eso no cambia).

## 3. Orden concreto de un rollout de SnLib (ej. 1.2.0 -> 1.2.1)

1. Subir `SnLib-1.2.1.jar` a cada backend (reemplaza el viejo). NO reiniciar todavia si no toca.
2. En cada ciclo natural de restart de cada modalidad, el backend levanta con 1.2.1.
   Flota mixta durante dias: OK por diseño.
3. Cuando TODOS los backends relevantes corren 1.2.1, subir al proxy y reiniciarlo en horario
   de bajo trafico.
4. Verificar: `/snlibv status` en proxy -> todos los backends con handshake READY y la version
   esperada.

## 4. Diagnostico: comandos y que mirar

| Donde | Comando | Que muestra |
|-------|---------|-------------|
| Backend | `/snlib bridge status` | handshake por namespace, versiones negociadas, cola, drops/expirados, NACKs, frames HMAC invalidos |
| Proxy | `/snlibv status` | tabla agregada por backend: frame, msgset, estado, cola, drops |
| Proxy | `/snlibv allowlist-audit` | diff de allowlists efectivas del verbo console entre backends |

Los NACKs (comando denegado, verbo no soportado, msgset viejo) aparecen rate-limited en el log
del proxy: un solo lugar que mirar, no 8 consolas.

## 5. Checklist "no llega un mensaje"

En orden:

1. **Hay handshake?** `/snlibv status`. Si el backend figura sin handshake:
   - Backend vacio? El handshake necesita un jugador carrier. Sin jugadores no hay canal. Punto.
   - SnLib instalado y arrancado en ese backend? (`/snlib version` en su consola)
   - Version de SnLib demasiado vieja para el frame? El status lo dice explicito.
2. **Estado WARMING?** Acaba de reiniciar el backend y todavia no entro el primer jugador, o
   entro hace instantes y el resync esta en curso. Esperar al primer join; el plugin decide que
   mostrar mientras tanto (eso es responsabilidad del consumer, no del transporte).
3. **Resultado del send?** Todo send devuelve resultado terminal del enum unico
   `SnDeliveryResult`. Buscar en el log del plugin proxy:
   `EXPIRED_TTL` (expiro en cola: sin carrier, sin handshake, o carrier caido a mitad de chunk),
   `UNSUPPORTED_MSGSET` (actualizar SnLib o el consumer en ese backend),
   `UNSUPPORTED_AT_DESTINATION` (solo verbos: SnLib de ese backend no conoce el verbo),
   `DENIED_BY_ALLOWLIST` (solo verbos, ver punto 6), `UNKNOWN_SERVER` (typo en el nombre).
4. **HMAC drops?** `/snlib bridge status` en el backend. Contador de HMAC invalido subiendo =
   el forwarding secret difiere entre `velocity.toml` y `paper-global.yml` de ese backend
   (tipicamente despues de rotar el secret en uno solo de los lados).
5. **detectLegacy avisando?** "contraparte desactualizada detectada" = un lado del plugin quedo
   en el stack viejo durante una migracion a medias. Actualizar el lado que falta.
6. Nada de lo anterior y sigue mudo: revisar que el plugin proxy declare `dependencies: snlib`
   y que el namespace reclamado sea el mismo string en ambos lados.

## 6. Allowlist del verbo console

- Vive en `plugins/SnLib/config.yml` de CADA backend (backend-autoritativa a proposito: un
  proxy comprometido no puede auto-ampliarse permisos).
- Patrones anclados por argumento: `crates key give <player> vote <int:1..64>`. Prohibido
  `crates key give *`.
- Despues de tocar la allowlist de un backend: correr `/snlibv allowlist-audit` y verificar que
  el diff entre backends sea el esperado. El merge de ymls de sn-deploy PRESERVA divergencias
  locales: sin el audit, un comando permitido en Gens y olvidado en Work es un ghost incident.
- Un rechazo se ve como NACK en el proxy con el patron que fallo. No es un bug del bridge: es
  la allowlist trabajando.

## 7. Rotacion del forwarding secret

El HMAC del bridge usa el forwarding secret moderno de Velocity por default. Ubicaciones reales:
en el proxy es el archivo `forwarding.secret` (apuntado por `forwarding-secret-file` en
`velocity.toml`), en cada backend `config/paper-global.yml` (`proxies.velocity.secret`). Al rotarlo:

1. Actualizar el archivo `forwarding.secret` del proxy y el `paper-global.yml` de TODOS los
   backends en la misma ventana de mantenimiento (esto ya es asi hoy: sin secret coherente los
   jugadores no entran).
2. El bridge se re-handshakea solo: HELLO es por conexion de carrier, asi que el primer join
   tras cada restart rearma el canal. Contadores de HMAC invalido durante la ventana son
   esperables; despues deben quedar en cero.
3. Si se prefiere desacoplar el bridge de esa rotacion: configurar el secreto dedicado
   (`bridge.hmac-secret` en `plugins/SnLib/config.yml` + config equivalente en el proxy) en
   TODOS los servers. Un secreto mas que mantener coherente: decision del operador.

## 8. Limites que no son bugs

- **Backend vacio = inalcanzable.** Plugin messaging viaja sobre conexiones de jugadores. Nada
  llega a (ni sale de) un backend sin jugadores. Flujos pagos usan persistencia DB
  (`pending_commands` de SnCredits) y se entregan al proximo join. Un verbo `console` a un
  backend vacio expira con `EXPIRED_TTL`, visible y contado: comportamiento correcto.
- **Resync = primer join post-restart.** No hay estado sincronizado antes de eso; WARMING lo
  hace explicito.
- **At-most-once.** El bridge nunca reintenta solo. Reintentos y deduplicacion son del consumer,
  con su DB, si el caso lo paga.

## 9. Matriz rapida de compatibilidad

| Situacion | Comportamiento |
|-----------|----------------|
| Proxy 1.2.1, backend 1.2.0, frames compatibles | Negocia el minimo; features nuevas del frame no se usan con ese backend |
| Proxy exige msgset 3, backend consumer msgset 2 | `UNSUPPORTED_MSGSET` tipado en cada send + NACK; nada explota |
| Verbo nuevo del proxy, SnLib viejo en backend | `UNSUPPORTED_AT_DESTINATION`; actualizar SnLib de ese backend |
| Plugin proxy viejo (canal legacy) + consumer nuevo | Silencio de protocolo; el lado NUEVO loguea "contraparte desactualizada" via `detectLegacy` (el lado viejo es codigo viejo: sigue mudo) |
| SnLib ausente en backend | Sin handshake; visible en `/snlibv status` |
| Cliente hackeado manda frames al canal | HMAC invalido -> descarte + contador |
| Frame autentico capturado y reflejado a la otra direccion | Check de direccion del receptor en decode -> descarte + contador propio |
