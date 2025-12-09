package servicoop.comunic.panelito.fragment

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import servicoop.comunic.panelito.R
import servicoop.comunic.panelito.ui.adapter.CheatSheetAdapter
import servicoop.comunic.panelito.ui.adapter.CheatSheetEntry
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class CheatSheetFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private val cheatSheetAdapter by lazy {
        CheatSheetAdapter(::openEndpointUrl, ::runPingForEndpoint, ::runTcpProbe)
    }
    private val items = mutableListOf<CheatSheetEntry>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_cheat_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.recycler_cheat_sheet)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = cheatSheetAdapter
        buildInitialItems()
    }

    override fun onDestroyView() {
        recyclerView.adapter = null
        super.onDestroyView()
    }

    private fun buildInitialItems() {
        val defaultPingResult = getString(R.string.cheat_sheet_ping_placeholder)
        val defaultTcpResult = getString(R.string.cheat_sheet_tcp_placeholder)
        items.clear()
        items.add(
            CheatSheetEntry.Endpoint(
                id = 1,
                title = getString(R.string.cheat_sheet_tw_title),
                url = getString(R.string.cheat_sheet_tw_url),
                ip = getString(R.string.cheat_sheet_tw_ip),
                pingResult = defaultPingResult
            )
        )
        items.add(
            CheatSheetEntry.Endpoint(
                id = 2,
                title = getString(R.string.cheat_sheet_pm_title),
                url = getString(R.string.cheat_sheet_pm_url),
                ip = getString(R.string.cheat_sheet_pm_ip),
                pingResult = defaultPingResult
            )
        )
        items.add(
            CheatSheetEntry.TcpProbe(
                id = 3,
                title = getString(R.string.cheat_sheet_modem_telef_title),
                host = getString(R.string.cheat_sheet_modem_telef_host),
                port = 40000,
                result = defaultTcpResult
            )
        )
        items.add(CheatSheetEntry.Notes(getString(R.string.cheat_sheet_notes)))
        submitItems()
    }

    private fun openEndpointUrl(endpoint: CheatSheetEntry.Endpoint) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(endpoint.url))
        try {
            startActivity(intent)
        } catch (ex: ActivityNotFoundException) {
            Toast.makeText(
                requireContext(),
                R.string.cheat_sheet_open_url_error,
                Toast.LENGTH_SHORT
            ).show()
        } catch (ex: Exception) {
            Toast.makeText(
                requireContext(),
                R.string.cheat_sheet_open_url_error,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun runPingForEndpoint(endpoint: CheatSheetEntry.Endpoint) {
        updateEndpoint(endpoint.id) {
            it.copy(
                isPingRunning = true,
                pingResult = getString(R.string.cheat_sheet_ping_running)
            )
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                executePing(endpoint.ip)
            }
            updateEndpoint(endpoint.id) {
                it.copy(
                    isPingRunning = false,
                    pingResult = result
                )
            }
        }
    }

    private fun runTcpProbe(probe: CheatSheetEntry.TcpProbe) {
        updateTcpProbe(probe.id) {
            it.copy(
                isRunning = true,
                result = getString(R.string.cheat_sheet_tcp_running)
            )
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                executeTcpProbe(probe.host, probe.port)
            }
            updateTcpProbe(probe.id) {
                it.copy(
                    isRunning = false,
                    result = result
                )
            }
        }
    }

    private fun updateEndpoint(
        id: Int,
        transformer: (CheatSheetEntry.Endpoint) -> CheatSheetEntry.Endpoint
    ) {
        val index = items.indexOfFirst { it is CheatSheetEntry.Endpoint && it.id == id }
        if (index >= 0) {
            val current = items[index] as CheatSheetEntry.Endpoint
            items[index] = transformer(current)
            submitItems()
        }
    }

    private fun updateTcpProbe(
        id: Int,
        transformer: (CheatSheetEntry.TcpProbe) -> CheatSheetEntry.TcpProbe
    ) {
        val index = items.indexOfFirst { it is CheatSheetEntry.TcpProbe && it.id == id }
        if (index >= 0) {
            val current = items[index] as CheatSheetEntry.TcpProbe
            items[index] = transformer(current)
            submitItems()
        }
    }

    private fun submitItems() {
        cheatSheetAdapter.submitList(items.toList())
    }

    private fun executeTcpProbe(host: String, port: Int): String {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 3000)
            }
            getString(R.string.cheat_sheet_tcp_open, port)
        } catch (ex: ConnectException) {
            getString(R.string.cheat_sheet_tcp_closed, port)
        } catch (ex: SocketTimeoutException) {
            getString(R.string.cheat_sheet_tcp_closed, port)
        } catch (ex: UnknownHostException) {
            getString(R.string.cheat_sheet_tcp_unknown)
        } catch (ex: IOException) {
            getString(R.string.cheat_sheet_tcp_unknown)
        } catch (ex: Exception) {
            getString(R.string.cheat_sheet_tcp_unknown)
        }
    }

    private fun executePing(target: String): String {
        val commands = listOf(
            listOf("/system/bin/ping", "-c", "4", target),
            listOf("ping", "-c", "4", target)
        )
        var lastError: Exception? = null
        for (command in commands) {
            try {
                val process = ProcessBuilder(command.toMutableList())
                    .redirectErrorStream(true)
                    .start()
                val completed = process.waitFor(15, TimeUnit.SECONDS)
                val output = process.inputStream.bufferedReader().use { it.readText() }
                if (!completed) {
                    process.destroy()
                    return getString(R.string.cheat_sheet_ping_timeout)
                }
                val trimmed = output.trim()
                val exitCode = process.exitValue()
                if (trimmed.isNotEmpty()) {
                    return trimmed
                }
                return if (exitCode == 0) {
                    getString(R.string.cheat_sheet_ping_no_output_success)
                } else {
                    getString(R.string.cheat_sheet_ping_error_exit, exitCode)
                }
            } catch (ex: IOException) {
                lastError = ex
            } catch (ex: Exception) {
                lastError = ex
                break
            }
        }
        val detail = lastError?.localizedMessage?.takeIf { it.isNotBlank() }
        return detail?.let { getString(R.string.cheat_sheet_ping_error_detail, it) }
            ?: getString(R.string.cheat_sheet_ping_error_generic, target)
    }

    companion object {
        fun newInstance(): CheatSheetFragment = CheatSheetFragment()
    }
}
