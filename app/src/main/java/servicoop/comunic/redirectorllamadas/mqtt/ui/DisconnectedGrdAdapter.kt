package servicoop.comunic.redirectorllamadas.mqtt.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import servicoop.comunic.redirectorllamadas.R
import servicoop.comunic.redirectorllamadas.mqtt.GrdDesconectado
import servicoop.comunic.redirectorllamadas.mqtt.TimeUtils

class DisconnectedGrdAdapter :
    ListAdapter<GrdDesconectado, DisconnectedGrdAdapter.VH>(DIFF) {

    fun submit(newItems: List<GrdDesconectado>) {
        submitList(ArrayList(newItems))
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
            txtUltima.text = item.ultimaCaida
            val desc = TimeUtils.sinceDescription(item.ultimaCaida)
            txtTdesc.text = desc ?: "N/D"
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<GrdDesconectado>() {
            override fun areItemsTheSame(oldItem: GrdDesconectado, newItem: GrdDesconectado): Boolean {
                return oldItem.id == newItem.id
            }
            override fun areContentsTheSame(oldItem: GrdDesconectado, newItem: GrdDesconectado): Boolean {
                return oldItem == newItem
            }
        }
    }
}
