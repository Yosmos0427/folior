package com.yussy.folior.store

/**
 * 编辑历史：整份工程 JSON 快照栈。
 *
 * 每次「可撤销的操作」之前压入一份快照，撤销/重做就是整份回放——
 * 不需要为每种元素、每种属性单独写逆向操作，也不会漏字段。
 * 栈深有限（默认 40 步），超出后丢弃最早的一份。
 */
class History(private val limit: Int = 40) {

    /** 一份快照：工程 JSON + 当时的当前页（撤销后回到同一页） */
    class Snapshot(val json: String, val pageIndex: Int)

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** 记录操作前的状态；记录新操作会清空重做栈（与常规编辑器一致） */
    fun push(before: Snapshot) {
        undoStack.addLast(before)
        while (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }

    /** 撤销：传入当前状态，返回要恢复的上一步；无历史时返回 null */
    fun undo(current: Snapshot): Snapshot? {
        val prev = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        return prev
    }

    /** 重做：传入当前状态，返回要恢复的下一步；无历史时返回 null */
    fun redo(current: Snapshot): Snapshot? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        return next
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
