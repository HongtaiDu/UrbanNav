package com.example.urban

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import android.os.Handler
import android.os.Looper

class AdminBeaconAdapter(
    private val beacons: MutableList<Beacon>,
    private val onEdit: (Beacon) -> Unit,
    private val onDelete: (Beacon) -> Unit
) : RecyclerView.Adapter<AdminBeaconAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvBeaconName)
        val mac: TextView = view.findViewById(R.id.tvBeaconMac)
        val coords: TextView = view.findViewById(R.id.tvBeaconCoords)
        val rssi: TextView = view.findViewById(R.id.tvBeaconRssi)
        val btnEdit: Button = view.findViewById(R.id.btnEdit)
        val btnDelete: Button = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_beacon_admin, parent, false))

    override fun getItemCount() = beacons.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val b = beacons[position]
        h.name.text = b.name
        h.mac.text = b.mac
        h.coords.text = "(${b.x}, ${b.y})"
        h.rssi.text = "${b.measuredPower} dBm"
        h.btnEdit.setOnClickListener { onEdit(b) }
        h.btnDelete.setOnClickListener { onDelete(b) }
    }

    fun refresh(newList: List<Beacon>) {
        val copy = newList.toList()
        beacons.clear()
        beacons.addAll(copy)
        notifyDataSetChanged()
    }
}
