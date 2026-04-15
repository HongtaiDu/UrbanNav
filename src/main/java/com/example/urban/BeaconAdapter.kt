package com.example.urban

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class BeaconAdapter(
    private var beacons: List<BleDevice>,
    private val onSelectionChanged: (Set<String>) -> Unit
) : RecyclerView.Adapter<BeaconAdapter.BeaconViewHolder>() {

    private val selectedMacs = mutableSetOf<String>()

    class BeaconViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textName: TextView = view.findViewById(R.id.text_beacon_name)
        val textInfo: TextView = view.findViewById(R.id.text_beacon_info)
        val checkBox: CheckBox = view.findViewById(R.id.checkbox_beacon)
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
        
        holder.checkBox.setOnCheckedChangeListener(null)
        holder.checkBox.isChecked = selectedMacs.contains(beacon.mac)
        
        holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                selectedMacs.add(beacon.mac)
            } else {
                selectedMacs.remove(beacon.mac)
            }
            onSelectionChanged(selectedMacs)
        }

        holder.itemView.setOnClickListener {
            holder.checkBox.toggle()
        }
    }

    override fun getItemCount() = beacons.size

    fun updateList(newBeacons: List<BleDevice>) {
        beacons = newBeacons
        notifyDataSetChanged()
    }

    fun getSelectedMacs(): Set<String> = selectedMacs

    fun clearSelection() {
        selectedMacs.clear()
        onSelectionChanged(selectedMacs)
        notifyDataSetChanged()
    }
}