package com.example.urban

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Displays a live row for each registered beacon showing its name,
 * current filtered RSSI, and signal strength bars.
 *
 * [LiveBeaconRow] is a simple UI model — filteredRssi is null when the
 * beacon hasn't been heard yet (or has gone stale).
 */
data class LiveBeaconRow(
    val mac: String,
    val name: String,
    val filteredRssi: Double?       // null = out of range
)

class LiveBeaconAdapter(
    private val rows: MutableList<LiveBeaconRow> = mutableListOf()
) : RecyclerView.Adapter<LiveBeaconAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView        = view.findViewById(R.id.tv_name)
        val tvDistance: TextView    = view.findViewById(R.id.tv_distance)
        val signalBars: SignalBarsView = view.findViewById(R.id.signal_bars)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_live_beacon, parent, false))

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val row = rows[position]
        h.tvName.text = row.name

        val rssi = row.filteredRssi
        if (rssi != null) {
            h.tvDistance.text = "${"%.1f".format(rssi)} dBm"
            h.tvDistance.setTextColor(0xFF3182CE.toInt())
            h.signalBars.setRssi(rssi)
        } else {
            h.tvDistance.text = "Out of range"
            h.tvDistance.setTextColor(0xFFE53E3E.toInt())
            h.signalBars.reset()
        }
    }

    fun refresh(newRows: List<LiveBeaconRow>) {
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
    }
}