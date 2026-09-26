package com.cov.justdiary.data

import com.cov.justdiary.model.DateUtil

/**
 * 「新一天的开始时间」改动后，归属日重算的**完整计划**。
 *
 * 抽成独立的纯函数（不碰数据库）是为了两件事：
 *  1. 设置页要**先算再问再改**（设计规范 `05-search-footprint-settings.md` §3.4）——
 *     预览与执行必须是同一份逻辑，否则"预览说 0 篇、执行却动了 3 篇"；
 *  2. 可以在 JVM 上直接把冲突判定逐条断言（见 `DayKeyPlanTest`）。
 *
 * 冲突的判定（**这里修掉了两端共有的一个数据损坏缺陷**）：
 *
 * 执行是两阶段的 —— 先把所有要移动的记录写成 `tmp#<id>`，再写各自的最终键。
 * 因此**所有移动者的旧键都会先被腾空**，最终键只可能与**不移动的**记录相撞。
 *
 * iOS 判断的是 `holderOf[row.oldKey]`（谁的新键等于我的**旧**键），而 `holderOf[k]`
 * 按定义就是"新键 == k 的那一行"，于是内层 `newKeys[ch] != oldKey` **恒为假** ——
 * 冲突分支永远进不去，`conflicts` 永远是 0。真撞上时（目标键被一个不移动的记录占着）
 * 第二阶段的 `UPDATE` 会违反 `day_key UNIQUE`：iOS 侧被外层 `try?` 吞掉并当作
 * "0 冲突"，但**临时键已经写进去了**，归属日会残留成 `tmp#N`。
 *
 * Android 按**真实约束**判断：目标键当前被一行不移动的记录占着 → 记一次冲突并跳过。
 * `day_key` 的取值在"不撞车"的所有情形下与 iOS 完全一致，因此跨端契约不受影响。
 */
internal data class DayKeyRow(val id: Long, val oldKey: String, val firstUtc: Long)

internal data class DayKeyPlan(
    /** 需要移动的记录 id（执行时先写 `tmp#<id>` 再写目标键，绕开 `day_key UNIQUE`）。 */
    val movers: List<Long>,
    /** 移动后的最终归属日。 */
    val finalKeys: Map<Long, String>,
    /** 因目标键被不移动的记录占着而放弃迁移的条数。 */
    val conflicts: Int,
) {
    /** 会改变归属日的日记篇数（设置页确认框里给用户看的那个数）。 */
    val movedBlocks: Int get() = movers.size
}

internal fun planDayKeys(rows: List<DayKeyRow>, dayStartHour: Int): DayKeyPlan {
    val newKeys = HashMap<Long, String>(rows.size)

    /** 当前（旧）键 → 持有它的记录 id。`day_key` 有 UNIQUE 约束，所以是一对一。 */
    val currentHolderOf = HashMap<String, Long>(rows.size)
    val oldKeyOf = HashMap<Long, String>(rows.size)
    for (row in rows) {
        val key = if (row.firstUtc > 0) {
            DateUtil.dayKeyForUtc(row.firstUtc, dayStartHour)
        } else {
            row.oldKey
        }
        newKeys[row.id] = key
        currentHolderOf[row.oldKey] = row.id
        oldKeyOf[row.id] = row.oldKey
    }

    var conflicts = 0
    val movers = mutableListOf<Long>()
    val finalKeys = HashMap<Long, String>()
    for (row in rows) {
        val newKey = newKeys[row.id] ?: row.oldKey
        if (newKey == row.oldKey) continue
        val holder = currentHolderOf[newKey]
        if (holder != null && holder != row.id) {
            // 目标键被别人占着：只有当那个人**自己也搬走**时才不算冲突（两阶段写会先腾空）
            val holderStays = newKeys[holder] == oldKeyOf[holder]
            if (holderStays) {
                conflicts += 1
                continue
            }
        }
        movers.add(row.id)
        finalKeys[row.id] = newKey
    }
    return DayKeyPlan(movers = movers, finalKeys = finalKeys, conflicts = conflicts)
}
