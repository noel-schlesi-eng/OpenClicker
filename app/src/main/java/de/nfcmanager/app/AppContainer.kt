package de.nfcmanager.app

import android.app.Application
import de.nfcmanager.app.data.AppDatabase
import de.nfcmanager.app.data.SettingsStore
import de.nfcmanager.app.data.TagRepository
import de.nfcmanager.app.nfc.EmulationController
import de.nfcmanager.app.nfc.EmulationStore
import kotlinx.coroutines.flow.MutableSharedFlow

/** Einfacher Dienste-Container (kein DI-Framework nötig). */
class NfcManagerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(app: Application) {
    private val database = AppDatabase.create(app)
    val repository = TagRepository(database.savedTagDao())
    val settings = SettingsStore(app)
    val emulationStore = EmulationStore(app)
    val emulation = EmulationController(app, emulationStore)

    /** Kurze Meldungen für die Snackbar. */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)

    fun post(text: String) {
        messages.tryEmit(text)
    }
}
