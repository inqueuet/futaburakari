package com.valoser.futaburakari.ui.compose

/**
 * レス投稿画面。
 *
 * 機能概要:
 * - 入力: 名前/メール/題名/コメント/削除キー、任意のファイル添付（ドキュメントピッカー）。
 * - 初期値: `initialQuote` はコメント初期値、`initialPassword` は削除キー初期値。
 * - タイトル: `title` が空なら「レスを投稿」を表示。
 * - 送信中: `UiState.Loading` 中はテキスト入力と送信ボタンに加えて添付ボタンも無効化し、プログレスを表示（「画像なし」チェックは状態保持のため操作可能）。
 * - 添付: ピッカーで選択すると読み取りの永続権限を取得して `pickedUri` に保持。`textOnly` は「添付しない」フラグとして扱い、ピッカーの有効/無効を切り替え、ファイル選択時は `false` に戻す。
 *
 * パラメータ:
 * - `title`: 上部タイトル。
 * - `initialQuote`/`initialPassword`: コメント/削除キーの初期値。
 * - `uiState`: 送信状態（Loading 中は UI をロック）。
 * - `onBack`: 戻る押下時のハンドラ。
 * - `onSubmit`: 投稿実行のハンドラ。名前/メール/題名/削除キーは空文字を null に変換して渡す。
 */
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.valoser.futaburakari.ui.theme.LocalSpacing
import androidx.compose.ui.text.style.TextOverflow
import com.valoser.futaburakari.ReplyViewModel

/**
 * レス投稿画面のコンポーザブル。
 *
 * 機能概要:
 * - 入力: 名前/メール/題名/コメント/削除キー、任意のファイル添付に対応。
 * - 初期値: `initialQuote` はコメント初期値、`initialPassword` は削除キー初期値。
 * - タイトル: `title` が空なら「レスを投稿」を表示。
 * - 送信中: `UiState.Loading` 中はテキスト入力と送信ボタンに加えて添付ボタンも無効化し、プログレスを表示（「画像なし」チェックは状態保持のため操作可能）。
 * - 添付: ドキュメントピッカーで選択し、読取りの永続権限を取得して保持。`textOnly` は「添付しない」フラグとしてピッカーの有効/無効を制御し、ファイル選択時には `false` をセット。
 *
 * パラメータ:
 * - `title`: 上部タイトル。
 * - `initialQuote`/`initialPassword`: コメント/削除キーの初期値。
 * - `uiState`: 送信状態（Loading 中は UI をロック）。
 * - `onBack`: 戻る押下時のハンドラ。
 * - `onCommentChange`: コメント本文が変更された際に呼び出されるコールバック。
 * - `onSubmit`: 投稿実行のハンドラ（名前/メール/題名/削除キーは空文字を null に変換して渡す）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplyScreen(
    title: String,
    initialQuote: String,
    initialPassword: String?,
    uiState: ReplyViewModel.UiState,
    onBack: () -> Unit,
    onCommentChange: (String) -> Unit,
    onSubmit: (
        name: String?,
        email: String?,
        sub: String?,
        com: String,
        pwd: String?,
        upfileUri: Uri?,
        textOnly: Boolean
    ) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var sub by remember { mutableStateOf("") }
    var comment by rememberSaveable(initialQuote) { mutableStateOf(initialQuote) }
    var pwd by remember { mutableStateOf(initialPassword ?: "") }
    var textOnly by remember { mutableStateOf(false) }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var pickedLabel by remember { mutableStateOf("ファイルが選択されていません") }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    // ドキュメントピッカー。選択後は読取りの永続権限を取得して `pickedUri` とラベルを更新。
    // 何かを選んだ場合は添付ありになるよう `textOnly` をfalseにする。
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickedUri = uri
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            pickedLabel = uri.lastPathSegment ?: uri.toString()
            textOnly = false
        } else {
            pickedLabel = "ファイルが選択されていません"
        }
    }

    val isLoading = uiState is ReplyViewModel.UiState.Loading
    val focusManager = LocalFocusManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = if (title.isNotBlank()) title else "レスを投稿", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            // キーボード直上の送信ボタン
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        WindowInsets.navigationBars
                            .only(WindowInsetsSides.Bottom)
                            .union(WindowInsets.ime)
                    )
            ) {
                HorizontalDivider()
                Button(
                    onClick = {
                        onSubmit(name.ifBlank { null }, email.ifBlank { null }, sub.ifBlank { null }, comment, pwd.ifBlank { null }, pickedUri, textOnly)
                    },
                    enabled = !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LocalSpacing.current.l, vertical = LocalSpacing.current.s)
                ) { Text("返信する") }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .padding(horizontal = LocalSpacing.current.l, vertical = LocalSpacing.current.m)
                .verticalScroll(rememberScrollState()),
        ) {
            // おなまえ（任意）
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("おなまえ") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
            )
            Spacer(modifier = Modifier.height(LocalSpacing.current.s))

            // E-mail（任意）
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("E-mail") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
            )
            Spacer(modifier = Modifier.height(LocalSpacing.current.s))

            // 題名（任意）
            OutlinedTextField(
                value = sub,
                onValueChange = { sub = it },
                label = { Text("題名") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
            )
            Spacer(modifier = Modifier.height(LocalSpacing.current.s))

            // コメント（必須）
            OutlinedTextField(
                value = comment,
                onValueChange = {
                    comment = it
                    onCommentChange(it)
                },
                label = { Text("コメント") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                enabled = !isLoading,
                minLines = 6,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
            )

            Spacer(modifier = Modifier.height(LocalSpacing.current.m))
            // 添付
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("添付File", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.size(LocalSpacing.current.m))
                Text(
                    text = pickedLabel,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.size(LocalSpacing.current.s))
                // 任意のMIMEタイプを選択可能。`textOnly`（=添付しない）または送信中は無効化する。
                androidx.compose.material3.FilledTonalButton(onClick = { pickLauncher.launch(arrayOf("*/*")) }, enabled = !textOnly && !isLoading) {
                    Text("選択…")
                }
                Spacer(modifier = Modifier.size(LocalSpacing.current.s))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // ラベルは「画像なし」だが、実装上は「ファイルを添付しない」フラグとして機能する
                    Checkbox(checked = textOnly, onCheckedChange = { textOnly = it })
                    Text("画像なし", style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(modifier = Modifier.height(LocalSpacing.current.m))

            OutlinedTextField(
                value = pwd,
                onValueChange = { pwd = it },
                label = { Text("削除キー") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = {
                    focusManager.clearFocus()
                }),
                visualTransformation = PasswordVisualTransformation()
            )

            if (isLoading) {
                Spacer(modifier = Modifier.height(LocalSpacing.current.m))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            }

            // 画面下部にも余白を確保（スクロール可能領域の最下部）
            Spacer(modifier = Modifier.height(LocalSpacing.current.l))
        }
    }
}
