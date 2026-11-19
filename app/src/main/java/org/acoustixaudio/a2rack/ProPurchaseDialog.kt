package org.acoustixaudio.a2rack

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ProPurchaseDialog(
    state: ProState,
    onBuy: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Support A2 Rack") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Pro helps fund open-source development. Everyone gets all features, no ads, and free updates forever.")
                if (!state.pending) {
                    state.price?.let { price ->
                        Text("$price one-time", style = MaterialTheme.typography.titleMedium)
                    }
                    Text("No subscription.", style = MaterialTheme.typography.bodySmall)
                }
                when {
                    state.pending -> Text("Your payment is pending in Google Play.")
                    state.purchasing -> Text("Opening Google Play checkout…")
                    state.message != null -> Text(state.message)
                    state.price == null -> Text("Checking price on Google Play…")
                }
            }
        },
        confirmButton = {
            val price = state.price
            when {
                state.pending -> Unit
                price != null -> Button(enabled = !state.purchasing, onClick = onBuy) {
                    Text("Buy Pro")
                }
                state.message != null -> TextButton(onClick = onRetry) { Text("Retry") }
            }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Not now") } }
    )
}
