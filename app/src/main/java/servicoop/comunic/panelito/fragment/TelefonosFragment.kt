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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import servicoop.comunic.panelito.R
import servicoop.comunic.panelito.ui.adapter.TelefonoListItem
import servicoop.comunic.panelito.ui.adapter.TelefonosAdapter

class TelefonosFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private val telefonosAdapter by lazy { TelefonosAdapter(::dialPhone) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_telefonos, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.recycler_telefonos)
        val layoutManager = GridLayoutManager(requireContext(), 2)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val item = telefonosAdapter.currentList.getOrNull(position)
                return if (item is TelefonoListItem.Section) 2 else 1
            }
        }
        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = telefonosAdapter
        telefonosAdapter.submitList(buildItems())
    }

    override fun onDestroyView() {
        recyclerView.adapter = null
        super.onDestroyView()
    }

    private fun buildItems(): List<TelefonoListItem> {
        return listOf(
            TelefonoListItem.Section(getString(R.string.telefonos_section_fontana)),
            TelefonoListItem.Phone(
                number = getString(R.string.telefono_fontana_4453400),
                comment = getString(R.string.telefono_comment_rotativas)
            ),
            TelefonoListItem.Phone(
                number = getString(R.string.telefono_fontana_4453355),
                comment = null
            ),
            TelefonoListItem.Phone(
                number = getString(R.string.telefono_fontana_4453222),
                comment = null
            ),
            TelefonoListItem.Phone(
                number = getString(R.string.telefono_fontana_4453232),
                comment = null
            ),
            TelefonoListItem.Phone(
                number = getString(R.string.telefono_fontana_4471718),
                comment = null
            ),
            TelefonoListItem.Phone(
                number = getString(R.string.telefono_fontana_2804675614),
                comment = null
            ),
            TelefonoListItem.Section(getString(R.string.telefonos_section_estivariz)),
            TelefonoListItem.Phone(
                number = getString(R.string.telefono_estivariz_4454041),
                comment = getString(R.string.telefono_comment_jefes)
            ),
            TelefonoListItem.Phone(
                number = getString(R.string.telefono_estivariz_4471837),
                comment = null
            ),
            TelefonoListItem.Phone(
                number = getString(R.string.telefono_estivariz_4472066),
                comment = null
            ),
            TelefonoListItem.Phone(
                number = getString(R.string.telefono_estivariz_4472131),
                comment = null
            ),
            TelefonoListItem.Phone(
                number = getString(R.string.telefono_estivariz_4455768),
                comment = null
            ),
            TelefonoListItem.Phone(
                number = getString(R.string.telefono_estivariz_2804675553),
                comment = null
            ),
            TelefonoListItem.Phone(
                number = getString(R.string.telefono_estivariz_2804992999),
                comment = null
            ),
            TelefonoListItem.Phone(
                number = getString(R.string.telefono_estivariz_2804607705),
                comment = null
            ),
            TelefonoListItem.Section(getString(R.string.telefonos_section_general)),
            TelefonoListItem.Phone(
                number = getString(R.string.telefono_general_08006667378),
                comment = getString(R.string.telefono_comment_linea_fija)
            )
        )
    }

    private fun dialPhone(item: TelefonoListItem.Phone) {
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:${item.number}")
        }
        try {
            startActivity(intent)
        } catch (ex: ActivityNotFoundException) {
            Toast.makeText(
                requireContext(),
                R.string.telefonos_error_open_dialer,
                Toast.LENGTH_SHORT
            ).show()
        } catch (ex: Exception) {
            Toast.makeText(
                requireContext(),
                R.string.telefonos_error_open_dialer,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    companion object {
        fun newInstance(): TelefonosFragment = TelefonosFragment()
    }
}
