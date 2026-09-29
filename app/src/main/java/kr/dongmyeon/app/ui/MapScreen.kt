package kr.dongmyeon.app.ui

import android.annotation.SuppressLint
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import kotlinx.coroutines.launch
import kr.dongmyeon.app.R
import kr.dongmyeon.app.data.AchievedEntity
import kr.dongmyeon.app.data.PhotoEntity
import kr.dongmyeon.app.data.VisitRepository
import kr.dongmyeon.app.tracking.Permissions
import kr.dongmyeon.core.RegionIndex
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.File
import java.net.URI

/** API 키가 필요 없는 OpenFreeMap 배경지도(OpenStreetMap 데이터) */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

/** 인터넷이 없을 때: 배경 없이 읍면동 경계만 표시 */
private const val OFFLINE_STYLE = """{"version":8,"sources":{},"layers":[
  {"id":"bg","type":"background","paint":{"background-color":"#DDE6EE"}}]}"""

/**
 * 방문 단계별 색. 미달성→통과→머무름→직접 방문 순으로 색이 진해지고 채도가 올라간다.
 * (직접 방문만 색상 계열 자체를 초록에서 금색으로 바꿔서 "특별함"을 강조)
 */
private data class MethodStyle(val fill: String, val line: String, val opacity: Float, val lineWidth: Float)

private val PENDING_STYLE = MethodStyle("#B0BEC5", "#78909C", 0.28f, 0.6f)
private val CROSSING_STYLE = MethodStyle("#81C784", "#4CAF50", 0.50f, 1.0f)
private val POINTS_STYLE = MethodStyle("#2E7D32", "#1B5E20", 0.62f, 1.4f)
private val ON_SITE_STYLE = MethodStyle("#F9A825", "#F57F17", 0.68f, 1.8f)

private fun styleFor(method: String?) = when (method) {
    "ON_SITE" -> ON_SITE_STYLE
    "POINTS" -> POINTS_STYLE
    "CROSSING" -> CROSSING_STYLE
    else -> PENDING_STYLE
}

private const val SRC = "regions"
private const val FILL = "regions-fill"
private const val LINE = "regions-line"
private const val FLAG_SRC = "photo-flags"
private const val FLAG_LAYER = "photo-flags-layer"
private const val FLAG_ICON = "flag-icon"

// 전국 데이터(2단계)로 확장하면서 초기 화면도 전국이 한눈에 보이게 조정
private val START_CAMERA = CameraPosition.Builder()
    .target(LatLng(36.2, 127.9))
    .zoom(6.4)
    .build()

/** 지도 한 개와 그 상태를 붙잡아 두는 holder */
private class MapHolder(val view: MapView) {
    var map: MapLibreMap? = null
    var style: Style? = null
    var usedFallback = false
    var clickListenerAdded = false
}

