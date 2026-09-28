package kr.dongmyeon.core

/**
 * 경위도 평면 좌표로 다루는 단순 기하 연산.
 * 읍면동 크기(수 km) 범위에서는 경위도를 평면으로 봐도 판정에 문제가 없다.
 */

/** 링 좌표: [lng0, lat0, lng1, lat1, ...] 형태로 저장해 메모리를 아낀다. */
class Ring(val coords: DoubleArray) {
    val size: Int get() = coords.size / 2
    fun x(i: Int) = coords[2 * i]
    fun y(i: Int) = coords[2 * i + 1]
}

/** 외곽 링 1개 + 구멍 링 0개 이상 */
class Polygon(val outer: Ring, val holes: List<Ring> = emptyList())

class BBox(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    fun contains(x: Double, y: Double) = x in minX..maxX && y in minY..maxY
    fun intersects(o: BBox) = minX <= o.maxX && o.minX <= maxX && minY <= o.maxY && o.minY <= maxY

    companion object {
        fun of(rings: List<Ring>): BBox {
            var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
            for (r in rings) for (i in 0 until r.size) {
                val x = r.x(i); val y = r.y(i)
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
            return BBox(minX, minY, maxX, maxY)
        }

        fun ofSegment(ax: Double, ay: Double, bx: Double, by: Double) =
            BBox(minOf(ax, bx), minOf(ay, by), maxOf(ax, bx), maxOf(ay, by))
    }
}

object Geo {
    /** 짝-홀(ray casting) 판정. 링이 닫혀 있든 아니든 동작한다. */
    fun ringContains(r: Ring, x: Double, y: Double): Boolean {
        var inside = false
        val n = r.size
        var j = n - 1
        for (i in 0 until n) {
            val xi = r.x(i); val yi = r.y(i)
            val xj = r.x(j); val yj = r.y(j)
            if ((yi > y) != (yj > y)) {
                val xCross = (xj - xi) * (y - yi) / (yj - yi) + xi
                if (x < xCross) inside = !inside
            }
            j = i
        }
        return inside
    }

    fun polygonContains(p: Polygon, x: Double, y: Double): Boolean =
        ringContains(p.outer, x, y) && p.holes.none { ringContains(it, x, y) }

    /**
     * 선분 AB 가 링의 변과 처음 만나는 위치를 A 기준 비율 t(0..1)로 돌려준다. 만나지 않으면 null.
     */
    fun firstRingIntersection(r: Ring, ax: Double, ay: Double, bx: Double, by: Double): Double? {
        var best: Double? = null
        val n = r.size
        var j = n - 1
        for (i in 0 until n) {
            val t = segmentIntersectionT(ax, ay, bx, by, r.x(j), r.y(j), r.x(i), r.y(i))
            if (t != null && (best == null || t < best)) best = t
            j = i
        }
        return best
    }

    /** 선분 P(a→b)와 Q(c→d)의 교점이 P 위에서 차지하는 비율 t. 평행/미교차면 null. */
    fun segmentIntersectionT(
        ax: Double, ay: Double, bx: Double, by: Double,
        cx: Double, cy: Double, dx: Double, dy: Double,
    ): Double? {
        val rx = bx - ax; val ry = by - ay
        val sx = dx - cx; val sy = dy - cy
        val denom = rx * sy - ry * sx
        if (denom == 0.0) return null
        val qpx = cx - ax; val qpy = cy - ay
        val t = (qpx * sy - qpy * sx) / denom
        val u = (qpx * ry - qpy * rx) / denom
        return if (t in 0.0..1.0 && u in 0.0..1.0) t else null
    }
}
