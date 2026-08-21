package com.gatherin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.gatherin.ui.theme.*

// ── Shared top app bar ────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    eyebrow: String,
    onSignOut: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text  = eyebrow,
                    style = MaterialTheme.typography.labelSmall,
                    color = BrandBlue
                )
                Text(
                    text  = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        navigationIcon = {
            Box(
                contentAlignment = Alignment.Center,
                modifier         = Modifier.padding(start = 12.dp)
            ) {
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    color = BrandBlue,
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("🎟", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        actions = {
            actions()
            IconButton(onClick = onSignOut) {
                Icon(Icons.Default.ExitToApp, contentDescription = "Sign out", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor       = MaterialTheme.colorScheme.surface,
            // Keep the bar solid/flat even when content scrolls underneath —
            // no translucent scrim, no transparent reveal.
            scrolledContainerColor = MaterialTheme.colorScheme.surface,
        )
    )
}

// ── Empty state placeholder ───────────────────────────────────────────────────
@Composable
fun EmptyState(modifier: Modifier = Modifier, message: String) {
    Box(
        modifier          = modifier.fillMaxSize().padding(32.dp),
        contentAlignment  = Alignment.Center
    ) {
        Text(
            text  = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
