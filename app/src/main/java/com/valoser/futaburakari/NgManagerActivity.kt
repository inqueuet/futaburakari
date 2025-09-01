package com.valoser.futaburakari

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams

class NgManagerActivity : BaseActivity() {
    companion object {
        const val EXTRA_LIMIT_RULE_TYPE = "extra_limit_rule_type"
    }
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: NgRuleAdapter
    private lateinit var store: NgStore
    private var limitType: RuleType? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ng_manager)

        setSupportActionBar(findViewById(R.id.toolbar))
        // 既存画面の方針に合わせ、独自の戻るボタンのみ使用
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        findViewById<View>(R.id.backButton)?.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        store = NgStore(this)
        store.cleanup()
        limitType = intent.getStringExtra(EXTRA_LIMIT_RULE_TYPE)?.let {
            runCatching { RuleType.valueOf(it) }.getOrNull()
        }

        recyclerView = findViewById(R.id.ngRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = NgRuleAdapter(onDelete = { rule ->
            AlertDialog.Builder(this)
                .setTitle("削除")
                .setMessage("このNGを削除しますか？")
                .setPositiveButton("削除") { _, _ ->
                    store.removeRule(rule.id)
                    refresh()
                }
                .setNegativeButton("キャンセル", null)
                .show()
        })
        recyclerView.adapter = adapter
        refresh()

        findViewById<FloatingActionButton>(R.id.addNgFab)?.setOnClickListener { showAddDialog() }

        // Lift the FAB above navigation bar
        findViewById<FloatingActionButton>(R.id.addNgFab)?.let { fab ->
            val orig = (fab.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
            ViewCompat.setOnApplyWindowInsetsListener(fab) { v, insets ->
                val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
                    bottomMargin = orig + sys.bottom
                }
                WindowInsetsCompat.CONSUMED
            }
        }
    }

    private fun refresh() {
        val rules = store.getRules()
        val filtered = limitType?.let { t -> rules.filter { it.type == t } } ?: rules
        adapter.submit(filtered)
    }

    private fun showAddDialog() {
        val types = when (limitType) {
            RuleType.TITLE -> arrayOf("スレタイ")
            else -> arrayOf("ID", "本文ワード", "スレタイ")
        }
        AlertDialog.Builder(this)
            .setTitle("NGの種類")
            .setItems(types) { _, which ->
                when (limitType) {
                    RuleType.TITLE -> addDialogForType(RuleType.TITLE)
                    else -> when (which) {
                        0 -> addDialogForType(RuleType.ID)
                        1 -> addDialogForType(RuleType.BODY)
                        else -> addDialogForType(RuleType.TITLE)
                    }
                }
            }
            .show()
    }

    private fun addDialogForType(type: RuleType) {
        if (type == RuleType.ID) {
            val input = android.widget.EditText(this).apply { hint = "例: abc123" }
            AlertDialog.Builder(this)
                .setTitle("NG IDを追加")
                .setView(input)
                .setPositiveButton("追加") { _, _ ->
                    val text = input.text?.toString()?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        store.addRule(type, text, MatchType.EXACT)
                        refresh()
                    }
                }
                .setNegativeButton("キャンセル", null)
                .show()
            return
        }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 16, 32, 0)
        }
        val input = android.widget.EditText(this).apply {
            hint = if (type == RuleType.TITLE) "含めたくないスレタイ語句" else "含めたくない語句"
        }
        val radio = android.widget.RadioGroup(this).apply {
            val optSub = android.widget.RadioButton(context).apply { text = "部分一致"; id = 1; isChecked = true }
            val optPre = android.widget.RadioButton(context).apply { text = "前方一致"; id = 2 }
            val optRe  = android.widget.RadioButton(context).apply { text = "正規表現"; id = 3 }
            addView(optSub); addView(optPre); addView(optRe)
        }
        container.addView(input)
        container.addView(radio)

        AlertDialog.Builder(this)
            .setTitle(if (type == RuleType.TITLE) "スレタイNGを追加" else "NGワードを追加")
            .setView(container)
            .setPositiveButton("追加") { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    val mt = when (radio.checkedRadioButtonId) {
                        2 -> MatchType.PREFIX
                        3 -> MatchType.REGEX
                        else -> MatchType.SUBSTRING
                    }
                    store.addRule(type, text, mt)
                    refresh()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // 編集ダイアログ
    fun editRule(rule: NgRule) {
        if (rule.type == RuleType.ID) {
            val input = android.widget.EditText(this).apply {
                hint = "例: abc123"
                setText(rule.pattern)
            }
            AlertDialog.Builder(this)
                .setTitle("NG IDを編集")
                .setView(input)
                .setPositiveButton("保存") { _, _ ->
                    val text = input.text?.toString()?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        store.updateRule(rule.id, text, MatchType.EXACT)
                        refresh()
                    }
                }
                .setNegativeButton("キャンセル", null)
                .show()
            return
        }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 16, 32, 0)
        }
        val input = android.widget.EditText(this).apply {
            hint = if (rule.type == RuleType.TITLE) "含めたくないスレタイ語句" else "含めたくない語句"
            setText(rule.pattern)
        }
        val radio = android.widget.RadioGroup(this).apply {
            val optSub = android.widget.RadioButton(context).apply { text = "部分一致"; id = 1 }
            val optPre = android.widget.RadioButton(context).apply { text = "前方一致"; id = 2 }
            val optRe  = android.widget.RadioButton(context).apply { text = "正規表現"; id = 3 }
            addView(optSub); addView(optPre); addView(optRe)
            when (rule.match ?: MatchType.SUBSTRING) {
                MatchType.PREFIX -> check(2)
                MatchType.REGEX -> check(3)
                else -> check(1)
            }
        }
        container.addView(input)
        container.addView(radio)

        AlertDialog.Builder(this)
            .setTitle(if (rule.type == RuleType.TITLE) "スレタイNGを編集" else "NGワードを編集")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    val mt = when (radio.checkedRadioButtonId) {
                        2 -> MatchType.PREFIX
                        3 -> MatchType.REGEX
                        else -> MatchType.SUBSTRING
                    }
                    store.updateRule(rule.id, text, mt)
                    refresh()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }
}

