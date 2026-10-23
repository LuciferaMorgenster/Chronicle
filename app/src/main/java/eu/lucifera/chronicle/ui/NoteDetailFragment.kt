package eu.lucifera.chronicle.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import eu.lucifera.chronicle.R
import eu.lucifera.chronicle.data.AppDatabase
import eu.lucifera.chronicle.data.Note
import eu.lucifera.chronicle.data.DriveServiceHelper
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Collections

import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class NoteDetailFragment : Fragment() {

    private lateinit var etTitle: EditText
    private lateinit var etContent: EditText
    private lateinit var etTags: EditText
    private lateinit var ivNoteImage: ImageView
    private var selectedImageUri: String? = null
    private var noteId: Long = -1L
    private var currentNote: Note? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            selectedImageUri = it.toString()
            ivNoteImage.setImageURI(it)
            ivNoteImage.visibility = View.VISIBLE
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_note_detail, container, false)
        
        etTitle = view.findViewById(R.id.et_title)
        etContent = view.findViewById(R.id.et_content)
        etTags = view.findViewById(R.id.et_tags)
        ivNoteImage = view.findViewById(R.id.iv_note_image)
        
        noteId = arguments?.getLong("noteId") ?: -1L
        val btnDelete: View = view.findViewById(R.id.btn_delete)

        if (noteId != -1L) {
            btnDelete.visibility = View.VISIBLE
            loadNote()
        }

        val toolbar: MaterialToolbar = view.findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        btnDelete.setOnClickListener {
            deleteNote()
        }

        view.findViewById<View>(R.id.btn_add_image).setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        view.findViewById<View>(R.id.btn_export_pdf).setOnClickListener {
            exportToPdf()
        }

        val fabSave: FloatingActionButton = view.findViewById(R.id.fab_save)
        fabSave.setOnClickListener {
            saveNote()
        }

        return view
    }

    private fun exportToPdf() {
        val title = etTitle.text.toString()
        val content = etContent.text.toString()

        if (title.isBlank() && content.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.toast_nothing_to_export), Toast.LENGTH_SHORT).show()
            return
        }

        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 size
        val page = pdfDocument.startPage(pageInfo)
        val canvas = page.canvas
        val paint = android.graphics.Paint()

        paint.textSize = 24f
        paint.isFakeBoldText = true
        canvas.drawText(if (title.isNotBlank()) title else getString(R.string.no_name_note), 50f, 50f, paint)

        paint.textSize = 14f
        paint.isFakeBoldText = false
        val lines = content.split("\n")
        var y = 100f
        for (line in lines) {
            canvas.drawText(line, 50f, y, paint)
            y += 20f
        }

        pdfDocument.finishPage(page)

        val fileName = "Chronicle_${System.currentTimeMillis()}.pdf"
        val file = File(requireContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)

        try {
            pdfDocument.writeTo(FileOutputStream(file))
            Toast.makeText(requireContext(), getString(R.string.toast_pdf_saved), Toast.LENGTH_LONG).show()
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(requireContext(), getString(R.string.toast_pdf_error), Toast.LENGTH_SHORT).show()
        } finally {
            pdfDocument.close()
        }
    }

    private fun loadNote() {
        val db = AppDatabase.getDatabase(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            currentNote = db.noteDao().getNoteById(noteId)
            currentNote?.let { note ->
                etTitle.setText(note.title)
                etContent.setText(note.content)
                etTags.setText(note.tags)
                note.imageUrl?.let { uri ->
                    selectedImageUri = uri
                    ivNoteImage.setImageURI(android.net.Uri.parse(uri))
                    ivNoteImage.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun deleteNote() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_title)
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                val db = AppDatabase.getDatabase(requireContext())
                viewLifecycleOwner.lifecycleScope.launch {
                    currentNote?.let { db.noteDao().delete(it) }
                    findNavController().navigateUp()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveNote() {
        val title = etTitle.text.toString()
        val content = etContent.text.toString()
        val tags = etTags.text.toString()

        if (title.isBlank() && content.isBlank() && selectedImageUri == null) {
            findNavController().navigateUp()
            return
        }

        val note = if (noteId != -1L) {
            currentNote?.copy(title = title, content = content, imageUrl = selectedImageUri, tags = tags, isSynced = false)
                ?: Note(title = title, content = content, imageUrl = selectedImageUri, tags = tags)
        } else {
            Note(title = title, content = content, imageUrl = selectedImageUri, tags = tags)
        }

        val db = AppDatabase.getDatabase(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            db.noteDao().insert(note)
            attemptAutoSync()
            findNavController().navigateUp()
        }
    }

    private suspend fun attemptAutoSync() {
        val account = GoogleSignIn.getLastSignedInAccount(requireContext()) ?: return
        
        try {
            val credential = GoogleAccountCredential.usingOAuth2(
                requireContext(), Collections.singletonList(DriveScopes.DRIVE_FILE)
            ).setSelectedAccount(account.account)

            val driveService = Drive.Builder(
                NetHttpTransport(),
                GsonFactory(),
                credential
            ).setApplicationName("Chronicle").build()

            val driveHelper = DriveServiceHelper(driveService)
            val db = AppDatabase.getDatabase(requireContext())
            
            val notes = db.noteDao().getAllNotes().first()
            val jsonContent = Gson().toJson(notes)
            driveHelper.uploadBackup(jsonContent)
        } catch (e: Exception) {
            // Silently fail for auto-sync to not disturb the user experience
            e.printStackTrace()
        }
    }
}
