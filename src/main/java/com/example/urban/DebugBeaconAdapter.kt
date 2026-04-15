package com.example.urban

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.urban.databinding.ItemDebugBeaconBinding
import java.util.Locale

class DebugBeaconAdapter : RecyclerView.Adapter<DebugBeaconAdapter.ViewHolder>() {

    private val dataList = mutableListOf<DebugBeaconData>()

    class ViewHolder(val binding: ItemDebugBeaconBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDebugBeaconBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = dataList[position]
        with(holder.binding) {
            tvName.text = item.beacon.name
            tvMac.text = item.beacon.mac

            val msAgo = System.currentTimeMillis() - item.lastSeen
            tvLastSeen.text = "${msAgo}ms ago"

            tvRssi.text = String.format(Locale.US, "%d / %.1f", item.rawRssi, item.filteredRssi)

            // Variance and stability
            tvVariance.text = String.format(Locale.US, "%.1f dB", item.rssiVariance)

            // Distance with "Stability" (variance of distance)
            tvDistance.text = String.format(Locale.US, "%.2fm ± %.1f", item.distance, item.distanceVariance)

            // Packet stats
            tvPackets.text = String.format(Locale.US, "%.0f%% / %.1fHz", item.packetLoss * 100, item.frequency)
        }
    }

    override fun getItemCount() = dataList.size

    fun updateData(newList: List<DebugBeaconData>) {
        dataList.clear()
        dataList.addAll(newList)
        notifyDataSetChanged()
    }
}

data class DebugBeaconData(
    val beacon: Beacon,
    val rawRssi: Int,
    val filteredRssi: Double,
    val distance: Double,
    val rssiVariance: Double,
    val distanceVariance: Double,
    val packetLoss: Double,
    val frequency: Double,
    val lastSeen: Long
)