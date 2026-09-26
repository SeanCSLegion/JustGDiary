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
 * 因此**所有真正会移动的记录，其旧键都会先被腾空**；最终键只可能与一个
 * **最终不会移动**的记录相撞。
 *
 * 难点在于"最终会不会移动"是**互相依赖**的：A 想搬到 B 现在占着的键，
 * 只有当 B 自己也搬走时才成立；而 B 搬不搬，又取决于 B 的目标键有没有人让。
 * 所以要**迭代到不动点**（集合只会缩小，必然收敛）：
 *
 * ```
 * 候选 = {所有 newKey != oldKey 的行}
 * repeat:
 *   对每个候选 r：若 r 的目标键当前被 h 占着，且 h != r，且 h 不在候选集里 → 剔除 r
 * until 不再变化
 * ```
 *
 * ⚠️ 只判断"持有者想不想搬"是不够的：**被剔除（判为冲突）的行同样不会腾空它的旧键**，
 * 于是排在它后面的那一条会被误判为可以移动，第二阶段仍会撞 `day_key UNIQUE`。
 * （实测数据：C=9/24 不动、B=9/25 想搬到 9/24 被判冲突、A=9/26 想搬到 9/25 —— 
 *  只看"B 想不想搬"会放行 A，写库时 B 还占着 9/25，直接抛约束异常。）
 *
 * iOS 侧的判定写的是 `holderOf[row.oldKey]`，内层 `newKeys[ch] != oldKey` **恒为假** ——
 * 冲突分支永远进不去，`conflicts` 永远是 0。真撞上时第二阶段的 `UPDATE` 会违反
 * `day_key UNIQUE`：iOS 侧被外层 `try?` 吞掉并当作"0 冲突"，但**临时键已经写进去了**，
 * 归属日会残留成 `tmp#N`。
 *
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
    for (row in rows) {
        val key = if (row.firstUtc > 0) {
            DateUtil.dayKeyForUtc(row.firstUtc, dayStartHour)
        } else {
            row.oldKey
        }
        newKeys[row.id] = key
        currentHolderOf[row.oldKey] = row.id
    }

    // 候选 = 目标键与当前键不同的行；之后只会从这个集合里剔除，因此必然收敛。
    val candidates = rows.filter { newKeys[it.id] != it.oldKey }
    val movable = candidates.mapTo(mutableSetOf()) { it.id }
    var changed = true
    while (changed) {
        changed = false
        for (row in candidates) {
            if (row.id !in movable) continue
            val holder = currentHolderOf[newKeys[row.id]]
            // 目标键被别人占着，而那个人**最终不会搬走**（不在 movable 里）→ 这一条放弃
            if (holder != null && holder != row.id && holder !in movable) {
                movable.remove(row.id)
                changed = true
            }
        }
    }

    val movers = candidates.filter { it.id in movable }.map { it.id }
    val finalKeys = candidates.filter { it.id in movable }
        .associate { it.id to (newKeys[it.id] ?: it.oldKey) }
    return DayKeyPlan(
        movers = movers,
        finalKeys = finalKeys,
        conflicts = candidates.size - movers.size,
    )
}
