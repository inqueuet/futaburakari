package com.valoser.futaburakari.ui.detail

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.viewinterop.AndroidView
import com.valoser.futaburakari.databinding.ActivityDetailBinding
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.platform.LocalDensity

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
    searchStateFlow: StateFlow<com.valoser.futaburakari.DetailSearchManager.SearchState>? = null,
    onSearchPrev: (() -> Unit)? = null,
    onSearchNext: (() -> Unit)? = null,
    bottomOffsetPxFlow: StateFlow<Int>? = null,
) {
    var query by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }

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
                    IconButton(onClick = { searchActive = !searchActive }) {
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            // Legacy content underneath
            AndroidView(
                modifier = Modifier.matchParentSize(),
                factory = { binding.root },
                update = { /* no-op */ }
            )

            // Compose検索バー（DockedSearchBar）: 虫眼鏡で表示/非表示をトグル
            if (searchActive) {
                DockedSearchBar(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 8.dp),
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = {
                        val q = query.trim()
                        if (q.isNotEmpty()) onSubmitSearch(q) else onClearSearch()
                        searchActive = false
                    },
                    active = true,
                    onActiveChange = { active -> searchActive = active },
                    placeholder = { Text("検索キーワード") },
                    leadingIcon = {
                        Icon(imageVector = Icons.Filled.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = {
                                query = ""
                                onClearSearch()
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Clear"
                                )
                            }
                        }
                    },
                    colors = SearchBarDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        inputFieldColors = SearchBarDefaults.inputFieldColors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            cursorColor = MaterialTheme.colorScheme.primary
                        )
                    )
                ) {
                    // Suggestion content: なし（必要なら最近の検索語等を表示可能）
                }
            }

            // 検索ナビ（↓↑ と 件数表示）— 従来の下部UI相当をComposeで重ねる
            if (searchStateFlow != null) {
                val s by searchStateFlow.collectAsState()
                if (s.active) {
                    val bottomPx by (bottomOffsetPxFlow ?: remember { mutableStateOf(0) }).collectAsState(initial = 0)
                    val bottomDp = with(LocalDensity.current) { bottomPx.toDp() }
                    SearchNavigationBar(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .padding(bottom = bottomDp),
                        current = s.currentIndexDisplay,
                        total = s.total,
                        onPrev = { onSearchPrev?.invoke() },
                        onNext = { onSearchNext?.invoke() }
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchNavigationBar(
    modifier: Modifier = Modifier,
    current: Int,
    total: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    androidx.compose.material3.Surface(
        modifier = modifier,
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = androidx.compose.material3.MaterialTheme.shapes.medium
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrev) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Prev")
            }
            Text(
                text = if (total > 0 && current in 1..total) "$current/$total" else "0/0",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            IconButton(onClick = onNext) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next")
            }
        }
    }
}
