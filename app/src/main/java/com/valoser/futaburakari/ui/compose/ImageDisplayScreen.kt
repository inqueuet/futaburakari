package com.valoser.futaburakari.ui.compose

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.valoser.futaburakari.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
/**
 * 画像とプロンプト（説明文）を表示する画面。
 * - 画像は 1:1 の正方形エリアに `ContentScale.Crop` で表示（未指定時はプレースホルダー）。
 * - 下部にスクロール可能なプロンプト表示と「プロンプトをコピー」ボタンを配置。
 * - プロンプトが空の場合は文言を補い、コピーは無効化します。
 */
fun ImageDisplayScreen(
    imageUri: String?,
    prompt: String?,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val promptText = prompt?.takeIf { it.isNotBlank() } ?: stringResource(R.string.prompt_info_not_found)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.image), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "戻る") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // 画像表示エリア（1:1 の正方形）
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                if (!imageUri.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(ctx).data(imageUri).crossfade(true).build(),
                        contentDescription = stringResource(R.string.displayed_image_description),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    // 画像が無い場合のプレースホルダー
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) {}
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // プロンプト表示カード（本文はスクロール可能）
            Surface(
                tonalElevation = 1.dp,
                shape = MaterialTheme.shapes.medium
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(8.dp)
                ) {
                    Text(
                        text = promptText,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // プロンプトをクリップボードへコピー（空の場合はボタン無効）
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Button(
                    onClick = {
                        if (!prompt.isNullOrBlank()) {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("prompt", prompt))
                            android.widget.Toast.makeText(ctx, "プロンプトをコピーしました", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !prompt.isNullOrBlank()
                ) {
                    Text(text = stringResource(R.string.copy_prompt))
                }
            }
        }
    }
}
