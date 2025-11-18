package servicoop.comunic.panelito.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import servicoop.comunic.panelito.R
import servicoop.comunic.panelito.core.model.EmailEvent
import servicoop.comunic.panelito.core.util.TimestampFormatter

class EmailEventsAdapter : RecyclerView.Adapter<EmailEventsAdapter.ViewHolder>() {

    private val items = mutableListOf<EmailEvent>()

    fun submit(events: List<EmailEvent>) {
        items.clear()
        items.addAll(events)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_email_event, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val indicator: View = view.findViewById(R.id.indicator_email_event)
        private val subject: TextView = view.findViewById(R.id.txt_email_event_subject)
        private val status: TextView = view.findViewById(R.id.txt_email_event_status)
        private val timestamp: TextView = view.findViewById(R.id.txt_email_event_timestamp)

        fun bind(event: EmailEvent) {
            val ctx = itemView.context
            subject.text = event.subject.ifBlank { ctx.getString(R.string.value_not_available) }
            if (event.ok) {
                indicator.setBackgroundResource(R.drawable.led_verde)
                status.text = ctx.getString(R.string.email_event_status_ok)
            } else {
                indicator.setBackgroundResource(R.drawable.led_rojo)
                status.text = ctx.getString(R.string.email_event_status_fail)
            }

            val ts = TimestampFormatter.format(event.timestamp, ctx.getString(R.string.status_unknown))
            timestamp.text = ctx.getString(R.string.email_event_timestamp, ts)
        }
    }
}