@Composable
fun MapScreen(
    repo: VisitRepository,
    achieved: List<AchievedEntity>,
    totalRegions: Int?,
    offlineMode: Boolean,
    photoCodes: List<String>,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var selected by remember { mutableStateOf<String?>(null) }
    var styleReady by remember { mutableStateOf(0) }
    var regionIndex by remember { mutableStateOf<RegionIndex?>(null) }
    val achievedByCode = remember(achieved) { achieved.associateBy { it.code } }

    LaunchedEffect(Unit) { regionIndex = repo.regions() }

    val holder = remember {
        MapHolder(MapView(context).apply { onCreate(null) })
    }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> holder.view.onStart()
                Lifecycle.Event.ON_RESUME -> holder.view.onResume()
                Lifecycle.Event.ON_PAUSE -> holder.view.onPause()
                Lifecycle.Event.ON_STOP -> holder.view.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            holder.view.onPause()
            holder.view.onStop()
            holder.view.onDestroy()
        }
    }

    // holder(최초 1회) + offlineMode(설정을 바꿀 때마다) 에 반응해 스타일을 다시 적용한다.
    // 오프라인 모드일 때는 배경 타일을 아예 요청하지 않는다(위치 노출 방지).
    LaunchedEffect(holder, offlineMode) {
        holder.view.getMapAsync { map ->
            holder.map = map
            val onLoaded = Style.OnStyleLoaded { style ->
                holder.style = style
                addRegionLayers(context, style)
                styleReady++
            }
            if (!holder.clickListenerAdded) {
                holder.clickListenerAdded = true
                map.cameraPosition = START_CAMERA
                map.uiSettings.isRotateGesturesEnabled = false
                map.addOnMapClickListener { point ->
                    val features = map.queryRenderedFeatures(map.projection.toScreenLocation(point), FILL)
                    selected = features.firstOrNull()?.getStringProperty("code")
                    true
                }
                holder.view.addOnDidFailLoadingMapListener {
                    if (!holder.usedFallback) {
                        holder.usedFallback = true
                        map.setStyle(Style.Builder().fromJson(OFFLINE_STYLE), onLoaded)
                    }
                }
            }
            val builder = if (offlineMode) Style.Builder().fromJson(OFFLINE_STYLE) else Style.Builder().fromUri(STYLE_URL)
            map.setStyle(builder, onLoaded)
        }
    }

    // 달성 목록이 바뀌거나 스타일이 새로 로드되면 색칠 갱신.
    // 방문 방식(머무름/통과/직접방문/미달성)별로 색·불투명도·테두리를 모두 다르게 준다.
    LaunchedEffect(achieved, styleReady) {
        val style = holder.style ?: return@LaunchedEffect
        val fillPairs = achieved.map { it.code to "\"${styleFor(it.method).fill}\"" }
        val opacityPairs = achieved.map { it.code to "${styleFor(it.method).opacity}" }
        val linePairs = achieved.map { it.code to "\"${styleFor(it.method).line}\"" }
        val lineWidthPairs = achieved.map { it.code to "${styleFor(it.method).lineWidth}" }
        style.getLayerAs<FillLayer>(FILL)?.setProperties(
            PropertyFactory.fillColor(groupedMatchExpr(fillPairs, "\"${PENDING_STYLE.fill}\"")),
            PropertyFactory.fillOpacity(groupedMatchExpr(opacityPairs, "${PENDING_STYLE.opacity}")),
        )
        style.getLayerAs<LineLayer>(LINE)?.setProperties(
            PropertyFactory.lineColor(groupedMatchExpr(linePairs, "\"${PENDING_STYLE.line}\"")),
            PropertyFactory.lineWidth(groupedMatchExpr(lineWidthPairs, "${PENDING_STYLE.lineWidth}")),
        )
    }

    // 사진이 있는 지역에 깃발 표시
    LaunchedEffect(photoCodes, regionIndex, styleReady) {
        val style = holder.style ?: return@LaunchedEffect
        val idx = regionIndex ?: return@LaunchedEffect
        val src = style.getSourceAs<GeoJsonSource>(FLAG_SRC) ?: return@LaunchedEffect
        val json = buildString {
            append("""{"type":"FeatureCollection","features":[""")
            var first = true
            for (code in photoCodes) {
                val r = idx.byCode[code] ?: continue
                if (!first) append(",")
                first = false
                append(
                    """{"type":"Feature","geometry":{"type":"Point","coordinates":[${r.centroidLng},${r.centroidLat}]},"properties":{}}"""
                )
            }
            append("]}")
        }
        src.setGeoJson(json)
    }

    // 현재 위치 표시(권한이 있을 때)
    LaunchedEffect(styleReady) {
        val map = holder.map ?: return@LaunchedEffect
        val style = holder.style ?: return@LaunchedEffect
        enableLocation(context, map, style)
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { holder.view }, modifier = Modifier.fillMaxSize())

        Card(
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text("달성 ${achieved.size} / ${totalRegions ?: "…"}", style = MaterialTheme.typography.titleMedium)
                Text("지역을 눌러 상세정보·직접 방문 기록", style = MaterialTheme.typography.bodySmall)
                if (offlineMode) {
                    Text("오프라인 모드: 배경지도 요청 안 함", style = MaterialTheme.typography.bodySmall)
                } else if (holder.usedFallback) {
                    Text("인터넷 연결 안 됨: 배경지도 없이 표시", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        selected?.let { code ->
            RegionDetailCard(
                repo = repo,
                code = code,
                achieved = achievedByCode[code],
                fallbackName = holder.map?.let { findName(it, code) },
                onDismiss = { selected = null },
            )
        }

        FloatingActionButton(
            onClick = { moveToMyLocation(holder) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Filled.MyLocation, contentDescription = "내 위치") }
    }
}

@Composable
private fun RegionDetailCard(
    repo: VisitRepository,
    code: String,
    achieved: AchievedEntity?,
    fallbackName: String?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var viewingPhoto by remember { mutableStateOf<PhotoEntity?>(null) }
    val photos by remember(code) { repo.photosOf(code) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val name = achieved?.name ?: fallbackName ?: code

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { repo.addPhotoFromUri(code, uri) }
    }

    Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "닫기") }
            }
            Text(
                if (achieved != null) "달성 · ${formatDateTime(achieved.firstVisitedAt)} · ${methodLabel(achieved.method)}" else "미달성",
                style = MaterialTheme.typography.bodySmall,
            )
            if (achieved?.method != "ON_SITE") {
                TextButton(onClick = { scope.launch { repo.markOnSite(code) } }) {
                    Text(if (achieved == null) "여기 직접 방문으로 기록" else "직접 방문으로 확인")
                }
            }

            Text("추억 사진", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Icon(Icons.Filled.Photo, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" 갤러리")
                }
            }
            if (photos.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    items(photos, key = { it.id }) { p ->
                        AsyncImage(
                            model = p.filePath,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { viewingPhoto = p },
                        )
                    }
                }
            }
        }
    }

    viewingPhoto?.let { photo ->
        PhotoViewerDialog(
            photo = photo,
            onDismiss = { viewingPhoto = null },
            onDelete = {
                scope.launch { repo.deletePhoto(photo) }
                viewingPhoto = null
            },
        )
    }
}

