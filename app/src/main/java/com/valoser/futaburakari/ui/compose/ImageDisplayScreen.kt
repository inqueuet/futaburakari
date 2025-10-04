/**
 * 画像とプロンプト（説明文）を表示する画面のCompose実装。
 * - 正方形領域に画像を表示し、下部にスクロール可能なテキストとコピー操作を提供します。
 * - 画像読み込みは Coil のクロスフェードと必要な HTTP ヘッダ指定を用いて行います。
 * - プロンプトが空の場合は代替文言を表示し、コピー操作は無効化します。
 */
package com.valoser.futaburakari.ui.compose

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.transitionFactory
import coil3.transition.CrossfadeTransition
import coil3.network.httpHeaders
import coil3.network.NetworkHeaders
import com.valoser.futaburakari.R
import com.valoser.futaburakari.ui.theme.LocalSpacing

/**
 * 画像とプロンプト（説明文）を表示する画面コンポーザブル。
 *
 * 機能概要:
 * - 画像は 1:1 の正方形領域に `ContentScale.Crop` で表示（URI 未指定時はプレースホルダー）。
 * - 下部にスクロール可能なプロンプト領域と「プロンプトをコピー」ボタンを配置。
 * - プロンプトが空の場合は代替文言を表示し、コピーは無効化。
 *
 * パラメータ:
 * - `imageUri`: 表示する画像の URI（null/空の場合はプレースホルダー）。
 * - `prompt`: 表示/コピー対象のプロンプト文字列（空のときは代替文言を表示）。
 * - `onBack`: 上部の戻る操作時に呼ばれるハンドラ。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
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
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = LocalSpacing.current.l, vertical = LocalSpacing.current.m)
        ) {
            // 画像表示エリア（1:1 の正方形）。ContentScale.Crop で中央トリミング表示。
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                if (!imageUri.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(ctx)
                            .data(imageUri)
                            .apply {
                                httpHeaders(
                                    NetworkHeaders.Builder()
                                        .add("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                                        .add("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
                                        .build()
                                )
                            }
                            .transitionFactory(CrossfadeTransition.Factory())
                            .build(),
                        contentDescription = stringResource(R.string.displayed_image_description),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    // 画像が無い場合のプレースホルダー（サーフェスのバリアント色）
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) {}
                }
            }

            Spacer(modifier = Modifier.height(LocalSpacing.current.m))

            // プロンプト表示領域（Surface + スクロールする本文）。空のときは代替文言を表示。
            Surface(
                tonalElevation = 1.dp,
                shape = MaterialTheme.shapes.medium
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(LocalSpacing.current.s)
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

            Spacer(modifier = Modifier.height(LocalSpacing.current.s))

            // プロンプトをクリップボードへコピー（空の場合はボタン無効）
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.FilledTonalButton(
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
