package servicoop.comunic.redirectorllamadas.llamadas

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat // Importar esto
import servicoop.comunic.redirectorllamadas.mqtt.MqttPublisher

class LlamadasEntrantes : BroadcastReceiver() {

    private var lastState = TelephonyManager.CALL_STATE_IDLE

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null || context == null) {
            Log.e("LlamadasEntrantes", "onReceive: Intent o Context nulo.")
            return
        }

        Log.d("LlamadasEntrantes", "onReceive: Action = ${intent.action}")

        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            var incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) // Ahora es 'var'

            Log.d("LlamadasEntrantes", "onReceive: Estado del telefono: $stateStr, Numero entrante (del intent): $incomingNumber")

            val currentState = when (stateStr) {
                TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
                TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
                TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
                else -> {
                    Log.w("LlamadasEntrantes", "onReceive: Estado desconocido: $stateStr. Asumiendo IDLE.")
                    TelephonyManager.CALL_STATE_IDLE
                }
            }

            Log.d("LlamadasEntrantes", "onReceive: lastState: $lastState, currentState: $currentState")

            if (currentState == TelephonyManager.CALL_STATE_RINGING && lastState == TelephonyManager.CALL_STATE_IDLE) {
                Log.d("LlamadasEntrantes", "onReceive: Detectada transicion a llamada entrante (RINGING).")

                // Si el numero entrante del intent es nulo, intentar obtenerlo del registro de llamadas
                if (incomingNumber.isNullOrEmpty()) {
                    Log.w("LlamadasEntrantes", "onReceive: Numero entrante nulo o vacio del intent. Intentando obtener del CallLog.")
                    incomingNumber = getLastIncomingCallNumber(context)
                    if (incomingNumber.isNullOrEmpty()) {
                        Log.e("LlamadasEntrantes", "onReceive: No se pudo obtener el numero entrante ni del intent ni del CallLog.")
                    } else {
                        Log.d("LlamadasEntrantes", "onReceive: Numero obtenido del CallLog: $incomingNumber")
                    }
                }

                if (!incomingNumber.isNullOrEmpty()) {
                    val callerName = getContactName(context, incomingNumber)
                    Log.d("LlamadasEntrantes", "onReceive: Llamada entrante FINAL - Numero: $incomingNumber, Nombre: ${callerName ?: "N/A"}")
                    MqttPublisher.publishIncomingCall(context, incomingNumber, callerName)
                    Log.d("LlamadasEntrantes", "onReceive: Solicitud de publicacion enviada a MqttPublisher.")
                } else {
                    Log.e("LlamadasEntrantes", "onReceive: No hay numero de telefono valido para publicar.")
                }
            }
            lastState = currentState
            Log.d("LlamadasEntrantes", "onReceive: lastState actualizado a $lastState")
        }
    }

    private fun getContactName(context: Context, phoneNumber: String): String? {
        var contactName: String? = null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                if (nameIndex != -1) {
                    contactName = cursor.getString(nameIndex)
                    Log.d("LlamadasEntrantes", "getContactName: Nombre de contacto encontrado: $contactName para $phoneNumber")
                } else {
                    Log.w("LlamadasEntrantes", "getContactName: Columna DISPLAY_NAME no encontrada en cursor de contactos.")
                }
            } else {
                Log.d("LlamadasEntrantes", "getContactName: Contacto no encontrado para el numero: $phoneNumber")
            }
        } catch (e: SecurityException) {
            Log.e("LlamadasEntrantes", "getContactName: SecurityException - Permiso READ_CONTACTS denegado para obtener nombre para $phoneNumber. ${e.message}", e)
            contactName = "Permiso denegado"
        } catch (e: Exception) {
            Log.e("LlamadasEntrantes", "getContactName: Error inesperado al obtener nombre de contacto para $phoneNumber: ${e.message}", e)
            contactName = "Error"
        } finally {
            cursor?.close()
        }
        return contactName
    }

    // Nueva función para obtener el último número de llamada entrante del CallLog
    private fun getLastIncomingCallNumber(context: Context): String? {
        // Verificar permiso READ_CALL_LOG antes de intentar leer el CallLog
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            Log.e("LlamadasEntrantes", "getLastIncomingCallNumber: Permiso READ_CALL_LOG no concedido. No se puede leer el registro de llamadas.")
            return null
        }

        var incomingNumber: String? = null
        var cursor: Cursor? = null
        try {
            val projection = arrayOf(CallLog.Calls.NUMBER)
            val sortOrder = CallLog.Calls.DATE + " DESC" // Ordenar por fecha descendente (más reciente primero)
            val selection = CallLog.Calls.TYPE + " = ?" // Solo llamadas entrantes
            val selectionArgs = arrayOf(CallLog.Calls.INCOMING_TYPE.toString())

            // Consulta el CallLog para la última llamada entrante
            cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )

            if (cursor != null && cursor.moveToFirst()) {
                val numberColumnIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                if (numberColumnIndex != -1) {
                    incomingNumber = cursor.getString(numberColumnIndex)
                    Log.d("LlamadasEntrantes", "getLastIncomingCallNumber: Ultima llamada entrante encontrada: $incomingNumber")
                } else {
                    Log.w("LlamadasEntrantes", "getLastIncomingCallNumber: Columna NUMBER no encontrada en CallLog.")
                }
            } else {
                Log.d("LlamadasEntrantes", "getLastIncomingCallNumber: No se encontraron llamadas entrantes recientes en el CallLog.")
            }
        } catch (e: SecurityException) {
            Log.e("LlamadasEntrantes", "getLastIncomingCallNumber: SecurityException - Permiso READ_CALL_LOG denegado. ${e.message}", e)
        } catch (e: Exception) {
            Log.e("LlamadasEntrantes", "getLastIncomingCallNumber: Error al consultar CallLog: ${e.message}", e)
        } finally {
            cursor?.close()
        }
        return incomingNumber
    }
}