package servicoop.comunic.panelito.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import servicoop.comunic.panelito.R
import servicoop.comunic.panelito.data.datastore.SettingsDataStore
import servicoop.comunic.panelito.domain.repository.SettingsRepository
import servicoop.comunic.panelito.fragment.DashboardFragment
import servicoop.comunic.panelito.fragment.ProxmoxFragment

class MainActivity : AppCompatActivity() {

    private val PERMISSION_REQUEST_CODE = 1001

    // provider simple del repositorio para toda la UI
    lateinit var settingsRepo: SettingsRepository
        private set
    private lateinit var viewPager: ViewPager2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // inyeccion manual (si luego queres, migramos a DI)
        settingsRepo = SettingsDataStore(this)

        Log.d("MainActivity", "onCreate: Solicitando permisos.")
        requestPermissions()

        viewPager = findViewById(R.id.view_pager)
        viewPager.adapter = MainPagerAdapter(this)
        viewPager.offscreenPageLimit = 1
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

    private class MainPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int) = when (position) {
            0 -> DashboardFragment.newInstance()
            else -> ProxmoxFragment.newInstance()
        }
    }
}
