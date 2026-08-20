package com.example.myapplication.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.content.ContextCompat;

import com.example.myapplication.R;
import com.example.myapplication.model.Asset;

import java.util.List;

public class AssetAdapter extends RecyclerView.Adapter<AssetAdapter.ViewHolder> {

    private final Context context;
    private final List<Asset> assets;

    public AssetAdapter(Context context, List<Asset> assets) {
        this.context = context;
        this.assets  = assets;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context)
                .inflate(R.layout.item_asset, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Asset asset = assets.get(position);

        holder.tvId.setText(asset.id);
        holder.tvName.setText(asset.name);
        holder.tvDepartment.setText(asset.department.isEmpty() ? "－" : asset.department);
        holder.tvLocation.setText(asset.location.isEmpty() ? "－" : asset.location);

        switch (asset.status) {
            case MATCHED:
                // 已盤點 + 相符（綠色）
                holder.viewStatus.setBackgroundResource(R.drawable.circle_checked);
                holder.itemView.setBackgroundResource(R.drawable.bg_card_item_matched);
                holder.tvId.setTextColor(ContextCompat.getColor(context, R.color.status_success));

                holder.tvCheckedAt.setVisibility(View.VISIBLE);
                holder.tvCheckedAt.setText("✓ " + asset.checkedAt);
                holder.tvCheckedAt.setTextColor(ContextCompat.getColor(context, R.color.status_success));

                holder.tvTag.setVisibility(View.VISIBLE);
                holder.tvTag.setText("已盤點");
                holder.tvTag.setTextColor(ContextCompat.getColor(context, R.color.status_success));
                holder.tvTag.setBackgroundResource(R.drawable.bg_tag_success);
                break;

            case UNMATCHED:
                // 已盤點 + 不相符（橘色）
                holder.viewStatus.setBackgroundResource(R.drawable.circle_unmatched);
                holder.itemView.setBackgroundResource(R.drawable.bg_card_item_unmatched);
                holder.tvId.setTextColor(ContextCompat.getColor(context, R.color.status_warning));

                holder.tvCheckedAt.setVisibility(View.VISIBLE);
                holder.tvCheckedAt.setText("✓ " + asset.checkedAt);
                holder.tvCheckedAt.setTextColor(ContextCompat.getColor(context, R.color.status_warning));

                holder.tvTag.setVisibility(View.VISIBLE);
                holder.tvTag.setText("不相符");
                holder.tvTag.setTextColor(ContextCompat.getColor(context, R.color.status_warning));
                holder.tvTag.setBackgroundResource(R.drawable.bg_tag_warning);
                break;

            case UNCHECKED:
            default:
                // 未盤點
                holder.viewStatus.setBackgroundResource(R.drawable.circle_unchecked);
                holder.itemView.setBackgroundResource(R.drawable.bg_card_item);
                holder.tvId.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
                holder.tvCheckedAt.setVisibility(View.GONE);
                holder.tvTag.setVisibility(View.GONE);
                break;
        }
    }

    @Override
    public int getItemCount() { return assets.size(); }

    // 比對成功後呼叫，更新單一項目
    public void markChecked(int position) {
        notifyItemChanged(position);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        View     viewStatus;
        TextView tvId, tvName, tvDepartment, tvLocation, tvCheckedAt, tvTag;

        ViewHolder(View itemView) {
            super(itemView);
            viewStatus    = itemView.findViewById(R.id.view_status);
            tvId          = itemView.findViewById(R.id.tv_id);
            tvName        = itemView.findViewById(R.id.tv_name);
            tvDepartment  = itemView.findViewById(R.id.tv_department);
            tvLocation    = itemView.findViewById(R.id.tv_location);
            tvCheckedAt   = itemView.findViewById(R.id.tv_checked_at);
            tvTag         = itemView.findViewById(R.id.tv_tag);
        }
    }
}
