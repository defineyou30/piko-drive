package com.pikodrive.ui.piko

import com.pikodrive.core.model.Expression
import com.pikodrive.core.model.GrowthStage

/**
 * 피코의 픽셀 격자 정의 (FRAMEWORK 1-1-1 고유 캐릭터 문법, 평가 후 v2).
 *
 * v1은 대각 더듬이 2개 + 팔 + 다리라서 기존 로봇 마스코트·아케이드 캐릭터와 겹쳤다 (reviews/eval-design.md).
 * v2 문법:
 * - 11 x 15 격자, 팔·다리 없는 세로 타원 (실루엣 폭 최대 9칸, 2:3)
 * - 정수리 중앙의 **램프 꼭지** 하나 (헤드램프에서 온 고유 요소). 완성 단계에서 앰버로 점등
 * - 얼굴의 **바이저 띠**: 몸이 얼굴을 덮으면 눈 줄(3행)이 어두운 띠가 되고 눈은 그 안에서 밝게 빛난다
 *   → 어느 단계든 "어두운 바탕 위 밝은 눈"이라 표정이 또렷하다
 * - 코어 픽셀(앰버) = 처음 태어난 픽셀. 완성 단계에서 위아래로 한 칸씩 자라 3칸이 된다
 * - 감정은 눈 모양으로만 (입 없음). 표정끼리 픽셀 수·방향이 확실히 다르다
 */
object PikoSprite {
    const val COLS = 11
    const val ROWS = 15

    private val SILHOUETTE = listOf(
        ".....#.....", // 0 램프 꼭지
        ".....#.....", // 1 꼭지 줄기
        "...#####...", // 2
        "..#######..", // 3
        ".#########.", // 4
        ".#########.", // 5
        ".#########.", // 6 ┐
        ".#########.", // 7 │ 바이저 (눈 줄)
        ".#########.", // 8 ┘
        ".#########.", // 9
        ".#########.", // 10
        ".#########.", // 11
        "..#######..", // 12
        "..#######..", // 13
        "...#####...", // 14 평평한 바닥
    )

    val core = Cell(row = 10, col = 5)

    /** 완성 단계에서 코어가 위아래로 자라는 칸 (심장 확장 보상) */
    val coreExtension = listOf(Cell(9, 5), Cell(11, 5))

    private val allCells: List<Cell> = buildList {
        SILHOUETTE.forEachIndexed { r, line ->
            line.forEachIndexed { c, ch -> if (ch == '#') add(Cell(r, c)) }
        }
    }

    /** 램프 꼭지(줄기 + 끝). FACE부터 보이고 COMPLETE에서 끝이 앰버로 */
    val antennaStem: Cell = Cell(1, 5)
    val antennaTip: Cell = Cell(0, 5)
    val antennae: List<Cell> = listOf(antennaTip, antennaStem)

    /** 정수리 (꼭지가 붙는 칸) */
    val crown: Cell = Cell(2, 5)

    /** 서서히 켜지는 몸통 (코어·꼭지 제외) */
    val body: List<Cell> = allCells.filter { it != core && it !in antennae }

    /** 바이저 띠: 눈 줄 3행 × 안쪽 7칸 */
    private const val EYE_TOP = 6
    val visor: Set<Cell> = (EYE_TOP..EYE_TOP + 2).flatMap { r -> (2..8).map { c -> Cell(r, c) } }.toSet()

    // 몸통 중심. 성장 순서는 이 점 기준 타원 거리라서 어느 단계든 "작은 피코" 실루엣이 된다.
    private const val CENTER_ROW = 8.5f
    private const val CENTER_COL = 5f
    private const val RADIUS_ROW = 7f
    private const val RADIUS_COL = 5f

    private fun ellipseDistance(c: Cell): Float {
        val dr = (c.row - CENTER_ROW) / RADIUS_ROW
        val dc = (c.col - CENTER_COL) / RADIUS_COL
        return dr * dr + dc * dc
    }

