package com.sunny.notificationlog

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sunny.notificationlog.service.NotificationCaptureService
import com.sunny.notificationlog.ui.HistoryScreen
import com.sunny.notificationlog.viewmodel.NotificationLogViewModel

/**
 * Phase 1 screen: explains notification access and deep-links to the exact
 * Settings page that grants it.
 *
 * Why there is no runtime dialog: notification access is NOT a normal runtime
 * permission. There is no ActivityCompat.requestPermissions flow for it — the
 * only way to grant it is the system toggle under
 * Settings > Apps > Special app access > Notification access, which is exactly
 * where the button below sends the user.
 */
class MainActivity : ComponentActivity() {

    /**
     * A package is granted notification access iff its package name appears in
     * the system's list of enabled notification listeners.
     */
    private fun hasNotificationAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    /**
     * True when the OS promised not to kill us for battery reasons (Doze, app
     * standby, OEM "battery saver"). Phase 4: without this, some vendors'
     * battery managers unbind notification listeners within hours and capture
     * silently stops — the worst kind of failure for a history tool, because
     * the user only notices days later when something is missing.
     */
    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    /**
     * Opens the one-tap system dialog ("Allow Notification Log to always run in
     * the background?"). Requires the REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
     * manifest permission. Some heavily-skinned ROMs strip this action, so we
     * fall back to the general battery-optimization list where the user can
     * find the app manually — never crash over a missing Settings surface.
     */
    private fun requestIgnoreBatteryOptimizations() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (e: ActivityNotFoundException) {
            Log.w(NotificationCaptureService.TAG, "Direct doze dialog unavailable; falling back", e)
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: ActivityNotFoundException) {
                Log.e(
                    NotificationCaptureService.TAG,
                    "No battery optimization settings reachable on this ROM",
                    e2
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var granted by remember { mutableStateOf(hasNotificationAccess()) }
                var batteryExempt by remember { mutableStateOf(isIgnoringBatteryOptimizations()) }
                // Session-scoped: if the user dismisses the hint we don't nag
                // again until the next launch. Deliberately NOT persisted —
                // the risk of silent capture loss outweighs one banner per run.
                var batteryBannerDismissed by remember { mutableStateOf(false) }

                // Re-check on every ON_RESUME: the user leaves via the Settings
                // button and comes back through Recents/back, which does NOT
                // recreate the activity — without this observer the screen would
                // keep showing "access needed" even after granting. The battery
                // flag is refreshed here too because the system dialog we send
                // them to is a separate activity.
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            granted = hasNotificationAccess()
                            batteryExempt = isIgnoringBatteryOptimizations()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                // Phase 3 rule: still ONE app. No navigation graph — when
                // access is granted the permission screen is simply replaced
                // by the history screen (a plain conditional).
                if (granted) {
                    MainHistoryContent(
                        showBatteryBanner = !batteryExempt && !batteryBannerDismissed,
                        onRequestBatteryExemption = ::requestIgnoreBatteryOptimizations,
                        onDismissBatteryBanner = { batteryBannerDismissed = true }
                    )
                } else {
                    NotificationAccessScreen(
                        granted = false,
                        onOpenSettings = {
                            startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            )
                        }
                    )
                }
            }
        }
    }
}

/**
 * The one and only main screen: acquires its ViewModel and collects state,
 * delegating all rendering to the stateless composables in ui/.
 */
@Composable
private fun MainHistoryContent(
    showBatteryBanner: Boolean,
    onRequestBatteryExemption: () -> Unit,
    onDismissBatteryBanner: () -> Unit
) {
    val viewModel: NotificationLogViewModel =
        viewModel(factory = NotificationLogViewModel.Factory)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val proStatus by viewModel.proStatus.collectAsStateWithLifecycle()

    // One-shot billing connection kick when the screen first shows.
    LaunchedEffect(Unit) { viewModel.onScreenShown() }

    // Play Billing's launchBillingFlow needs an Activity reference — that's an
    // API shape, not a layering choice. LocalActivity (not a context cast)
    // hands it over cleanly; null outside a real activity context.
    val activity = LocalActivity.current

    HistoryScreen(
        uiState = uiState,
        searchQuery = searchQuery,
        onSearchQueryChange = viewModel::onSearchQueryChange,
        showBatteryBanner = showBatteryBanner,
        onRequestBatteryExemption = onRequestBatteryExemption,
        onDismissBatteryBanner = onDismissBatteryBanner,
        onDeleteNotification = viewModel::onDeleteNotification,
        onClearAll = viewModel::onClearAll,
        proStatus = proStatus,
        onLaunchPurchase = { activity?.let(viewModel::buyPro) },
        onExportToUri = viewModel::exportCsvTo
    )
}

/** Stateless screen: takes current state + event callback, knows nothing about the activity. */
@Composable
fun NotificationAccessScreen(
    granted: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (!granted) {
                Text(
                    text = stringResource(R.string.access_needed_title),
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.access_needed_body),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onOpenSettings) {
                    Text(stringResource(R.string.grant_access_button))
                }
            } else {
                Text(
                    text = stringResource(R.string.access_granted_title),
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.access_granted_body),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "Access not granted")
@Composable
private fun NotificationAccessScreenNotGrantedPreview() {
    MaterialTheme {
        NotificationAccessScreen(granted = false, onOpenSettings = {})
    }
}

@Preview(showBackground = true, name = "Access granted")
@Composable
private fun NotificationAccessScreenGrantedPreview() {
    MaterialTheme {
        NotificationAccessScreen(granted = true, onOpenSettings = {})
    }
}
