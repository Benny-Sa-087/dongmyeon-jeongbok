package kr.dongmyeon.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 읍·면·동 하나 */
class Region(
    /** 행정안전부 10자리 행정기관코드 */
    val code: String,
    /** 전체 이름, 예: "인천광역시 남동구 구월1동" */
    val name: String,
    val sido: String,
    val sgg: String,
    val polygons: List<Polygon>,
) {
    val bbox: BBox = BBox.of(polygons.map { it.outer })

    /** 마지막 토큰(읍면동 이름만) */
    val shortName: String get() = name.substringAfterLast(' ')

    /**
     * 가장 큰 폴리곤 외곽 링 좌표의 단순 평균. 정확한 도형 무게중심은 아니지만
     * "근처 미달성 지역 추천"처럼 정밀도가 중요하지 않은 용도로는 충분하다.
     */
    val centroidLat: Double
    val centroidLng: Double

    init {
        val outer = polygons.maxByOrNull { it.outer.size }?.outer
        var sx = 0.0; var sy = 0.0
        val n = outer?.size ?: 0
        if (outer != null) for (i in 0 until n) { sx += outer.x(i); sy += outer.y(i) }
        centroidLng = if (n > 0) sx / n else bbox.let { (it.minX + it.maxX) / 2 }
        centroidLat = if (n > 0) sy / n else bbox.let { (it.minY + it.maxY) / 2 }
    }

    fun contains(lat: Double, lng: Double): Boolean =
        bbox.contains(lng, lat) && polygons.any { Geo.polygonContains(it, lng, lat) }

    /** 선분이 이 영역 경계와 처음 만나는 비율 t, 없으면 null */
    fun firstBoundaryHit(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double? {
        var best: Double? = null
        for (p in polygons) {
            for (r in listOf(p.outer) + p.holes) {
                val t = Geo.firstRingIntersection(r, aLng, aLat, bLng, bLat)
                if (t != null && (best == null || t < best)) best = t
            }
        }
        return best
    }
}

/** 빠른 후보 검색을 위한 격자 색인 */
class RegionIndex(val regions: List<Region>, private val cellDeg: Double = 0.05) {
    private val cells = HashMap<Long, MutableList<Region>>()
    val byCode: Map<String, Region> = regions.associateBy { it.code }

    init {
        for (r in regions) forCells(r.bbox) { key -> cells.getOrPut(key) { ArrayList() }.add(r) }
    }

    private fun cellOf(v: Double) = Math.floor(v / cellDeg).toLong()
    private fun key(cx: Long, cy: Long) = (cx shl 32) xor (cy and 0xffffffffL)

    private inline fun forCells(b: BBox, f: (Long) -> Unit) {
        for (cx in cellOf(b.minX)..cellOf(b.maxX)) for (cy in cellOf(b.minY)..cellOf(b.maxY)) f(key(cx, cy))
    }

    fun find(lat: Double, lng: Double): Region? =
        cells[key(cellOf(lng), cellOf(lat))]?.firstOrNull { it.contains(lat, lng) }

    fun candidatesForSegment(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Set<Region> {
        val b = BBox.ofSegment(aLng, aLat, bLng, bLat)
        val out = LinkedHashSet<Region>()
        forCells(b) { k -> cells[k]?.forEach { if (it.bbox.intersects(b)) out.add(it) } }
        return out
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** 앱에 내장된 GeoJSON(FeatureCollection) 파싱. properties: code, name, sido, sgg */
        fun fromGeoJson(text: String): RegionIndex {
            val root = json.parseToJsonElement(text).jsonObject
            val regions = root["features"]!!.jsonArray.map { f ->
                val props = f.jsonObject["properties"]!!.jsonObject
                val geom = f.jsonObject["geometry"]!!.jsonObject
                val type = geom["type"]!!.jsonPrimitive.content
                val coords = geom["coordinates"]!!.jsonArray
                val polys = when (type) {
                    "Polygon" -> listOf(parsePolygon(coords))
                    "MultiPolygon" -> coords.map { parsePolygon(it.jsonArray) }
                    else -> error("지원하지 않는 geometry: $type")
                }
                Region(
                    code = props.str("code"),
                    name = props.str("name"),
                    sido = props.str("sido"),
                    sgg = props.str("sgg"),
                    polygons = polys,
                )
            }
            return RegionIndex(regions)
        }

        private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content ?: ""

        private fun parsePolygon(rings: JsonArray): Polygon {
            val parsed = rings.map { ring ->
                val pts = ring.jsonArray
                val arr = DoubleArray(pts.size * 2)
                pts.forEachIndexed { i, p ->
                    val xy = p.jsonArray
                    arr[2 * i] = xy[0].jsonPrimitive.double
                    arr[2 * i + 1] = xy[1].jsonPrimitive.double
                }
                Ring(arr)
            }
            return Polygon(parsed.first(), parsed.drop(1))
        }
    }
}
