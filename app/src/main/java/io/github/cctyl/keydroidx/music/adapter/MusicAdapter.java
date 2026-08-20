package io.github.cctyl.keydroidx.music.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import io.github.cctyl.keydroidx.music.R;
import io.github.cctyl.keydroidx.music.model.MusicItem;

/**
 * 适用于按键机的音乐列表适配器（支持高亮选中光标跟随）
 */
public class MusicAdapter extends RecyclerView.Adapter<MusicAdapter.MusicViewHolder> {

    public interface OnItemClickListener {
        void onItemClick(MusicItem item, int position);
    }

    private final List<MusicItem> dataList = new ArrayList<>();
    private int selectedPosition = 0;
    private OnItemClickListener onItemClickListener;

    public void setData(List<MusicItem> list) {
        dataList.clear();
        if (list != null) {
            dataList.addAll(list);
        }
        selectedPosition = 0;
        notifyDataSetChanged();
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.onItemClickListener = listener;
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    public MusicItem getSelectedItem() {
        if (selectedPosition >= 0 && selectedPosition < dataList.size()) {
            return dataList.get(selectedPosition);
        }
        return null;
    }

    public void selectNext() {
        if (dataList.isEmpty()) return;
        int prev = selectedPosition;
        selectedPosition = (selectedPosition + 1) % dataList.size();
        notifyItemChanged(prev);
        notifyItemChanged(selectedPosition);
    }

    public void selectPrev() {
        if (dataList.isEmpty()) return;
        int prev = selectedPosition;
        selectedPosition = (selectedPosition - 1 + dataList.size()) % dataList.size();
        notifyItemChanged(prev);
        notifyItemChanged(selectedPosition);
    }

    @NonNull
    @Override
    public MusicViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_music, parent, false);
        return new MusicViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MusicViewHolder holder, int position) {
        MusicItem item = dataList.get(position);
        holder.tvTitle.setText(item.getTitle());
        holder.tvArtist.setText(item.getArtist());

        boolean isSelected = (position == selectedPosition);
        if (isSelected) {
            // 复古高亮风格
            holder.itemView.setBackgroundColor(0xFF0055AA);
            holder.tvTitle.setTextColor(Color.WHITE);
            holder.tvArtist.setTextColor(0xFFE0E0E0);
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT);
            holder.tvTitle.setTextColor(0xFF1A1A1A);
            holder.tvArtist.setTextColor(0xFF666666);
        }

        holder.itemView.setOnClickListener(v -> {
            int prev = selectedPosition;
            selectedPosition = holder.getBindingAdapterPosition();
            notifyItemChanged(prev);
            notifyItemChanged(selectedPosition);
            if (onItemClickListener != null) {
                onItemClickListener.onItemClick(item, selectedPosition);
            }
        });
    }

    @Override
    public int getItemCount() {
        return dataList.size();
    }

    static class MusicViewHolder extends RecyclerView.ViewHolder {
        TextView tvTitle;
        TextView tvArtist;

        public MusicViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tv_song_title);
            tvArtist = itemView.findViewById(R.id.tv_song_artist);
        }
    }
}
