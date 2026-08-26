package com.sunny.notificationlog.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sunny.notificationlog.R
import com.sunny.notificationlog.billing.ProStatus
import com.sunny.notificationlog.viewmodel.HistoryUiState
import com.sunny.notificationlog.viewmodel.NotificationRow

/**
 * The one and only main screen: pinned search bar + reverse-chronological list.
 *
 * Stateless by design — it renders whatever [HistoryUiState] it is given and
 * reports events up. The ViewModel acquisition happens in MainActivity via the
 * viewModel() composable, keeping this file free of lifecycle wiring.
 *
 * Phase 4 additions:
 * - [showBatteryBanner]/[onRequestBatteryExemption]: one explainer banner about
 *   battery-optimization exemption (MainActivity owns the actual check/dialog).
 * - [onDeleteNotification] (swipe a row) and clear-all inside the overflow menu.
 *
 * Phase 7 additions:
 * - [proStatus] drives the single Pro entry point: an "Upgrade to Pro" item in
 *   the same unobtrusive ⋮ menu. Export CSV is the one Pro-gated feature; for
 *   free users it opens the upgrade dialog instead of a paywall interruption.
 *
 * The only state held locally is view-ephemeral UI state (menu/dialog visibility);
 * all data lives in the ViewModel per unidirectional data flow.
 */
@Composable
fun HistoryScreen(
    uiState: HistoryUiState,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    showBatteryBanner: Boolean = false,
    onRequestBatteryExemption: () -> Unit = {},
    onDismissBatteryBanner: () -> Unit = {},
    onDeleteNotification: (Long) -> Unit = {},
    onClearAll: () -> Unit = {},
    proStatus: ProStatus = ProStatus.Available(priceText = null),
    onLaunchPurchase: () -> Unit = {},
    onExportToUri: (Uri) -> Unit = {}
) {
    // View-ephemeral only (whether menus/dialogs are open); all data state
    // stays in the ViewModel.
    var menuExpanded by remember { mutableStateOf(false) }
    var showUpgradeDialog by remember { mutableStateOf(false) }
    var clearAllDialogVisible by remember { mutableStateOf(false) }

    // SAF file picker for the Pro export (phase 7). CreateDocument needs no
    // storage permission — the user picks (or creates) the destination file.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let(onExportToUri)
    }

    fun requestExport() {
        if (proStatus is ProStatus.Pro) {
            exportLauncher.launch("notification-history.csv")
        } else {
            // Free users hitting the gated feature IS the upgrade entry point.
            showUpgradeDialog = true
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SearchField(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    // Weighted so the menu icon can sit beside the field.
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.more_options)
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    when (proStatus) {
                        is ProStatus.Pro -> DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_pro_active)) },
                            leadingIcon = {
                                Icon(Icons.Filled.Check, contentDescription = null)
                            },
                            enabled = false,
                            onClick = {}
                        )
                        else -> DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_upgrade)) },
                            onClick = {
                                menuExpanded = false
                                showUpgradeDialog = true
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_export_csv)) },
                        onClick = {
                            menuExpanded = false
                            requestExport()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.clear_all_history)) },
                        onClick = {
                            menuExpanded = false
                            clearAllDialogVisible = true
                        }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (showBatteryBanner && uiState !is HistoryUiState.Error) {
                BatteryBanner(
                    onAllow = onRequestBatteryExemption,
                    onDismiss = onDismissBatteryBanner
                )
                Spacer(Modifier.height(12.dp))
            }
            when (val state = uiState) {
                is HistoryUiState.Loading -> LoadingContent()
                is HistoryUiState.Empty -> EmptyHistory()
                is HistoryUiState.NoResults -> EmptyResults(state.query)
                is HistoryUiState.Error -> ErrorContent(state.messageResId)
                is HistoryUiState.Content ->
                    NotificationList(state.notifications, onDeleteNotification)
            }
        }
    }

    // The dialog disappears by itself once status flips to Pro — no manual
    // dismissal plumbing after a successful purchase.
    if (showUpgradeDialog && proStatus !is ProStatus.Pro) {
        UpgradeDialog(
            proStatus = proStatus,
            onBuy = onLaunchPurchase,
            onDismiss = { showUpgradeDialog = false }
        )
    }
    if (clearAllDialogVisible) {
        ClearAllDialog(
            onConfirm = {
                clearAllDialogVisible = false
                onClearAll()
            },
            onDismiss = { clearAllDialogVisible = false }
        )
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(stringResource(R.string.search_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.clear_search)
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
    )
}

@Composable
private fun NotificationList(
    notifications: List<NotificationRow>,
    onDeleteNotification: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        items(notifications, key = { it.id }) { row ->
            SwipeToDeleteRow(
                row = row,
                onDelete = onDeleteNotification
            )
        }
    }
}

/**
 * Phase 4 swipe-to-delete. Wraps the card in a Material 3 SwipeToDismissBox:
 * swiping left reveals a red background with a delete icon; once the swipe
 * settles past the threshold the [onDismiss] callback fires the delete. (This
 * is the current API — the older confirmValueChange constructor parameter is
 * deprecated.) Dismissing leftwards is disabled so rows only ever leave via
 * the delete gesture. The LazyColumn `key` above keeps each row's swipe state
 * tied to its id, so rows never inherit each other's position during updates.
 */