private class NgRuleAdapter(
    private val onDelete: (NgRule) -> Unit
) : RecyclerView.Adapter<NgRuleViewHolder>() {
    private val items = mutableListOf<NgRule>()

    fun submit(list: List<NgRule>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NgRuleViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_ng_rule, parent, false)
        return NgRuleViewHolder(v)
    }

    override fun onBindViewHolder(holder: NgRuleViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
        holder.itemView.setOnLongClickListener { onDelete(item); true }
        holder.itemView.setOnClickListener { v ->
            val ctx = v.context
            val options = arrayOf("編集", "削除")
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> (ctx as? NgManagerActivity)?.editRule(item)
                        1 -> onDelete(item)
                    }
                }
                .show()
        }
    }

    override fun getItemCount(): Int = items.size
}

private class NgRuleViewHolder(v: View) : RecyclerView.ViewHolder(v) {
    private val title: TextView = v.findViewById(R.id.ruleTitle)
    private val subtitle: TextView = v.findViewById(R.id.ruleSubtitle)
    fun bind(rule: NgRule) {
        val mt = rule.match ?: when (rule.type) {
            RuleType.ID -> MatchType.EXACT
            RuleType.BODY -> MatchType.SUBSTRING
            RuleType.TITLE -> MatchType.SUBSTRING
        }
        val typeLabel = when (rule.type) {
            RuleType.ID -> "ID"
            RuleType.BODY -> "本文"
            RuleType.TITLE -> "タイトル"
        }
        val matchLabel = when (mt) {
            MatchType.EXACT -> "完全一致"
            MatchType.PREFIX -> "前方一致"
            MatchType.SUBSTRING -> "部分一致"
            MatchType.REGEX -> "正規表現"
        }
        title.text = "$typeLabel（$matchLabel）"
        subtitle.text = rule.pattern
    }
}
