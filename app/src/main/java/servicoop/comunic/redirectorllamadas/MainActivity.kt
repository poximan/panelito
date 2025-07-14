package servicoop.comunic.redirectorllamadas

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import servicoop.comunic.redirectorllamadas.fragment.MqttFragment

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, MqttFragment())
                .commit()
        }
    }
}