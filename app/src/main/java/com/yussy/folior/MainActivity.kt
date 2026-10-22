package com.yussy.folior

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yussy.folior.model.Project
import com.yussy.folior.store.Store
import com.yussy.folior.ui.ProjectDialog

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: ProjectAdapter
    private val projects = mutableListOf<Project>()

    companion object {
        const val REQ_IMPORT = 101
        const val REQ_EXPORT_BUNDLE = 102
    }

    private var pendingExport: Project? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun buildUi(): View {
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFAFAF7.toInt())
        }
        // 顶栏
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * d).toInt(), (28 * d).toInt(), (20 * d).toInt(), (10 * d).toInt())
        }
        top.addView(TextView(this).apply {
            text = "页册"
            textSize = 26f
            setTextColor(0xFF1A1A18.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(TextView(this).apply {
            text = "导入"
            setTextColor(0xFF1A1A18.toInt())
            setPadding((16 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
            setOnClickListener { pickImport() }
        })
        root.addView(top)

        root.addView(TextView(this).apply {
            text = "摄影书 · 展览编排"
            textSize = 12f
            setTextColor(0xFF6B6B66.toInt())
            letterSpacing = 0.1f
            setPadding((20 * d).toInt(), 0, (20 * d).toInt(), (14 * d).toInt())
        })

        // 项目列表
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        adapter = ProjectAdapter()
        rv.adapter = adapter
        root.addView(rv)

        // 新建按钮
        root.addView(TextView(this).apply {
            text = "+ 新建照片集"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFFFAFAF7.toInt())
            setBackgroundColor(0xFF1A1A18.toInt())
            setPadding(0, (16 * d).toInt(), 0, (16 * d).toInt())
            (layoutParams as? LinearLayout.LayoutParams)?.setMargins(
                (20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), (24 * d).toInt())
            setOnClickListener { newProject() }
        })
        return root
    }

    private fun load() {
        projects.clear()
        projects.addAll(Store.listProjects(this))
        adapter.notifyDataSetChanged()
    }

    private fun newProject() {
        ProjectDialog.show(this, false, "未命名", "book", 210f, 297f, "#FFFFFF") { r ->
            val p = Project(title = r.title, type = r.type, pageWmm = r.wmm, pageHmm = r.hmm, defaultBg = r.bg)
            p.pages.add(com.yussy.folior.model.Page())
            Store.save(this, p)
            open(p)
        }
    }

    private fun open(p: Project) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra("id", p.id))
    }

    private fun pickImport() {
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            startActivityForResult(this, REQ_IMPORT)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            REQ_IMPORT -> {
                try {
                    contentResolver.openInputStream(uri)?.use {
                        val p = Store.importBundle(this, it)
                        if (p != null) { load(); Toast.makeText(this, "已导入：${p.title}", Toast.LENGTH_SHORT).show() }
                        else Toast.makeText(this, "导入失败：文件无效", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "导入失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            REQ_EXPORT_BUNDLE -> {
                val p = pendingExport
                try {
                    contentResolver.openOutputStream(uri)?.use {
                        Store.exportBundle(this, p!!, it)
                        Toast.makeText(this, "工程文件已导出", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun exportProjectFile(p: Project) {
        pendingExport = p
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "${p.title}.folio")
            startActivityForResult(this, REQ_EXPORT_BUNDLE)
        }
    }

    inner class ProjectAdapter : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val d = resources.displayMetrics.density
            val cell = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((20 * d).toInt(), (14 * d).toInt(), (20 * d).toInt(), (14 * d).toInt())
            }
            return VH(cell)
        }

        override fun getItemCount(): Int = projects.size

        override fun onBindViewHolder(holder: VH, pos: Int) {
            val p = projects[pos]
            holder.title.text = p.title
            holder.sub.text = buildString {
                append(if (p.type == Project.TYPE_EXHIBITION) "展览" else "摄影书")
                append(" · ${p.pages.size} 页 · ${p.pageWmm.toInt()}×${p.pageHmm.toInt()}mm")
            }
            holder.itemView.setOnClickListener { open(p) }
            holder.itemView.setOnLongClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setItems(arrayOf("打开", "导出工程文件 (.folio)", "删除")) { _, which ->
                        when (which) {
                            0 -> open(p)
                            1 -> exportProjectFile(p)
                            2 -> AlertDialog.Builder(this@MainActivity)
                                .setTitle("删除「${p.title}」？")
                                .setPositiveButton("删除") { _, _ -> Store.delete(this@MainActivity, p.id); load() }
                                .setNegativeButton("取消", null).show()
                        }
                    }.show()
                true
            }
        }
    }

    inner class VH(cell: LinearLayout) : RecyclerView.ViewHolder(cell) {
        val title: TextView
        val sub: TextView

        init {
            val d = resources.displayMetrics.density
            val col = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            title = TextView(cell.context).apply {
                textSize = 16f
                setTextColor(0xFF1A1A18.toInt())
            }
            sub = TextView(cell.context).apply {
                textSize = 12f
                setTextColor(0xFF6B6B66.toInt())
                setPadding(0, (2 * d).toInt(), 0, 0)
            }
            col.addView(title); col.addView(sub)
            val arrow = TextView(cell.context).apply { text = "›"; textSize = 20f; setTextColor(0xFFBDB8AA.toInt()) }
            cell.addView(col); cell.addView(arrow)
            // 分隔线
            cell.background = android.graphics.drawable.GradientDrawable().apply {
                setStroke(0, 0)
                setColor(Color.TRANSPARENT)
            }
        }
    }
}
