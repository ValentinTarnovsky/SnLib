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
- Regla dura: actualizar SnLib.jar requiere restart del server. Jamas
  hot-reload de la lib (classloader compartido con ~57 consumers).

## Entrypoint: SnPlugin obligatoria + requiredApiLevel()

La UNICA via de inicializacion es extender `com.sn.lib.SnPlugin`:

```java
public final class MyPlugin extends SnPlugin {
    protected int requiredApiLevel() { return SnApi.LEVEL; }
    protected SnSpec buildSpec() {
        return SnSpec.builder().config("config.yml").lang().guis().build();
    }
    protected void onInnerEnable() {
        // registrar comandos, guis, items, db, etc. sobre el contexto Sn
    }
}
```

`requiredApiLevel()` devuelve `SnApi.LEVEL` inlineado en el bytecode del
consumer al compilar: si el `SnLib.jar` instalado es mas viejo que el nivel
requerido, el consumer se deshabilita limpio con mensaje y URL de descarga,
sin `NoSuchMethodError` ni `NoClassDefFoundError`.

## Compatibilidad

- Floor de runtime: 1.20.4. Target: 1.21.8. Versiones 1.22+ desconocidas
  arrancan con WARN forward, jamas hard-fail.
- Floor 1.20.4 requiere JVM Java 21: los classfiles son release 21; con JVM 17
  falla con `UnsupportedClassVersionError` antes de cualquier probe.
- Cero NMS/packets: 100% API de Paper y Adventure. Toda API posterior a 1.20.4
  degrada con UN WARN via feature-probe.

## Distribucion para desarrolladores

- `mvn install -f <ruta>/SnLib/pom.xml` publica `com.sn:snlib` al `.m2` local.
- JitPack NO soportado (el repo es privado).
- Templates de consumer en `docs/`: `consumer-pom-template.xml` (pom minimo,
  scope provided) y `snlib-consumer-rules.pro` (reglas ProGuard).
- Specs golden de configuracion en `docs/menu-example.yml` (GUIs) y
  `docs/item-example.yml` (items fisicos).

## Modulos

Documentacion detallada por modulo pendiente; se completa con la matriz de
campos de ambos specs golden al cierre de v1.0.0.

- **yml**: pendiente.
- **auto-update**: pendiente.
- **menus (gui)**: pendiente.
- **items**: pendiente.
- **commands**: pendiente.
- **lang**: pendiente.
- **db**: pendiente.
- **papi**: pendiente.
- **debug**: pendiente.
- **text (MiniMessage + [rgb] + [center])**: pendiente.
- **scheduler**: pendiente.
- **actions / requirements**: pendiente.
- **cooldowns**: pendiente.
- **economy**: pendiente.
- **reload**: pendiente.
- **bossbars**: pendiente.
- **holograms**: pendiente.
- **cron**: pendiente.
- **leaderboards**: pendiente.
- **discord webhooks**: pendiente.
- **utils**: pendiente.
