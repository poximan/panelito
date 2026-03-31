package servicoop.comunic.panelito.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import servicoop.comunic.panelito.R

sealed class TelefonoListItem {
    data class Section(val title: String) : TelefonoListItem()
    data class Phone(val number: String, val comment: String?) : TelefonoListItem()
}

class TelefonosAdapter(
    private val onPhoneClicked: (TelefonoListItem.Phone) -> Unit
) : ListAdapter<TelefonoListItem, RecyclerView.ViewHolder>(DiffCallback) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is TelefonoListItem.Section -> VIEW_TYPE_SECTION
        is TelefonoListItem.Phone -> VIEW_TYPE_PHONE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_SECTION -> {
                val view = inflater.inflate(R.layout.item_telefono_section, parent, false)
                SectionViewHolder(view)
            }

            VIEW_TYPE_PHONE -> {
                val view = inflater.inflate(R.layout.item_telefono_number, parent, false)
                PhoneViewHolder(view, onPhoneClicked)
            }

            else -> throw IllegalArgumentException("Unexpected view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is SectionViewHolder -> holder.bind(getItem(position) as TelefonoListItem.Section)
            is PhoneViewHolder -> holder.bind(getItem(position) as TelefonoListItem.Phone)
        }
    }

    private class SectionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleView: TextView = itemView.findViewById(R.id.txt_telefono_section)

        fun bind(item: TelefonoListItem.Section) {
            titleView.text = item.title
        }
    }

    private class PhoneViewHolder(
        itemView: View,
        private val onPhoneClicked: (TelefonoListItem.Phone) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val numberView: TextView = itemView.findViewById(R.id.txt_telefono_number)
        private val commentView: TextView = itemView.findViewById(R.id.txt_telefono_comment)
        private val hintView: TextView = itemView.findViewById(R.id.txt_telefono_hint)

        fun bind(item: TelefonoListItem.Phone) {
            numberView.text = item.number
            val comment = item.comment
            if (comment.isNullOrBlank()) {
                commentView.visibility = View.GONE
            } else {
                commentView.visibility = View.VISIBLE
                commentView.text = comment
            }
            hintView.text = itemView.context.getString(R.string.telefonos_touch_to_call)
            itemView.setOnClickListener { onPhoneClicked(item) }
        }
    }

    private companion object {
        private const val VIEW_TYPE_SECTION = 1
        private const val VIEW_TYPE_PHONE = 2

        private val DiffCallback = object : DiffUtil.ItemCallback<TelefonoListItem>() {
            override fun areItemsTheSame(
                oldItem: TelefonoListItem,
                newItem: TelefonoListItem
            ): Boolean {
                return when {
                    oldItem is TelefonoListItem.Section && newItem is TelefonoListItem.Section ->
                        oldItem.title == newItem.title
                    oldItem is TelefonoListItem.Phone && newItem is TelefonoListItem.Phone ->
                        oldItem.number == newItem.number
                    else -> false
                }
            }

            override fun areContentsTheSame(
                oldItem: TelefonoListItem,
                newItem: TelefonoListItem
            ): Boolean = oldItem == newItem
        }
    }
}
