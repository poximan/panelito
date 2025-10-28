package servicoop.comunic.panelito.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import servicoop.comunic.panelito.R
import servicoop.comunic.panelito.core.model.BrokerEstado
import servicoop.comunic.panelito.data.datastore.SettingsDataStore
import servicoop.comunic.panelito.fragment.CheatSheetFragment
import servicoop.comunic.panelito.fragment.DashboardFragment
import servicoop.comunic.panelito.fragment.EmailEventsFragment
import servicoop.comunic.panelito.fragment.ProxmoxFragment
import servicoop.comunic.panelito.repository.SettingsRepository
import servicoop.comunic.panelito.services.mqtt.MQTTService

class MainActivity : AppCompatActivity() {

    private val PERMISSION_REQUEST_CODE = 1001

    lateinit var settingsRepo: SettingsRepository
        private set

    private lateinit var viewPager: ViewPager2
    private lateinit var switchConnect: Switch
    private lateinit var indicatorBroker: View
    private lateinit var txtBroker: TextView
    private var suppressSwitchChange = false
    private var desiredServiceEnabled = false

    private val brokerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MQTTService.ACTION_BROKER_ESTADO) return
            val raw = intent.getStringExtra(MQTTService.EXTRA_BROKER_ESTADO) ?: return
            val estado = runCatching { BrokerEstado.valueOf(raw) }
                .getOrElse { BrokerEstado.ERROR }
            updateBrokerUi(estado)
            when (estado) {
                BrokerEstado.DESCONECTADO -> {
                    if (!desiredServiceEnabled && switchConnect.isChecked) {
                        setSwitchWithoutTrigger(false)
                    }
                }
                BrokerEstado.CONECTADO,
                BrokerEstado.CONECTANDO,
                BrokerEstado.REINTENTANDO -> {
                    if (desiredServiceEnabled && !switchConnect.isChecked) {
                        setSwitchWithoutTrigger(true)
                    }
                }
                else -> {}
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settingsRepo = SettingsDataStore(this)

        Log.d("MainActivity", "onCreate: Solicitando permisos.")
        requestPermissions()

        switchConnect = findViewById(R.id.switch_connect)
        indicatorBroker = findViewById(R.id.indicator_broker)
        txtBroker = findViewById(R.id.txt_broker)
        viewPager = findViewById(R.id.view_pager)

        viewPager.adapter = MainPagerAdapter(this)
        viewPager.offscreenPageLimit = 3

        switchConnect.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitchChange) return@setOnCheckedChangeListener
            desiredServiceEnabled = isChecked
            lifecycleScope.launch { settingsRepo.setServiceEnabled(isChecked) }
            if (isChecked) {
                startMqttService()
                updateBrokerUi(BrokerEstado.CONECTANDO)
                broadcastBrokerState(BrokerEstado.CONECTANDO)
                requestServiceState()
            } else {
                stopMqttService()
                updateBrokerUi(BrokerEstado.DESCONECTADO)
                broadcastBrokerState(BrokerEstado.DESCONECTADO)
            }
        }

        lifecycleScope.launch {
            settingsRepo.ensureDefaults()
            val enabled = settingsRepo.getServiceEnabled().first()
            desiredServiceEnabled = enabled
            setSwitchWithoutTrigger(enabled)
            if (enabled) {
                startMqttService()
                updateBrokerUi(BrokerEstado.CONECTANDO)
                broadcastBrokerState(BrokerEstado.CONECTANDO)
                requestServiceState()
            } else {
                stopMqttService()
                updateBrokerUi(BrokerEstado.DESCONECTADO)
                broadcastBrokerState(BrokerEstado.DESCONECTADO)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            brokerReceiver,
            IntentFilter(MQTTService.ACTION_BROKER_ESTADO)
        )
        if (switchConnect.isChecked) {
            requestServiceState()
        } else {
            broadcastBrokerState(BrokerEstado.DESCONECTADO)
        }
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(brokerReceiver)
        super.onStop()
    }

    private fun setSwitchWithoutTrigger(value: Boolean) {
        suppressSwitchChange = true
        switchConnect.isChecked = value
        suppressSwitchChange = false
    }

    private fun startMqttService() {
        ContextCompat.startForegroundService(this, Intent(this, MQTTService::class.java))
    }

    private fun stopMqttService() {
        stopService(Intent(this, MQTTService::class.java))
    }

    private fun requestServiceState() {
        if (!switchConnect.isChecked) return
        val intent = Intent(this, MQTTService::class.java).apply {
            putExtra(MQTTService.EXTRA_SOLICITAR_ESTADO, true)
        }
        startService(intent)
    }

    private fun broadcastBrokerState(state: BrokerEstado) {
        val intent = Intent(MQTTService.ACTION_BROKER_ESTADO).apply {
            putExtra(MQTTService.EXTRA_BROKER_ESTADO, state.name)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun updateBrokerUi(state: BrokerEstado) {
        txtBroker.text = getString(R.string.broker_status, state.name)
        val indicator = when (state) {
            BrokerEstado.CONECTADO -> R.drawable.led_verde
            BrokerEstado.CONECTANDO, BrokerEstado.REINTENTANDO -> R.drawable.led_naranja
            else -> R.drawable.led_rojo
        }
        indicatorBroker.setBackgroundResource(indicator)
    }

    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                Log.d("MainActivity", "requestPermissions: POST_NOTIFICATIONS pendiente.")
            }
        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.READ_PHONE_STATE)
        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.READ_CONTACTS)
        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CALL_LOG
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.READ_CALL_LOG)
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
            Log.d(
                "MainActivity",
                "requestPermissions: Solicitando ${permissionsToRequest.size} permisos."
            )
        } else {
            Log.d("MainActivity", "requestPermissions: Todos los permisos ya concedidos.")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.d("MainActivity", "onRequestPermissionsResult: Request Code = $requestCode")

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val deniedPermissions = permissions.filterIndexed { index, _ ->
                val permissionName = permissions[index]
                val isGranted = grantResults[index] == PackageManager.PERMISSION_GRANTED
                Log.d("MainActivity", "permiso=$permissionName granted=$isGranted")
                !isGranted
            }

            if (deniedPermissions.isNotEmpty()) {
                Log.e("MainActivity", "Permisos denegados: ${deniedPermissions.joinToString()}")
                Toast.makeText(
                    this,
                    getString(R.string.permissions_denied_message),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.permissions_granted_message),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun isBrokerDesiredEnabled(): Boolean = desiredServiceEnabled

    private class MainPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 4

        override fun createFragment(position: Int) = when (position) {
            0 -> DashboardFragment.newInstance()
            1 -> ProxmoxFragment.newInstance()
            2 -> EmailEventsFragment.newInstance()
            else -> CheatSheetFragment.newInstance()
        }
    }
}