@Composable
private fun PhotoViewerDialog(photo: PhotoEntity, onDismiss: () -> Unit, onDelete: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Image(
                painter = rememberAsyncImagePainter(photo.filePath),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(formatDateTime(photo.takenAt), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" 삭제")
                }
                TextButton(onClick = onDismiss) { Text("닫기") }
            }
        }
    }
}

private fun addRegionLayers(context: Context, style: Style) {
    if (style.getSource(SRC) == null) {
        style.addSource(GeoJsonSource(SRC, URI("asset://${VisitRepository.REGIONS_ASSET}")))
    }
    if (style.getLayer(FILL) == null) {
        style.addLayer(
            FillLayer(FILL, SRC).withProperties(
                PropertyFactory.fillColor(PENDING_STYLE.fill),
                PropertyFactory.fillOpacity(PENDING_STYLE.opacity),
            )
        )
    }
    if (style.getLayer(LINE) == null) {
        style.addLayer(
            LineLayer(LINE, SRC).withProperties(
                PropertyFactory.lineColor(PENDING_STYLE.line),
                PropertyFactory.lineWidth(PENDING_STYLE.lineWidth),
                PropertyFactory.lineOpacity(0.8f),
            )
        )
    }
    if (style.getSource(FLAG_SRC) == null) {
        style.addSource(GeoJsonSource(FLAG_SRC, """{"type":"FeatureCollection","features":[]}"""))
    }
    if (style.getImage(FLAG_ICON) == null) {
        ContextCompat.getDrawable(context, R.drawable.ic_flag)?.let { d ->
            style.addImage(FLAG_ICON, d.toBitmap(d.intrinsicWidth, d.intrinsicHeight))
        }
    }
    if (style.getLayer(FLAG_LAYER) == null) {
        style.addLayer(
            SymbolLayer(FLAG_LAYER, FLAG_SRC).withProperties(
                PropertyFactory.iconImage(FLAG_ICON),
                PropertyFactory.iconSize(0.7f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            )
        )
    }
}

/**
 * (code → 리터럴값) 목록을 값별로 묶어서 MapLibre match 표현식을 만든다.
 * 리터럴은 JSON 그대로("\"#000\"" 또는 "0.5" 같은 문자열) 넣는다.
 */
private fun groupedMatchExpr(pairs: List<Pair<String, String>>, defaultLiteral: String): Expression {
    if (pairs.isEmpty()) return Expression.Converter.convert(defaultLiteral)
    val byValue = pairs.groupBy({ it.second }, { it.first })
    val clauses = byValue.entries.joinToString(",") { (value, codes) ->
        val labels = codes.joinToString(",") { "\"$it\"" }
        "[$labels],$value"
    }
    return Expression.Converter.convert("""["match",["get","code"],$clauses,$defaultLiteral]""")
}

private fun findName(map: MapLibreMap, code: String): String? {
    val src = map.style?.getSourceAs<GeoJsonSource>(SRC) ?: return null
    return src.querySourceFeatures(Expression.eq(Expression.get("code"), code))
        .firstOrNull()?.getStringProperty("name")
}

@SuppressLint("MissingPermission")
private fun enableLocation(context: Context, map: MapLibreMap, style: Style) {
    if (!Permissions.hasFineLocation(context)) return
    val lc = map.locationComponent
    if (!lc.isLocationComponentActivated) {
        lc.activateLocationComponent(LocationComponentActivationOptions.builder(context, style).build())
    }
    lc.isLocationComponentEnabled = true
    lc.cameraMode = CameraMode.NONE
    lc.renderMode = RenderMode.COMPASS
}

@SuppressLint("MissingPermission")
private fun moveToMyLocation(holder: MapHolder) {
    val map = holder.map ?: return
    val style = holder.style ?: return
    enableLocation(holder.view.context, map, style)
    val lc = map.locationComponent
    val loc = if (lc.isLocationComponentActivated) lc.lastKnownLocation else null
    if (loc != null) {
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 13.0))
    }
}
