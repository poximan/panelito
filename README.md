# panelito

App Android de campo para observar el estado operativo publicado por Lechuza y disparar acciones acotadas por MQTT.

## Responsabilidad

`panelito` es un consumidor movil liviano. Su responsabilidad es presentar estados operativos, eventos recientes y comandos permitidos para personal de campo, sin consultar bases ni APIs internas de los servicios productores.

No es fuente de verdad de ningun dominio operativo. Puede conservar datos locales para renderizar pantalla, reconexion o continuidad de interfaz, pero la verdad vigente pertenece a los servicios centrales.

## Integracion MQTT

La app se conecta al broker configurado y consume los contratos versionados de `lechuza-server`.
El acceso se aprovisiona en cada dispositivo y se guarda cifrado; no se incorpora a la compilación ni a Git.

Topicos consumidos principales:

- `lechu/v1/modem/status`
- `lechu/v1/exemys/grd/summary`
- `lechu/v1/exemys/grd/disconnected`
- `lechu/v1/generators/edif-estivariz/status`
- `lechu/v1/generators/edif-fontana/status`
- `lechu/v1/email/status`
- `lechu/v1/email/event`
- `lechu/v1/proxmox/status`
- `lechu/v1/services/lechu/status`
- `lechu/v1/charito/status`

Topicos de comando/respuesta:

- `lechu/v1/rpc/request/{accion}`
- `lechu/v1/rpc/response/{clientId}/{corr}`

Acciones RPC vigentes:

- `get_global_status`
- `get_modem_status`
- `get_email_events`
- `send_email_test`

## Fronteras

- No consume HTTP de `lechuza-server`.
- No accede a SQLite ni archivos internos de ningun servicio.
- No consume directamente `charodaemon/host/{clientId}/*`; el estado agregado llega por `lechu/v1/charito/status`.
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

`MainActivity` compone y navega. `MqttSession` y `data/mqtt` son compartidos porque administran una sola conexión al broker mientras la app está en primer plano. No hay DAO por pestaña: Panelito consume proyecciones MQTT y solo conserva localmente eventos de correo para continuidad visual.

## Configuracion y secretos

La configuracion operativa debe mantenerse fuera de git cuando contenga credenciales, hosts reales o endpoints sensibles. Los valores versionados deben ser plantillas o ejemplos sin secreto real.

## Aprovisionamiento MQTT

La app solicita el acceso MQTT al primer ingreso. Se puede modificar con el icono
de configuración junto al estado del broker. Se utiliza el módulo compartido
`../../../platform/android-credentials`; el APK no contiene credenciales.
Ver su README para almacenamiento, TLS y provisión por dispositivo.
`local.properties` contiene únicamente la ruta local del SDK.
