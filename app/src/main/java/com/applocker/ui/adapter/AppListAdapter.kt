package com.applocker.ui.adapter

import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.applocker.databinding.ItemAppBinding
import com.applocker.ui.AppListActivity

class AppListAdapter(
    private val packageManager: PackageManager,
    private val onToggle: (AppListActivity.AppItem, Boolean) -> Unit
) : ListAdapter<AppListActivity.AppItem, AppListAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(val binding: ItemAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AppListActivity.AppItem) {
            binding.tvAppName.text = item.label
            binding.tvPackageName.text = item.packageName

            try {
                val icon = packageManager.getApplicationIcon(item.packageName)
                binding.ivAppIcon.setImageDrawable(icon)
            } catch (e: PackageManager.NameNotFoundException) {
                binding.ivAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            binding.switchLock.setOnCheckedChangeListener(null)
            binding.switchLock.isChecked = item.isLocked
            binding.switchLock.setOnCheckedChangeListener { _, checked ->
                item.isLocked = checked
                onToggle(item, checked)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<AppListActivity.AppItem>() {
            override fun areItemsTheSame(
                a: AppListActivity.AppItem,
                b: AppListActivity.AppItem
            ) = a.packageName == b.packageName

            override fun areContentsTheSame(
                a: AppListActivity.AppItem,
                b: AppListActivity.AppItem
            ) = a == b
        }
    }
}
