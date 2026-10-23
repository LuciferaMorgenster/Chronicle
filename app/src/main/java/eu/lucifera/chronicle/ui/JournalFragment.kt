package eu.lucifera.chronicle.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import eu.lucifera.chronicle.R
import eu.lucifera.chronicle.data.AppDatabase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class JournalFragment : Fragment() {
    private var searchJob: Job? = null
    private lateinit var adapter: NoteAdapter
    private lateinit var tvEmpty: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_journal, container, false)
        
        val fab: ExtendedFloatingActionButton = view.findViewById(R.id.fab_add)
        fab.setOnClickListener {
            findNavController().navigate(R.id.navigation_note_detail)
        }

        val rvNotes: RecyclerView = view.findViewById(R.id.rv_notes)
        tvEmpty = view.findViewById(R.id.tv_empty)
        
        adapter = NoteAdapter { note ->
            val bundle = Bundle().apply {
                putLong("noteId", note.id)
            }
            findNavController().navigate(R.id.navigation_note_detail, bundle)
        }
        rvNotes.adapter = adapter
        rvNotes.layoutManager = LinearLayoutManager(requireContext())

        val searchView: SearchView = view.findViewById(R.id.search_view)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                observeNotes(newText ?: "")
                return true
            }
        })

        observeNotes("")
        
        return view
    }

    private fun observeNotes(query: String) {
        searchJob?.cancel()
        val db = AppDatabase.getDatabase(requireContext())
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            val flow = if (query.isEmpty()) {
                db.noteDao().getAllNotes()
            } else {
                db.noteDao().searchNotes(query)
            }
            
            flow.collectLatest { notes ->
                adapter.submitList(notes)
                tvEmpty.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
}
