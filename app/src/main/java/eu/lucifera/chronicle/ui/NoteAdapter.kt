package eu.lucifera.chronicle.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import eu.lucifera.chronicle.R
import eu.lucifera.chronicle.data.Note
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.*

class NoteAdapter(private val onNoteClick: (Note) -> Unit) : ListAdapter<Note, NoteAdapter.NoteViewHolder>(NoteDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_note, parent, false)
        return NoteViewHolder(view, onNoteClick)
    }

    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class NoteViewHolder(itemView: View, private val onNoteClick: (Note) -> Unit) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_title)
        private val tvContent: TextView = itemView.findViewById(R.id.tv_content)
        private val tvTags: TextView = itemView.findViewById(R.id.tv_tags)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tv_timestamp)
        private val ivThumbnail: ImageView = itemView.findViewById(R.id.iv_thumbnail)
        private val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

        fun bind(note: Note) {
            itemView.setOnClickListener { onNoteClick(note) }
            tvTitle.text = note.title
            tvContent.text = note.content
            tvTimestamp.text = dateFormat.format(Date(note.timestamp))

            if (!note.tags.isNullOrBlank()) {
                val formattedTags = note.tags.split(",")
                    .joinToString(" ") { "#${it.trim()}" }
                tvTags.text = formattedTags
                tvTags.visibility = View.VISIBLE
            } else {
                tvTags.visibility = View.GONE
            }
            
            if (note.imageUrl != null) {
                ivThumbnail.setImageURI(Uri.parse(note.imageUrl))
                ivThumbnail.visibility = View.VISIBLE
            } else {
                ivThumbnail.visibility = View.GONE
            }
        }
    }

    class NoteDiffCallback : DiffUtil.ItemCallback<Note>() {
        override fun areItemsTheSame(oldItem: Note, newItem: Note): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Note, newItem: Note): Boolean = oldItem == newItem
    }
}