    /**
     * 몸통 안쪽부터 바깥으로 켜지는 성장 순서. 좌우 대칭을 유지하도록 각 칸 바로 뒤에 거울 칸이 온다.
     */
    val growthOrder: List<Cell> = run {
        val bodySet = body.toSet()
        val sorted = body.sortedWith(compareBy<Cell> { ellipseDistance(it) }.thenBy { it.row }.thenBy { it.col })
        val added = LinkedHashSet<Cell>()
        sorted.forEach { c ->
            if (added.add(c)) c.mirror().takeIf { it in bodySet }?.let(added::add)
        }
        added.toList()
    }

    /**
     * 엔진의 pixelCount(최대 COMPLETE.pixelTarget)를 켤 몸 픽셀 수로 변환. 대칭 쌍이 갈라지지 않게 올림.
     */
    fun litBodyCount(pixelCount: Int): Int {
        val max = GrowthStage.COMPLETE.pixelTarget
        val ratio = ((pixelCount - 1).coerceAtLeast(0)).toFloat() / (max - 1)
        var count = (ratio.coerceIn(0f, 1f) * growthOrder.size).toInt()
        if (count in 1 until growthOrder.size && growthOrder[count - 1].mirror() == growthOrder[count]) count++
        return count
    }

    /**
     * 눈은 첫 단계부터 있다 (v2). 점등 단계에서도 표정이 보여야 운전 반응이 화면에 드러난다.
     * 단계가 오를수록 몸이 눈을 감싸며 바이저가 생긴다.
     */
    fun hasEyes(@Suppress("UNUSED_PARAMETER") stage: GrowthStage): Boolean = true

    // 눈 영역: 3x3, 왼쪽 cols 2..4 / 오른쪽 cols 6..8, rows 6..8 (바이저 안쪽)
    private val EYE_LEFTS = listOf(2, 6)

    /** 3x3 눈 영역 안의 로컬 좌표(행, 열). 표정끼리 픽셀 수·방향이 확실히 다르게 */
    private val EYE_SHAPES: Map<Expression, List<Pair<Int, Int>>> = mapOf(
        // 세로 알약 눈 (기본, 2px) — 감은 눈(가로 3px)·조용(점 1px)과 방향·크기로 구분
        Expression.CALM to listOf(1 to 1, 2 to 1),
        // ∩ 웃는 눈 (가운데 위로 솟은 아치, 5px)
        Expression.HAPPY to listOf(0 to 1, 1 to 0, 1 to 2, 2 to 0, 2 to 2),
        // 크게 뜬 고리 눈 (8px)
        Expression.SURPRISED to listOf(0 to 0, 0 to 1, 0 to 2, 1 to 0, 1 to 2, 2 to 0, 2 to 1, 2 to 2),
        // 감은 눈: 아래 가로줄 (3px)
        Expression.SLEEPY to listOf(2 to 0, 2 to 1, 2 to 2),
        // 조용 모드: 작은 점 (1px)
        Expression.QUIET to listOf(1 to 1),
    )

    fun eyeCells(expression: Expression): List<Cell> {
        val shape = EYE_SHAPES.getValue(expression)
        return EYE_LEFTS.flatMap { left -> shape.map { (r, c) -> Cell(EYE_TOP + r, left + c) } }
    }

    /** 깜빡임 프레임 = 감은 눈 */
    val blinkCells: List<Cell> get() = eyeCells(Expression.SLEEPY)

    /** 눈 3x3 영역 전체 */
    val eyeSockets: Set<Cell> by lazy {
        EYE_LEFTS.flatMap { left -> (0..2).flatMap { r -> (0..2).map { c -> Cell(EYE_TOP + r, left + c) } } }.toSet()
    }
}

data class Cell(val row: Int, val col: Int) {
    fun mirror(): Cell = Cell(row, PikoSprite.COLS - 1 - col)
}
