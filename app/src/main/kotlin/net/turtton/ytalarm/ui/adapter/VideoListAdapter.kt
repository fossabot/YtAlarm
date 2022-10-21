package net.turtton.ytalarm.ui.adapter

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.findFragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.selection.SelectionTracker.SelectionObserver
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.turtton.ytalarm.R
import net.turtton.ytalarm.database.structure.Video
import net.turtton.ytalarm.ui.fragment.FragmentVideoPlayerArgs
import net.turtton.ytalarm.util.extensions.deleteVideo
import net.turtton.ytalarm.viewmodel.PlaylistViewContainer
import net.turtton.ytalarm.viewmodel.VideoViewContainer
import net.turtton.ytalarm.worker.VideoInfoDownloadWorker

class VideoListAdapter<T>(
    private val fragment: T
) : ListAdapter<Video, VideoListAdapter.ViewHolder>(BasicComparator<Video>())
        where T : LifecycleOwner,
              T : PlaylistViewContainer,
              T : VideoViewContainer {
    private val currentCheckBox = hashSetOf<ViewContainer>()

    var tracker: SelectionTracker<Long>? = null
        set(value) {
            value?.let {
                it.addObserver(object : SelectionObserver<Long>() {
                    override fun onSelectionChanged() {
                        val selected = currentCheckBox.filter { current ->
                            it.isSelected(current.id)
                        }
                        val unSelectable = selected.filter { current ->
                            !current.selectable
                        }
                        if (unSelectable.isNotEmpty() && unSelectable.size == selected.size) {
                            fragment.lifecycleScope.launch(Dispatchers.Main) {
                                it.clearSelection()
                            }
                            return
                        }

                        currentCheckBox.forEach { (id, box, selectable) ->
                            if (!selectable) {
                                fragment.lifecycleScope.launch(Dispatchers.Main) {
                                    it.deselect(id)
                                }
                                return@forEach
                            }
                            box.visibility = if (it.hasSelection()) {
                                View.VISIBLE
                            } else {
                                View.GONE
                            }
                            box.isChecked = it.isSelected(id)
                        }
                    }
                })
            }
            field = value
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view =
            LayoutInflater.from(parent.context).inflate(R.layout.item_video_list, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val data = getItem(position)
        val itemView = holder.itemView
        itemView.tag = data.id
        val checkBox = holder.checkBox

        when (val state = data.stateData) {
            is Video.State.Importing -> setUpAsImporting(itemView, holder, data, state.state)
            is Video.State.Downloading -> setUpAsDownloading(holder, state.state)
            else -> setUpNormally(itemView, holder, data, state)
        }
        tracker?.applyTrackerState(itemView, holder, holder.selectable, data.id)
        currentCheckBox.add(ViewContainer(data.id, checkBox, holder.selectable))
    }

    override fun onViewDetachedFromWindow(holder: ViewHolder) {
        currentCheckBox.remove(
            ViewContainer(
                holder.itemView.tag as Long,
                holder.checkBox,
                holder.selectable
            )
        )
    }

    private fun setUpAsImporting(
        itemView: View,
        holder: ViewHolder,
        video: Video,
        workerState: Video.WorkerState
    ) {
        val context = itemView.context
        val title = holder.title
        val domainOrSize = holder.domainOrSize
        val thumbnail = holder.thumbnail
        if (workerState is Video.WorkerState.Failed) {
            title.text = context.getString(R.string.item_video_list_import_failed)
            domainOrSize.text = context.getString(R.string.item_video_list_click_to_retry)
            thumbnail.setImageResource(R.drawable.ic_error)

            val url = workerState.url
            itemView.setOnClickListener { view ->
                val retryButtonMessage = R.string.dialog_video_import_failed_retry
                val clearButtonMessage = R.string.dialog_video_import_failed_clear

                AlertDialog.Builder(view.context)
                    .setTitle(R.string.dialog_video_import_failed_title)
                    .setMessage(url)
                    .setPositiveButton(retryButtonMessage) { _, _ ->
                        fragment.lifecycleScope.launch {
                            val targetPlaylists = fragment.playlistViewModel
                                .allPlaylistsAsync
                                .await()
                                .filter { it.videos.contains(video.id) }
                                .apply {
                                    fragment.playlistViewModel.update(deleteVideo(video.id)).join()
                                }
                                .map { it.id }
                                .toLongArray()
                            VideoInfoDownloadWorker.registerWorker(context, url, targetPlaylists)
                            fragment.videoViewModel.delete(video)
                        }
                    }.setNegativeButton(clearButtonMessage) { _, _ ->
                        fragment.lifecycleScope.launch {
                            val targetPlaylists = fragment.playlistViewModel
                                .allPlaylistsAsync
                                .await()
                                .filter { it.videos.contains(video.id) }
                                .deleteVideo(video.id)
                            fragment.playlistViewModel.update(targetPlaylists)
                            fragment.videoViewModel.delete(video)
                        }
                    }.show()
                holder.selectable = false
            }
        } else {
            title.text = video.title
            domainOrSize.visibility = View.GONE
            thumbnail.setImageResource(R.drawable.ic_download)
        }
    }

    private fun setUpAsDownloading(holder: ViewHolder, workerState: Video.WorkerState) {
        if (workerState is Video.WorkerState.Failed) {
            holder.selectable = false
            TODO("implement in #65")
        }
    }

    private fun setUpNormally(
        itemView: View,
        holder: ViewHolder,
        video: Video,
        state: Video.State
    ) {
        val context = itemView.context
        val title = holder.title
        val domainOrSize = holder.domainOrSize
        val thumbnail = holder.thumbnail
        title.text = video.title
        domainOrSize.text = if (state is Video.State.Downloaded) {
            context.getString(
                R.string.item_video_list_data_size,
                state.fileSize / BYTE_CARRY_IN / BYTE_CARRY_IN
            )
        } else {
            video.domain
        }
        Glide.with(itemView).load(video.thumbnailUrl).into(thumbnail)
        itemView.setOnClickListener {
            val navController = it.findFragment<Fragment>().findNavController()

            val args = FragmentVideoPlayerArgs(video.videoId).toBundle()
            navController.navigate(R.id.nav_graph_video_player, args)
        }
    }

    private fun SelectionTracker<Long>.applyTrackerState(
        view: View,
        holder: ViewHolder,
        selectable: Boolean,
        videoId: Long
    ) {
        var isSelected = isSelected(videoId)
        if (isSelected && !selectable) {
            fragment.lifecycleScope.launch(Dispatchers.Main) {
                deselect(videoId)
            }
        }
        isSelected = isSelected && selectable
        view.isActivated = isSelected
        val checkBox = holder.checkBox
        checkBox.isChecked = isSelected
        checkBox.visibility = if (hasSelection() && selectable) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    companion object {
        const val BYTE_CARRY_IN = 1024f
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.item_video_list_title)
        val domainOrSize: TextView = view.findViewById(R.id.item_video_domain_or_size)
        val thumbnail: ImageView = view.findViewById(R.id.item_video_list_thumbnail)
        val checkBox: CheckBox = view.findViewById(R.id.item_video_checkbox)

        var selectable: Boolean = true

        init {
            checkBox.visibility = View.GONE
        }

        fun toItemDetail(): ItemDetailsLookup.ItemDetails<Long> =
            object : ItemDetailsLookup.ItemDetails<Long>() {
                override fun getPosition(): Int = absoluteAdapterPosition
                override fun getSelectionKey(): Long? = itemView.tag as? Long
            }
    }

    class VideoListDetailsLookup(val recyclerView: RecyclerView) : ItemDetailsLookup<Long>() {
        override fun getItemDetails(e: MotionEvent): ItemDetails<Long>? {
            return recyclerView.findChildViewUnder(e.x, e.y)?.let { view ->
                val viewHolder = recyclerView.getChildViewHolder(view)
                if (viewHolder is ViewHolder) {
                    viewHolder.toItemDetail()
                } else {
                    null
                }
            }
        }
    }

    private data class ViewContainer(val id: Long, val checkBox: CheckBox, val selectable: Boolean)
}