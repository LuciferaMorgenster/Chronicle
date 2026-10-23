package eu.lucifera.chronicle.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import eu.lucifera.chronicle.R
import eu.lucifera.chronicle.data.AppDatabase
import eu.lucifera.chronicle.data.DriveServiceHelper
import eu.lucifera.chronicle.data.Note
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.Locale
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Intent
import eu.lucifera.chronicle.ReminderReceiver
import java.util.Calendar
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

class SettingsFragment : Fragment() {

    private lateinit var btnSyncNow: MaterialButton
    private lateinit var btnRestore: MaterialButton
    private lateinit var switchSync: SwitchMaterial
    private lateinit var switchDarkMode: SwitchMaterial
    private lateinit var switchSecurity: SwitchMaterial
    private lateinit var switchReminder: SwitchMaterial
    private lateinit var btnSelectTime: MaterialButton
    private lateinit var tvCurrentLanguage: TextView

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        if (task.isSuccessful) {
            val account = task.result
            updateSyncUI(account)
            Toast.makeText(requireContext(), getString(R.string.toast_logged_in), Toast.LENGTH_SHORT).show()
        } else {
            switchSync.isChecked = false
            val exception = task.exception
            val errorMessage = if (exception is com.google.android.gms.common.api.ApiException) {
                "Code: ${exception.statusCode}"
            } else {
                exception?.message ?: "Unknown error"
            }
            Toast.makeText(requireContext(), "${getString(R.string.toast_login_failed)} ($errorMessage)", Toast.LENGTH_LONG).show()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            showTimePickerDialog()
        } else {
            switchReminder.isChecked = false
            Toast.makeText(requireContext(), getString(R.string.toast_permission_required), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)

        switchSync = view.findViewById(R.id.switch_sync)
        switchDarkMode = view.findViewById(R.id.switch_dark_mode)
        switchSecurity = view.findViewById(R.id.switch_security)
        switchReminder = view.findViewById(R.id.switch_reminder)
        btnSelectTime = view.findViewById(R.id.btn_select_time)
        tvCurrentLanguage = view.findViewById(R.id.tv_current_language)
        btnSyncNow = view.findViewById(R.id.btn_sync_now)
        btnRestore = view.findViewById(R.id.btn_restore)
        val btnLogout: MaterialButton = view.findViewById(R.id.btn_logout)

        val sharedPrefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
        switchDarkMode.isChecked = sharedPrefs.getBoolean("dark_mode", false)
        switchSecurity.isChecked = sharedPrefs.getBoolean("security_enabled", false)
        switchReminder.isChecked = sharedPrefs.getBoolean("reminder_enabled", false)
        
        btnSelectTime.visibility = if (switchReminder.isChecked) View.VISIBLE else View.GONE

        switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            sharedPrefs.edit().putBoolean("dark_mode", isChecked).apply()
            if (isChecked) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }

        switchSecurity.setOnCheckedChangeListener { _, isChecked ->
            sharedPrefs.edit().putBoolean("security_enabled", isChecked).apply()
        }

