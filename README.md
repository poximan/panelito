# panelito

App Android de campo para observar el estado operativo publicado por Lechuza y disparar acciones acotadas por MQTT.

## Responsabilidad

`panelito` es un consumidor movil liviano. Su responsabilidad es presentar estados operativos, eventos recientes y comandos permitidos para personal de campo, sin consultar bases ni APIs internas de los servicios productores.

No es fuente de verdad de ningun dominio operativo. Puede conservar datos locales para renderizar pantalla, reconexion o continuidad de interfaz, pero la verdad vigente pertenece a los servicios centrales.

## Integracion MQTT

La app se conecta al broker configurado y consume los contratos declarados en `../docs/contratos-sistema.md`.

Topicos consumidos principales:

- `lechuza-server/router/status`
- `lechuza-server/modbus/grd/summary`
- `lechuza-server/modbus/grd/disconnected`
- `lechuza-server/modbus/ge/edif-estivariz/status`
- `lechuza-server/modbus/ge/edif-fontana/status`
- `lechuza-server/email/status`
- `lechuza-server/email/event`
- `lechuza-server/pve/status`
- `panelexemys/status`
- `charito/state`

Topicos de comando/respuesta:

- `lechuza-server/rpc/req/{accion}`
- `lechuza-server/rpc/res/{clientId}/{corr}`

Acciones RPC vigentes:

- `get_global_status`
- `get_modem_status`
- `send_email_test`

## Fronteras

- No consume HTTP de `lechuza-server`.
- No accede a SQLite ni archivos internos de ningun servicio.
- No consume directamente `charodaemon/host/{clientId}/*`; el estado agregado llega por `charito/state`.
- No debe compensar contratos incompletos con canales alternativos.

## Estructura

| Pestaña | Presentación | Contrato/negocio | Adaptador |
|---|---|---|---|
| Dashboard | `DashboardFragment.kt` | `DashboardGrdParser.kt`, modelos de conectividad | `DisconnectedGrdAdapter.kt` |
| Proxmox | `ProxmoxFragment.kt` | `ProxmoxStateParser.kt`, `ProxmoxState.kt`, `ProxmoxVm.kt` | `ProxmoxVmAdapter.kt` |
| Charito | `CharitoFragment.kt` | `CharitoStateParser.kt`, `CharitoState.kt` | `CharitoInstanceAdapter.kt` |
| Eventos de email | `EmailEventsFragment.kt` | `EmailServiceState.kt`, `EmailEvent.kt` | `EmailEventsAdapter.kt` |
| Teléfonos | `TelefonosFragment.kt` | recursos versionados de teléfonos | `TelefonosAdapter.kt` |
| Ayuda | `CheatSheetFragment.kt` | catálogo local de accesos y estados | `CheatSheetAdapter.kt` |

`MainActivity` compone y navega. `MQTTService` y `data/mqtt` son compartidos porque administran una sola conexión al broker. No hay DAO por pestaña: Panelito consume proyecciones MQTT y no persiste datos operativos.

## Configuracion y secretos

La configuracion operativa debe mantenerse fuera de git cuando contenga credenciales, hosts reales o endpoints sensibles. Los valores versionados deben ser plantillas o ejemplos sin secreto real.

La metodología general está en `../../../metodologia.txt`.
