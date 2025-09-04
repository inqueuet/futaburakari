package com.valoser.futaburakari.ui.compose

/**
 * レス投稿画面。
 * - 名前/メール/題名/コメント/削除キーの入力、任意のファイル添付（ドキュメントピッカー）に対応。
 * - `initialQuote` はコメント初期値、`initialPassword` は削除キー初期値。
 * - タイトルが空の場合は "レスを投稿" を表示。
 * - 送信中（UiState.Loading）は入力と送信を無効化し、プログレスを表示。
 */
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.valoser.futaburakari.ReplyViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplyScreen(
    title: String,
    initialQuote: String,
    initialPassword: String?,
    uiState: ReplyViewModel.UiState,
    onBack: () -> Unit,
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
    var comment by remember { mutableStateOf(initialQuote) }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = if (title.isNotBlank()) title else "レスを投稿", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "戻る") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // おなまえ（任意）
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("おなまえ") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            )
            Spacer(modifier = Modifier.height(8.dp))

            // E-mail（任意）
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("E-mail") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            )
            Spacer(modifier = Modifier.height(8.dp))

            // 題名（任意）
            OutlinedTextField(
                value = sub,
                onValueChange = { sub = it },
                label = { Text("題名") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            )
            Spacer(modifier = Modifier.height(8.dp))

            // コメント（必須）
            OutlinedTextField(
                value = comment,
                onValueChange = { comment = it },
                label = { Text("コメント") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                enabled = !isLoading,
                minLines = 6
            )

            Spacer(modifier = Modifier.height(12.dp))
            // 添付
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("添付File", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.size(12.dp))
                Text(
                    text = pickedLabel,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.size(8.dp))
                // 任意のMIMEタイプを選択可能。`textOnly`（=添付しない）がオンの間は無効化。
                Button(onClick = { pickLauncher.launch(arrayOf("*/*")) }, enabled = !textOnly && !isLoading) {
                    Text("選択…")
                }
                Spacer(modifier = Modifier.size(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // ラベルは「画像なし」だが、実装上は「ファイルを添付しない」フラグとして機能する
                    Checkbox(checked = textOnly, onCheckedChange = { textOnly = it })
                    Text("画像なし", style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = pwd,
                onValueChange = { pwd = it },
                label = { Text("削除キー") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation()
            )

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    // 空文字は null に変換して送信。コメントはそのまま必須扱い。
                    onSubmit(name.ifBlank { null }, email.ifBlank { null }, sub.ifBlank { null }, comment, pwd.ifBlank { null }, pickedUri, textOnly)
                },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) { Text("返信する") }

            if (isLoading) {
                Spacer(modifier = Modifier.height(12.dp))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            }
        }
    }
}
