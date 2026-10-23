package com.tamperdroid

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tamperdroid.proxy.Exchange

class RequestListAdapter(
    private val items: MutableList<Exchange>,
    private val onClick: (Exchange) -> Unit
) : RecyclerView.Adapter<RequestListAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.txtTitle)
        val subtitle: TextView = view.findViewById(R.id.txtSubtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_request, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ex = items[position]
        holder.title.text = "${ex.method} ${ex.host}${ex.path}"
        val statusText = ex.status?.toString() ?: (if (ex.held) "HELD" else "...")
        holder.subtitle.text = "$statusText  ·  ${ex.scheme}"
        holder.itemView.setOnClickListener { onClick(ex) }
    }

    override fun getItemCount() = items.size

    fun submit(newItems: List<Exchange>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun updateOne(exchange: Exchange) {
        val idx = items.indexOfFirst { it.id == exchange.id }
        if (idx >= 0) notifyItemChanged(idx)
    }
}
