package servicoop.comunic.panelito.ui.adapter

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import servicoop.comunic.panelito.R
import servicoop.comunic.panelito.core.model.GrdDesconectado
import servicoop.comunic.panelito.core.util.TimeUtils
import servicoop.comunic.panelito.core.util.TimestampFormatter

class DisconnectedGrdAdapter :
    ListAdapter<GrdDesconectado, DisconnectedGrdAdapter.VH>(DIFF) {

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            // Refresca todas las filas para recalcular T.desc
            notifyDataSetChanged()
            handler.postDelayed(this, 60_000L)
        }
    }

    fun submit(newItems: List<GrdDesconectado>) {
        submitList(ArrayList(newItems))
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        // Arranca ticker cuando el adapter esta en uso
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 60_000L)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        // Detiene ticker para evitar consumo cuando no esta visible
        handler.removeCallbacks(ticker)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_grd_row, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val txtEquipo: TextView = itemView.findViewById(R.id.txt_equipo)
        private val txtUltima: TextView = itemView.findViewById(R.id.txt_ultima)
        private val txtTdesc: TextView = itemView.findViewById(R.id.txt_tdesc)

        fun bind(item: GrdDesconectado) {
            txtEquipo.text = item.nombre
            txtUltima.text = TimestampFormatter.format(
                item.ultimaCaida,
                itemView.context.getString(R.string.value_not_available)
            )
            val desc = TimeUtils.sinceDescription(item.ultimaCaida)
            txtTdesc.text = desc ?: itemView.context.getString(R.string.value_not_available)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<GrdDesconectado>() {
            override fun areItemsTheSame(
                oldItem: GrdDesconectado,
                newItem: GrdDesconectado
            ): Boolean = oldItem.id == newItem.id

            override fun areContentsTheSame(
                oldItem: GrdDesconectado,
                newItem: GrdDesconectado
            ): Boolean = oldItem == newItem
        }
    }
}
