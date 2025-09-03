package com.valoser.futaburakari.ui.detail

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.viewinterop.AndroidView
import com.valoser.futaburakari.databinding.ActivityDetailBinding

/**
 * Hybrid container for gradual Compose migration.
 * Currently embeds the existing XML root inside Compose.
 */
@Composable
fun DetailScreenHybrid(binding: ActivityDetailBinding) {
    AndroidView(factory = { binding.root })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreenScaffold(
    binding: ActivityDetailBinding,
    title: String,
    onBack: () -> Unit,
    onReply: () -> Unit,
    onReload: () -> Unit,
    onOpenNg: () -> Unit,
    onOpenMedia: () -> Unit,
    onSubmitSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
) {
    var showSearchDialog by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(TextFieldValue("")) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = title, maxLines = 2, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onReply) {
                        Icon(Icons.Filled.Reply, contentDescription = "Reply")
                    }
                    IconButton(onClick = onReload) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                    }
                    IconButton(onClick = { showSearchDialog = true }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = onOpenNg) {
                        Icon(Icons.Filled.Block, contentDescription = "NG Manage")
                    }
                    IconButton(onClick = onOpenMedia) {
                        Icon(Icons.Filled.Image, contentDescription = "Media List")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                )
            )
        }
    ) { contentPadding: PaddingValues ->
        // Legacy layout hosted as content; respect Scaffold paddings
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            factory = { binding.root },
            update = { /* no-op */ }
        )

        if (showSearchDialog) {
            AlertDialog(
                onDismissRequest = { showSearchDialog = false },
                title = { Text("検索") },
                text = {
                    androidx.compose.material3.OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text("キーワードを入力") }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val q = query.text.trim()
                        if (q.isNotEmpty()) onSubmitSearch(q)
                        showSearchDialog = false
                    }) { Text("検索") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        onClearSearch()
                        showSearchDialog = false
                    }) { Text("クリア") }
                }
            )
        }
    }
}
