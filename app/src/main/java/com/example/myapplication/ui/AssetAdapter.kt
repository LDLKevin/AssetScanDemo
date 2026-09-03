package com.example.myapplication.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.model.Asset

/**
 * 財產清單的 RecyclerView Adapter。
 *
 * Kotlin 筆記：
 * - 主建構子直接宣告 `context`/`assets` 屬性，省掉 Java 的欄位 + 建構子賦值樣板。
 * - `holder.tvId.text = ...` 是屬性語法糖，等同 Java 的 `setText(...)`。
 * - `when (asset.status)` 對 enum 是「窮舉」的：三個狀態都列到就不需要 else，
 *   日後 enum 若新增狀態，編譯器會強制你補上分支（Java 的 switch 不會）。
 * - ViewHolder 的欄位用 `val` + 行內 findViewById 初始化，非空型別本身就表達了「不為 null」。
 */
class AssetAdapter(
    private val context: Context,
    private val assets: List<Asset>,
) : RecyclerView.Adapter<AssetAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_asset, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val asset = assets[position]

        holder.tvId.text = asset.id
        holder.tvName.text = asset.name
        holder.tvDepartment.text = asset.department.ifEmpty { "－" }
        holder.tvLocation.text = asset.location.ifEmpty { "－" }

        when (asset.status) {
            Asset.Status.MATCHED -> {
                // 已盤點 + 相符（綠色）
                holder.viewStatus.setBackgroundResource(R.drawable.circle_checked)
                holder.itemView.setBackgroundResource(R.drawable.bg_card_item_matched)
                holder.tvId.setTextColor(color(R.color.status_success))

                holder.tvCheckedAt.visibility = View.VISIBLE
                holder.tvCheckedAt.text = "✓ ${asset.checkedAt}"
                holder.tvCheckedAt.setTextColor(color(R.color.status_success))

                holder.tvTag.visibility = View.VISIBLE
                holder.tvTag.text = "已盤點"
                holder.tvTag.setTextColor(color(R.color.status_success))
                holder.tvTag.setBackgroundResource(R.drawable.bg_tag_success)
            }

            Asset.Status.UNMATCHED -> {
                // 已盤點 + 不相符（橘色）
                holder.viewStatus.setBackgroundResource(R.drawable.circle_unmatched)
                holder.itemView.setBackgroundResource(R.drawable.bg_card_item_unmatched)
                holder.tvId.setTextColor(color(R.color.status_warning))

                holder.tvCheckedAt.visibility = View.VISIBLE
                holder.tvCheckedAt.text = "✓ ${asset.checkedAt}"
                holder.tvCheckedAt.setTextColor(color(R.color.status_warning))

                holder.tvTag.visibility = View.VISIBLE
                holder.tvTag.text = "不相符"
                holder.tvTag.setTextColor(color(R.color.status_warning))
                holder.tvTag.setBackgroundResource(R.drawable.bg_tag_warning)
            }

            Asset.Status.UNCHECKED -> {
                // 未盤點
                holder.viewStatus.setBackgroundResource(R.drawable.circle_unchecked)
                holder.itemView.setBackgroundResource(R.drawable.bg_card_item)
                holder.tvId.setTextColor(color(R.color.text_primary))
                holder.tvCheckedAt.visibility = View.GONE
                holder.tvTag.visibility = View.GONE
            }
        }
    }

    override fun getItemCount(): Int = assets.size

    // 比對成功後呼叫，更新單一項目
    fun markChecked(position: Int) {
        notifyItemChanged(position)
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(context, resId)

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val viewStatus: View = itemView.findViewById(R.id.view_status)
        val tvId: TextView = itemView.findViewById(R.id.tv_id)
        val tvName: TextView = itemView.findViewById(R.id.tv_name)
        val tvDepartment: TextView = itemView.findViewById(R.id.tv_department)
        val tvLocation: TextView = itemView.findViewById(R.id.tv_location)
        val tvCheckedAt: TextView = itemView.findViewById(R.id.tv_checked_at)
        val tvTag: TextView = itemView.findViewById(R.id.tv_tag)
    }
}
