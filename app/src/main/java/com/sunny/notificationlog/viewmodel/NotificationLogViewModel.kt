package com.sunny.notificationlog.viewmodel

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sunny.notificationlog.NotificationLogApp
import com.sunny.notificationlog.R
import com.sunny.notificationlog.billing.BillingRepository
import com.sunny.notificationlog.data.NotificationEntity
import com.sunny.notificationlog.data.NotificationRepository
import com.sunny.notificationlog.util.Csv
import com.sunny.notificationlog.util.RelativeTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.util.Log
import com.sunny.notificationlog.billing.ProStatus

/** Everything HistoryScreen renders; one sealed type = impossible invalid combos. */
sealed interface HistoryUiState {
    /** First emission from Room hasn't arrived yet. */
    data object Loading : HistoryUiState

    /** No notifications captured at all (blank query). */
    data object Empty : HistoryUiState

    /** A search term is active but matched nothing; carries the exact term. */
    data class NoResults(val query: String) : HistoryUiState

    data class Content(val notifications: List<NotificationRow>) : HistoryUiState

    /** Database read failed unexpectedly; shown as a non-crashing message. */
    data class Error(val messageResId: Int = R.string.error_generic) : HistoryUiState
}

/** Display-ready row: entity fields + presentation-only derived values. */
data class NotificationRow(
    val id: Long,
    val appLabel: String,
    val title: String,
    val text: String,
    val relativeTimestamp: String
)

/**
 * Holds search state and exposes the (optionally filtered) history.
 *
 * Data flow: TextField -> [onSearchQueryChange] -> MutableStateFlow ->
 * debounce -> flatMapLatest(repository.observeNotifications(query)) ->
 * Room re-queries on every DB change -> mapped to HistoryUiState -> StateFlow
 * collected by the screen. All database logic lives here, not in Composables
 * (phase-3 requirement, mirrors AGENTS.md MVVM rules).
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class NotificationLogViewModel(
    application: Application,
    // Injectable so tests can point at an in-memory database; production code
    // relies on the default and gets the app-wide singleton.
    private val repository: NotificationRepository =
        (application as NotificationLogApp).repository,
    // Injectable clock so tests can pin "now" and assert relative times exactly.
    private val now: () -> Long = System::currentTimeMillis,
    // Phase 7: billing wrapper. Default resolves the app singleton; tests that
    // exercise free-tier behavior pass a repository built with a no-op client.
    val billing: BillingRepository =
        (application as NotificationLogApp).billingRepository
) : AndroidViewModel(application) {

    /**
     * What the user typed RIGHT NOW — the TextField binds to this so keystrokes
     * echo instantly, while the list below waits for the debounced value.
     */
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    /**
     * Why debounce: Room can handle per-keystroke queries, but each one tears
     * down and rebuilds the Flow chain; 300ms feels instant to humans while
     * keeping the work done to a sane minimum.
     */
    val uiState: StateFlow<HistoryUiState> = _searchQuery
        .debounce(DEBOUNCE_MILLIS)
        .distinctUntilChanged()
        .flatMapLatest { query ->
            repository.observeNotifications(query).map { rows ->
                rows.toUiState(query)
            }
        }
        .catch { throwable ->
            // Never crash the collector; log and show a friendly dead-end.
            android.util.Log.e("NotificationLog", "Failed to observe notifications", throwable)
            emit(HistoryUiState.Error())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = HistoryUiState.Loading
        )

    fun onSearchQueryChange(query: String) {
        _searchQuery.update { query }
    }

    /**
     * Phase 4 swipe-to-delete. Fire-and-forget on purpose: the list updates
     * itself reactively the moment Room's InvalidationTracker notices the
     * DELETE, so there is no result value to plumb back into the UI.
     */
    fun onDeleteNotification(id: Long) {
        viewModelScope.launch {
            try {
                repository.deleteById(id)
            } catch (t: Throwable) {
                // Non-crashing per AGENTS.md: a failed delete leaves the row in
                // place and the list untouched — safe, just un-deleted.
                android.util.Log.e("NotificationLog", "Failed to delete notification #$id", t)
            }
        }
    }

    /** Phase 4 clear-all. Same reactive-update reasoning as [onDeleteNotification]. */
    fun onClearAll() {
        viewModelScope.launch {
            try {
                repository.deleteAll()
            } catch (t: Throwable) {
                android.util.Log.e("NotificationLog", "Failed to clear history", t)
            }
        }
    }

    /**
     * Phase 7: Pro entitlement state, re-exposed from the billing wrapper.
     * The screen renders this directly — no transformation needed yet, and
     * keeping one sealed type end-to-end means no invalid combinations.
     */
    val proStatus: StateFlow<ProStatus> = billing.status

    /** Called once when the history screen first appears; kicks off Play connection. */
    fun onScreenShown() {
        billing.connect()
    }

    /**
     * Launches the Play purchase sheet. Needs the Activity because that's how
     * Play Billing's API is shaped — the UI layer owns the Activity reference,
     * the ViewModel never touches one.
     */
    fun buyPro(activity: android.app.Activity) {
        billing.launchPurchase(activity)
    }

    /**
     * Phase 7 Pro feature: writes the whole history as RFC 4180 CSV through
     * the SAF file picker URI (no storage permission needed). Toasts are used
     * for feedback instead of snackbar scaffolding — pragmatic for a single
     * action in a settings-less utility; revisit if more feedback appears.
     */
    fun exportCsvTo(uri: Uri) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            try {
                val csv = Csv.build(repository.getAllForExport())
                val output = context.contentResolver.openOutputStream(uri)
                // A null stream means the provider rejected us; treat like any failure.
                    ?: throw IllegalStateException("Could not open output stream")
                output.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                Log.i("NotificationLog", "History exported (${csv.length} chars)")
                Toast.makeText(context, R.string.export_success, Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Log.e("NotificationLog", "CSV export failed", t)
                Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun List<NotificationEntity>.toUiState(query: String): HistoryUiState {
        if (isEmpty()) {
            return if (query.isBlank()) {
                HistoryUiState.Empty
            } else {
                HistoryUiState.NoResults(query)
            }
        }
        val currentMillis = now()
        return HistoryUiState.Content(
            map { entity ->
                NotificationRow(
                    id = entity.id,
                    appLabel = entity.appLabel,
                    title = entity.title,
                    text = entity.text,
                    relativeTimestamp =
                        RelativeTime.format(entity.timestamp, currentMillis)
                )
            }
        )
    }

    companion object {
        const val DEBOUNCE_MILLIS = 300L
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /**
         * Factory wiring the Application-scoped repository in. Explicit rather
         * than reflection-based default factory because of the extra `now`
         * constructor parameter (and it keeps tests free of reflection).
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                checkNotNull(app as? NotificationLogApp) {
                    "NotificationLogViewModel requires NotificationLogApp"
                }
                NotificationLogViewModel(app)
            }
        }
    }
}
