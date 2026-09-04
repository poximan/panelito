package servicoop.comunic.panelito.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.launch
import servicoop.comunic.panelito.PanelitoApplication
import servicoop.comunic.panelito.R
import servicoop.comunic.panelito.core.model.BrokerEstado
import servicoop.comunic.panelito.fragment.CharitoFragment
import servicoop.comunic.panelito.fragment.CheatSheetFragment
import servicoop.comunic.panelito.fragment.DashboardFragment
import servicoop.comunic.panelito.fragment.EmailEventsFragment
import servicoop.comunic.panelito.fragment.ProxmoxFragment
import servicoop.comunic.panelito.fragment.TelefonosFragment
import servicoop.comunic.panelito.services.mqtt.MqttSession

class MainActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var indicatorBroker: View
    private lateinit var txtBroker: TextView
    private val mqttSession: MqttSession
        get() = (application as PanelitoApplication).mqttSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        indicatorBroker = findViewById(R.id.indicator_broker)
        txtBroker = findViewById(R.id.txt_broker)
        viewPager = findViewById(R.id.view_pager)

        viewPager.adapter = MainPagerAdapter(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mqttSession.state.collect { state -> updateBrokerUi(state.broker) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        mqttSession.start()
    }

    override fun onStop() {
        mqttSession.stop()
        super.onStop()
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

    private class MainPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 6

        override fun createFragment(position: Int) = when (position) {
            0 -> DashboardFragment.newInstance()
            1 -> ProxmoxFragment.newInstance()
            2 -> CharitoFragment.newInstance()
            3 -> EmailEventsFragment.newInstance()
            4 -> TelefonosFragment.newInstance()
            else -> CheatSheetFragment.newInstance()
        }
    }
}


