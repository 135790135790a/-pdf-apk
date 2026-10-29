package com.mangashelf.reader.data

/**
 * 导入批次的状态机（v1.5.6）。
 *
 * 需求里点名要防的两种非法流转：
 * - 「已完成再暂停」——`DONE` / `PARTIAL` / `FAILED` / `CANCELLED` 是终态，任何流转都拒绝；
 * - 「已取消再继续」——`CANCELLED` 同样在终态里，`resume()` 之前会先过这里。
 *
 * 为什么不靠 UI 禁用按钮：暂停/继续/取消是三个入口（横幅、通知栏、启动恢复）在并发调用，
 * 光禁按钮挡不住通知栏那条路。真正的一致性靠这里 + DAO 的条件 UPDATE
 * （`UPDATE ... WHERE status = :expect`），两边都过才算流转成功。
 */
object ImportStatusMachine {

    /** 合法流转表：from -> 允许到达的状态集合 */
    private val allowed: Map<String, Set<String>> = mapOf(
        ImportBatch.QUEUED to setOf(
            ImportBatch.RUNNING, ImportBatch.PAUSED, ImportBatch.CANCELLED
        ),
        ImportBatch.RUNNING to setOf(
            ImportBatch.PAUSED, ImportBatch.DONE, ImportBatch.PARTIAL,
            ImportBatch.FAILED, ImportBatch.CANCELLED
        ),
        ImportBatch.PAUSED to setOf(ImportBatch.RUNNING, ImportBatch.CANCELLED),
        ImportBatch.DONE to emptySet(),
        ImportBatch.PARTIAL to emptySet(),
        ImportBatch.FAILED to emptySet(),
        ImportBatch.CANCELLED to emptySet()
    )

    @Volatile
    private var lastReject: String? = null

    /** 最近一次被拒绝的流转（`from->to`），排查用；正常流程里一直是 null */
    fun lastRejected(): String? = lastReject

    fun canMove(from: String, to: String): Boolean {
        if (from == to) return false
        val ok = allowed[from]?.contains(to) == true
        if (!ok) lastReject = "$from->$to"
        return ok
    }

    fun isTerminal(status: String): Boolean =
        status == ImportBatch.DONE || status == ImportBatch.PARTIAL ||
            status == ImportBatch.FAILED || status == ImportBatch.CANCELLED

    /**
     * 能不能「暂停」：排队中和导入中可以；已暂停不必再暂停。
     * 对应需求「导入中可暂停、已暂停可继续、已完成/失败不可暂停」。
     */
    fun canPause(status: String): Boolean =
        status == ImportBatch.QUEUED || status == ImportBatch.RUNNING

    /** 能不能「继续」：只有已暂停可以 */
    fun canResume(status: String): Boolean = status == ImportBatch.PAUSED

    /**
     * 由计数推断终态：全成功 → DONE；有成功有失败 → PARTIAL；
     * 全失败 → FAILED；一个都没跑（全跳过）→ CANCELLED。
     */
    fun terminalOf(done: Int, failed: Int, skipped: Int): String = when {
        done > 0 && failed == 0 -> ImportBatch.DONE
        done > 0 && failed > 0 -> ImportBatch.PARTIAL
        done == 0 && failed > 0 -> ImportBatch.FAILED
        else -> ImportBatch.CANCELLED
    }
}
