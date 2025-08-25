package com.valoser.hutaburakari

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
// import android.view.MenuItem // onSupportNavigateUp を使うので、これは必須ではない
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import com.valoser.hutaburakari.databinding.ActivityBookmarkBinding
import com.valoser.hutaburakari.databinding.DialogAddBookmarkBinding
// MaterialToolbar の ViewBinding を使うので、直接の import は不要な場合が多い
// import com.google.android.material.appbar.MaterialToolbar

class BookmarkActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBookmarkBinding
    private lateinit var bookmarkAdapter: BookmarkAdapter
    private var currentEditingBookmarkUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityBookmarkBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup the Toolbar from the layout
        setSupportActionBar(binding.toolbar) // binding.toolbar は activity_bookmark.xml で定義したID

        // Enable the Up button (back arrow)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // Title is set in XML (app:title), so this line can be removed or commented
        // supportActionBar?.title = "ブックマーク管理" 

        setupRecyclerView()
        loadBookmarks()

        binding.btnAddBookmark.setOnClickListener {
            currentEditingBookmarkUrl = null
            showAddEditBookmarkDialog(null)
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.btnAddBookmark) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Get original margins
            val mlp = view.layoutParams as ViewGroup.MarginLayoutParams
            view.updateLayoutParams {
                // Ensure the type of 'this' inside the lambda is ViewGroup.MarginLayoutParams
                // If not, you might need to explicitly cast or check the type of layoutParams
                if (this is ViewGroup.MarginLayoutParams) {
                    bottomMargin = mlp.bottomMargin + insets.bottom
                }
                // Optionally adjust other margins if needed, e.g., for gesture navigation handles
                // leftMargin = mlp.leftMargin + insets.left
                // rightMargin = mlp.rightMargin + insets.right
            }
            // Return CONSUMED if you don't want the insets to be passed to other views
            WindowInsetsCompat.CONSUMED
        }
    }

    // This handles the Up button navigation
    override fun onSupportNavigateUp(): Boolean {
        finish() // Simply finish this activity to go back to the parent (MainActivity)
        return true
    }
    
    private fun setupRecyclerView() {
        bookmarkAdapter = BookmarkAdapter(
            onEditClick = { bookmark ->
                currentEditingBookmarkUrl = bookmark.url
                showAddEditBookmarkDialog(bookmark)
            },
            onDeleteClick = { bookmark ->
                showDeleteConfirmationDialog(bookmark)
            },
            onItemClick = { bookmark ->
                // When an item is clicked, save it as the selected bookmark and finish this activity
                BookmarkManager.saveSelectedBookmarkUrl(this, bookmark.url)
                Toast.makeText(this, "「${bookmark.name}」を選択しました", Toast.LENGTH_SHORT).show()
                finish()
            }
        )
        binding.rvBookmarks.apply {
            layoutManager = LinearLayoutManager(this@BookmarkActivity)
            adapter = bookmarkAdapter
        }
    }

    private fun loadBookmarks() {
        val bookmarks = BookmarkManager.getBookmarks(this)
        bookmarkAdapter.submitList(bookmarks.toList()) // Convert to List for submitList
    }

    private fun showAddEditBookmarkDialog(bookmarkToEdit: Bookmark?) {
        val dialogBinding = DialogAddBookmarkBinding.inflate(LayoutInflater.from(this))
        val etBookmarkName: EditText = dialogBinding.etBookmarkName
        val etBookmarkUrl: EditText = dialogBinding.etBookmarkUrl

        val dialogTitle = if (bookmarkToEdit == null) "ブックマークを追加" else "ブックマークを編集"

        bookmarkToEdit?.let {
            etBookmarkName.setText(it.name)
            etBookmarkUrl.setText(it.url)
        }

        AlertDialog.Builder(this)
            .setTitle(dialogTitle)
            .setView(dialogBinding.root)
            .setPositiveButton(if (bookmarkToEdit == null) "追加" else "保存") { dialog, _ ->
                val name = etBookmarkName.text.toString().trim()
                val url = etBookmarkUrl.text.toString().trim()

                if (name.isNotEmpty() && url.isNotEmpty()) {
                    if (bookmarkToEdit == null) { // Add new
                        BookmarkManager.addBookmark(this, Bookmark(name, url))
                        Toast.makeText(this, "ブックマークを追加しました", Toast.LENGTH_SHORT).show()
                    } else { // Edit existing
                        BookmarkManager.updateBookmark(this, bookmarkToEdit.url, Bookmark(name, url))
                        // If the edited bookmark was the currently selected one, update the selection
                        if (BookmarkManager.getSelectedBookmarkUrl(this) == bookmarkToEdit.url) {
                            BookmarkManager.saveSelectedBookmarkUrl(this, url)
                        }
                        Toast.makeText(this, "ブックマークを更新しました", Toast.LENGTH_SHORT).show()
                    }
                    loadBookmarks() // Refresh the list
                } else {
                    Toast.makeText(this, "名前とURLを入力してください", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showDeleteConfirmationDialog(bookmark: Bookmark) {
        AlertDialog.Builder(this)
            .setTitle("ブックマークを削除")
            .setMessage("「${bookmark.name}」を削除してもよろしいですか？")
            .setPositiveButton("削除") { dialog, _ ->
                BookmarkManager.deleteBookmark(this, bookmark)
                // If the deleted bookmark was the currently selected one, clear the selection
                // MainActivity will then prompt for a new selection or use the first available.
                if (BookmarkManager.getSelectedBookmarkUrl(this) == bookmark.url) {
                    BookmarkManager.saveSelectedBookmarkUrl(this, null)
                }
                loadBookmarks()
                Toast.makeText(this, "「${bookmark.name}」を削除しました", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }
}
