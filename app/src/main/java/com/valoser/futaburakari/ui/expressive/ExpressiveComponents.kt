package com.valoser.futaburakari.ui.expressive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.vector.ImageVector
import com.valoser.futaburakari.ui.theme.LocalSpacing

/**
 * Expressive（表現豊かな）UIのコンポーネント例を集約したショーケース。
 * AppBar / Buttons / FAB / Progress / Segmented buttons / FAB menu / Split button などを
 * デバッグ用途で横断的に確認できます。実アプリの業務ロジックは含みません。
 *
 * 利用例: `ExpressiveDemoActivity` からテーマ `expressive = true` で呼び出し、
 * 見た目やモーションの整合性をチェックします。
 *
 * 引数:
 * - `modifier`: ルートレイアウトに適用する `Modifier`
 * - `title`: 上部 AppBar に表示するタイトル
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpressiveShowcaseScreen(
    modifier: Modifier = Modifier,
    title: String = "Expressive UI"
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showFabMenu by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = { showDialog = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "menu")
                    }
                },
                actions = {
                    IconButton(onClick = { /* search */ }) {
                        Icon(Icons.Default.Search, contentDescription = "search")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        floatingActionButton = {
            FabMenu(
                expanded = showFabMenu,
                onExpandedChange = { showFabMenu = it },
                items = listOf(
                    FabMenuItem(Icons.Default.Favorite, "お気に入り") { /* no-op */ },
                    FabMenuItem(Icons.Default.Search, "検索") { /* no-op */ }
                )
            )
        },
        bottomBar = {
            NavigationBar {
                listOf("ホーム", "お気に入り", "検索").forEachIndexed { index, label ->
                    val selected = index == selectedTab
                    NavigationBarItem(
                        selected = selected,
                        onClick = { selectedTab = index },
                        icon = {
                            Icon(
                                when (index) {
                                    0 -> Icons.Default.Home
                                    1 -> Icons.Default.Favorite
                                    else -> Icons.Default.Search
                                }, contentDescription = label
                            )
                        },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(LocalSpacing.current.l),
            verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.l)
        ) {
            item {
                Text("Buttons", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.m)) {
                    Button(onClick = { loading = true }) { Text("Primary") }
                    ElevatedButton(onClick = { /* noop */ }) { Text("Elevated") }
                    IconButton(onClick = { /* noop */ }) { Icon(Icons.Default.Favorite, contentDescription = null) }
                }

                Spacer(Modifier.height(LocalSpacing.current.s))
                SplitButton(
                    text = "Split",
                    onPrimary = { /* primary */ },
                    menuItems = listOf("1つ目" to {}, "2つ目" to {})
                )
            }

            item {
                Text("Progress", style = MaterialTheme.typography.titleMedium)
                if (loading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(LocalSpacing.current.s))
                    Row(horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.m)) {
                        CircularProgressIndicator()
                        Text("読み込み中…", style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    Text("アイドル状態", style = MaterialTheme.typography.bodyLarge)
                }

                // 自動で擬似ロードを終了
                LaunchedEffect(loading) {
                    if (loading) {
                        delay(1500)
                        loading = false
                    }
                }
            }

            item {
                Text("Button groups", style = MaterialTheme.typography.titleMedium)
                var selected by remember { mutableIntStateOf(0) }
                val options = listOf("日", "週", "月")
                SingleChoiceSegmentedButtonRow {
                    options.forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = index == selected,
                            onClick = { selected = index },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size)
                        ) {
                            Text(label)
                        }
                    }
                }
            }
        }
        LoadingOverlay(visible = loading)
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                Button(onClick = { showDialog = false }) { Text("OK") }
            },
            dismissButton = {
                Button(onClick = { showDialog = false }) { Text("キャンセル") }
            },
            title = { Text("メニュー") },
            text = { Text("ここにアクションを配置できます。") }
        )
    }
}

/**
 * FAB メニュー項目のメタデータ。
 * `FabMenu` に渡してスピードダイアルの各アクションを表現します。
 */
data class FabMenuItem(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/**
 * FAB メニュー（Speed dial）。メインの拡張 FAB と、展開時に表示される小型 FAB 群で構成。
 */
@Composable
fun FabMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    items: List<FabMenuItem>
) {
    Box(contentAlignment = Alignment.BottomEnd) {
        Column(horizontalAlignment = Alignment.End) {
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.m)) {
                    items.forEach { it ->
                        SmallFloatingActionButton(onClick = { onExpandedChange(false); it.onClick() }) {
                            Icon(it.icon, contentDescription = it.label)
                        }
                    }
                }
            }
            val rotation by animateFloatAsState(targetValue = if (expanded) 45f else 0f, label = "fab-rotate")
            ExtendedFloatingActionButton(
                text = { Text(if (expanded) "閉じる" else "作成") },
                icon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.rotate(rotation)) },
                onClick = { onExpandedChange(!expanded) }
            )
        }
    }
}

/**
 * Split button（分割ボタン）: 左側にメインアクション、右側にオプションのドロップダウンを配置。
 */
@Composable
fun SplitButton(
    text: String,
    onPrimary: () -> Unit,
    menuItems: List<Pair<String, () -> Unit>>,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Row(modifier) {
        Button(onClick = onPrimary) { Text(text) }
        Button(
            onClick = { expanded = true },
            colors = ButtonDefaults.filledTonalButtonColors(),
            contentPadding = PaddingValues(horizontal = LocalSpacing.current.s)
        ) {
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            menuItems.forEach { (label, action) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    expanded = false
                    action()
                })
            }
        }
    }
}

/**
 * 画面全面に薄いサーフェスとインジケータを重ねる簡易ローディングオーバーレイ。
 *
 * パラメータ:
 * - `visible`: 表示するかどうか。
 */
@Composable
fun LoadingOverlay(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            ) { Box(Modifier.fillMaxSize()) }
            CircularProgressIndicator()
        }
    }
}
