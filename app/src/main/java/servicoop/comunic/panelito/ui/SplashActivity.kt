package servicoop.comunic.panelito.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import servicoop.comunic.panelito.R

class SplashActivity : AppCompatActivity() {

    private val launchHandler = Handler(Looper.getMainLooper())
    private val launchRunnable = Runnable {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        launchHandler.postDelayed(launchRunnable, 1200L)
    }

    override fun onDestroy() {
        launchHandler.removeCallbacks(launchRunnable)
        super.onDestroy()
    }
}