@Composable
private fun SwipeToDeleteRow(
    row: NotificationRow,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val dismissState = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        onDismiss = { direction ->
            // Only EndToStart is enabled, but the explicit check documents
            // intent and guards against future direction changes.
            if (direction == SwipeToDismissBoxValue.EndToStart) onDelete(row.id)
        },
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.delete_notification),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
        }
    ) {
        NotificationItem(row)
    }
}

/**
 * Phase 7 purchase dialog: states what Pro adds (one line), shows Play's local
 * price, and hands off to the system purchase sheet. Deliberately quiet — no
 * timers, no badges; it only ever appears because the user asked for it.
 */
@Composable
private fun UpgradeDialog(
    proStatus: ProStatus,
    onBuy: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.upgrade_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.upgrade_dialog_feature))
                Spacer(Modifier.height(8.dp))
                when (val status = proStatus) {
                    is ProStatus.Available -> if (status.priceText == null) {
                        Text(
                            stringResource(R.string.upgrade_dialog_price_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    ProStatus.Pending -> Text(
                        stringResource(R.string.upgrade_dialog_pending),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> Unit
                }
            }
        },
        confirmButton = {
            val price = (proStatus as? ProStatus.Available)?.priceText
            // Buy stays disabled until Play reports a real price — launching a
            // flow without product details would just fail.
            TextButton(onClick = onBuy, enabled = price != null) {
                Text(
                    if (price != null) stringResource(R.string.buy_button, price)
                    else stringResource(R.string.buy_button_no_price)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.not_now_button))
            }
        }
    )
}

/**
 * Phase 4 guard for the destructive clear-all: an explicit confirmation rather
 * than an undo snackbar — simplest safe design, no extra state to manage.
 */
@Composable
private fun ClearAllDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.clear_all_dialog_title)) },
        text = { Text(stringResource(R.string.clear_all_dialog_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.clear_all_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        }
    )
}

/**
 * Phase 4 battery-optimization explainer. Shown while the app is NOT exempt;
 * "Allow" opens the system dialog from MainActivity. Dismissible per session —
 * MainActivity deliberately does not persist the dismissal, because losing
 * capture silently is worse than seeing this banner once more after a restart.
 */
@Composable
private fun BatteryBanner(
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.battery_banner_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.battery_banner_dismiss)
                    )
                }
            }
            Text(
                text = stringResource(R.string.battery_banner_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onAllow) {
                Text(stringResource(R.string.battery_banner_action))
            }
        }
    }
}

@Composable
fun NotificationItem(row: NotificationRow, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.appLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = row.relativeTimestamp,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = row.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Shown only for the instant before Room's first emission arrives. */
@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    CenteredMessage(modifier = modifier) {
        Text(stringResource(R.string.loading_history), style = MaterialTheme.typography.bodyLarge)
    }
}

/** Distinct state #1: nothing captured yet at all. */
@Composable
private fun EmptyHistory(modifier: Modifier = Modifier) {
    CenteredMessage(modifier = modifier) {
        Text(
            text = stringResource(R.string.empty_history_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.empty_history_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** Distinct state #2: a search term exists but matched nothing. */
@Composable
private fun EmptyResults(query: String, modifier: Modifier = Modifier) {
    CenteredMessage(modifier = modifier) {
        Text(
            text = stringResource(R.string.empty_results_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.empty_results_body, query),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ErrorContent(messageResId: Int, modifier: Modifier = Modifier) {
    CenteredMessage(modifier = modifier) {
        Text(
            text = stringResource(messageResId),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
    }
}

/** Shared layout shell so all four non-list states look consistent. */
@Composable
private fun CenteredMessage(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        content()
    }
}

// region Previews

private val sampleRows = listOf(
    NotificationRow(1, "Telegram", "Sofia", "Are we still on for tonight?", "5m ago"),
    NotificationRow(2, "Bank", "Login code", "Your code is 482913", "2h ago"),
    NotificationRow(3, "Calendar", "Reminder", "Dentist appointment tomorrow 09:30", "Yesterday")
)

@Preview(showBackground = true, name = "List with content")
@Composable
private fun HistoryListPreview() {
    MaterialTheme {
        HistoryScreen(
            uiState = HistoryUiState.Content(sampleRows),
            searchQuery = "",
            onSearchQueryChange = {}
        )
    }
}

@Preview(showBackground = true, name = "Empty history")
@Composable
private fun HistoryEmptyPreview() {
    MaterialTheme {
        HistoryScreen(
            uiState = HistoryUiState.Empty,
            searchQuery = "",
            onSearchQueryChange = {}
        )
    }
}

@Preview(showBackground = true, name = "No search results")
@Composable
private fun HistoryNoResultsPreview() {
    MaterialTheme {
        HistoryScreen(
            uiState = HistoryUiState.NoResults("zebra"),
            searchQuery = "zebra",
            onSearchQueryChange = {}
        )
    }
}

@Preview(showBackground = true, name = "Single item detail")
@Composable
private fun NotificationItemPreview() {
    MaterialTheme {
        NotificationItem(sampleRows.first())
    }
}
// endregion