        switchReminder.setOnCheckedChangeListener { _, isChecked ->
            sharedPrefs.edit().putBoolean("reminder_enabled", isChecked).apply()
            btnSelectTime.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                checkNotificationPermission()
            } else {
                cancelReminder()
            }
        }

        btnSelectTime.setOnClickListener {
            showTimePickerDialog()
        }

        updateLanguageLabel()

        view.findViewById<View>(R.id.btn_select_language).setOnClickListener {
            showLanguageDialog()
        }

        val account = GoogleSignIn.getLastSignedInAccount(requireContext())
        updateSyncUI(account)

        switchSync.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && GoogleSignIn.getLastSignedInAccount(requireContext()) == null) {
                signInToGoogle()
            }
        }

        btnSyncNow.setOnClickListener {
            performSync()
        }

        btnRestore.setOnClickListener {
            performRestore()
        }

        btnLogout.setOnClickListener {
            signOut()
        }

        return view
    }

    private fun updateSyncUI(account: GoogleSignInAccount?) {
        val isLoggedIn = account != null
        switchSync.isChecked = isLoggedIn
        btnSyncNow.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
        btnRestore.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
    }

    private fun performSync() {
        val account = GoogleSignIn.getLastSignedInAccount(requireContext()) ?: return
        
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

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val notes = db.noteDao().getAllNotes().first()
                val jsonContent = Gson().toJson(notes)
                driveHelper.uploadBackup(jsonContent)
                Toast.makeText(requireContext(), getString(R.string.toast_sync_success), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.toast_sync_failed, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun performRestore() {
        val account = GoogleSignIn.getLastSignedInAccount(requireContext()) ?: return

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

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val jsonContent = driveHelper.downloadBackup()
                if (jsonContent != null) {
                    val noteListType = object : TypeToken<List<Note>>() {}.type
                    val notes: List<Note> = Gson().fromJson(jsonContent, noteListType)
                    
                    notes.forEach { note ->
                        db.noteDao().insert(note)
                    }
                    Toast.makeText(requireContext(), getString(R.string.toast_restore_success, notes.size), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.toast_restore_no_file), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.toast_restore_failed, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun signInToGoogle() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()

        val googleSignInClient = GoogleSignIn.getClient(requireActivity(), gso)
        googleSignInLauncher.launch(googleSignInClient.signInIntent)
    }

    private fun signOut() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
        val googleSignInClient = GoogleSignIn.getClient(requireActivity(), gso)
        googleSignInClient.signOut().addOnCompleteListener {
            updateSyncUI(null)
            Toast.makeText(requireContext(), getString(R.string.toast_logged_out), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTimePickerDialog() {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)

        val timePickerDialog = TimePickerDialog(requireContext(), { _, selectedHour, selectedMinute ->
            scheduleReminder(selectedHour, selectedMinute)
        }, hour, minute, true)
        timePickerDialog.show()
    }

    private fun scheduleReminder(hour: Int, minute: Int) {
        val alarmManager = requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(requireContext(), ReminderReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            requireContext(), 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DATE, 1)
            }
        }

        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            pendingIntent
        )
        
        Toast.makeText(requireContext(), getString(R.string.toast_reminder_set, String.format(Locale.getDefault(), "%02d:%02d", hour, minute)), Toast.LENGTH_SHORT).show()
    }

    private fun cancelReminder() {
        val alarmManager = requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(requireContext(), ReminderReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            requireContext(), 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        Toast.makeText(requireContext(), getString(R.string.toast_reminder_cancelled), Toast.LENGTH_SHORT).show()
    }

    private fun showLanguageDialog() {
        val languages = arrayOf(
            getString(R.string.lang_en),
            getString(R.string.lang_nl),
            getString(R.string.lang_de),
            getString(R.string.lang_fr),
            getString(R.string.lang_es)
        )
        val codes = arrayOf("en", "nl", "de", "fr", "es")
        
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        val currentCode = if (currentLocales.isEmpty) "en" else currentLocales.get(0)?.language ?: "en"
        val checkedItem = codes.indexOf(currentCode).let { if (it == -1) 0 else it }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_language)
            .setSingleChoiceItems(languages, checkedItem) { dialog, which ->
                val selectedCode = codes[which]
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(selectedCode))
                updateLanguageLabel()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateLanguageLabel() {
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        val currentCode = if (currentLocales.isEmpty) "en" else currentLocales.get(0)?.language ?: "en"
        
        val label = when(currentCode) {
            "nl" -> getString(R.string.lang_nl)
            "de" -> getString(R.string.lang_de)
            "fr" -> getString(R.string.lang_fr)
            "es" -> getString(R.string.lang_es)
            else -> getString(R.string.lang_en)
        }
        tvCurrentLanguage.text = label
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    showTimePickerDialog()
                }
                else -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            showTimePickerDialog()
        }
    }
}
