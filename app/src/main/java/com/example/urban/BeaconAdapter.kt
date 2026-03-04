package com.example.urban

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class BeaconAdapter(
    private val beacons: List<MockBeacon>,
    private val onItemClick: (MockBeacon) -> Unit
) : RecyclerView.Adapter<BeaconAdapter.BeaconViewHolder>() {

    class BeaconViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textName: TextView = view.findViewById(R.id.text_beacon_name)
        val textInfo: TextView = view.findViewById(R.id.text_beacon_info)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BeaconViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_beacon, parent, false)
        return BeaconViewHolder(view)
    }

    override fun onBindViewHolder(holder: BeaconViewHolder, position: Int) {
        val beacon = beacons[position]
        holder.textName.text = beacon.name
        holder.textInfo.text = "MAC: ${beacon.mac} | RSSI: ${beacon.rssi} dBm"
        holder.itemView.setOnClickListener { onItemClick(beacon) }
    }

    override fun getItemCount() = beacons.size
}