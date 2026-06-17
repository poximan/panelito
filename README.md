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

- `app/src/main/java/servicoop/comunic/panelito/data/mqtt`: constantes y configuracion MQTT.
- `app/src/main/java/servicoop/comunic/panelito/services/mqtt`: servicio de conexion, suscripcion, publicacion y difusion interna.
- `app/src/main/java/servicoop/comunic/panelito/fragment`: pantallas operativas.
- `app/src/main/res`: textos, layouts y recursos Android.

## Configuracion y secretos

La configuracion operativa debe mantenerse fuera de git cuando contenga credenciales, hosts reales o endpoints sensibles. Los valores versionados deben ser plantillas o ejemplos sin secreto real.

## Criterios Android

- Centralizar textos visibles en recursos.
- Mantener la logica de dominio fuera de fragments/adapters cuando corresponda.
- Tratar errores de contrato como fallas visibles, no como datos inventados.
- Usar reconexion MQTT como resiliencia tecnica, sin transformarla en fuente alternativa de verdad.
