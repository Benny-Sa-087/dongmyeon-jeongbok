package kr.dongmyeon.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import kr.dongmyeon.app.data.AchievedEntity
import kr.dongmyeon.app.data.VisitRepository
import kr.dongmyeon.app.tracking.Permissions
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
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import java.net.URI

/** API 키가 필요 없는 OpenFreeMap 배경지도(OpenStreetMap 데이터) */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

/** 인터넷이 없을 때: 배경 없이 읍면동 경계만 표시 */
private const val OFFLINE_STYLE = """{"version":8,"sources":{},"layers":[
  {"id":"bg","type":"background","paint":{"background-color":"#DDE6EE"}}]}"""

private const val ACHIEVED_COLOR = "#E4572E"
private const val PENDING_COLOR = "#9E9E9E"
private const val SRC = "regions"
private const val FILL = "regions-fill"
private const val LINE = "regions-line"

private val START_CAMERA = CameraPosition.Builder()
    .target(LatLng(37.456, 126.63))
    .zoom(9.3)
    .build()

/** 지도 한 개와 그 상태를 붙잡아 두는 holder */
private class MapHolder(val view: MapView) {
    var map: MapLibreMap? = null
    var style: Style? = null
    var usedFallback = false
    var clickListenerAdded = false
}

@Composable
fun MapScreen(repo: VisitRepository, achieved: List<AchievedEntity>, totalRegions: Int?, offlineMode: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var selected by remember { mutableStateOf<String?>(null) }
    var styleReady by remember { mutableStateOf(0) }
    val achievedByCode = remember(achieved) { achieved.associateBy { it.code } }
    val codes = remember(achieved) { achieved.map { it.code } }

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
                addRegionLayers(style)
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

    // 달성 목록이 바뀌거나 스타일이 새로 로드되면 색칠 갱신
    LaunchedEffect(codes, styleReady) {
        holder.style?.getLayerAs<FillLayer>(FILL)?.setProperties(PropertyFactory.fillColor(fillExpression(codes)))
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
                if (offlineMode) {
                    Text("오프라인 모드: 배경지도 요청 안 함", style = MaterialTheme.typography.bodySmall)
                } else if (holder.usedFallback) {
                    Text("인터넷 연결 안 됨: 배경지도 없이 표시", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        selected?.let { code ->
            val a = achievedByCode[code]
            val name = a?.name ?: holder.map?.let { findName(it, code) } ?: code
            Card(
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                onClick = { selected = null },
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (a != null) "달성 · ${formatDateTime(a.firstVisitedAt)} · ${methodLabel(a.method)}" else "미달성",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (a == null) {
                        TextButton(onClick = { scope.launch { repo.markOnSite(code) } }) {
                            Text("여기 직접 방문으로 기록")
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { moveToMyLocation(holder) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Filled.MyLocation, contentDescription = "내 위치") }
    }
}

private fun addRegionLayers(style: Style) {
    if (style.getSource(SRC) == null) {
        style.addSource(GeoJsonSource(SRC, URI("asset://${VisitRepository.REGIONS_ASSET}")))
    }
    if (style.getLayer(FILL) == null) {
        style.addLayer(
            FillLayer(FILL, SRC).withProperties(
                PropertyFactory.fillColor(PENDING_COLOR),
                PropertyFactory.fillOpacity(0.5f),
            )
        )
    }
    if (style.getLayer(LINE) == null) {
        style.addLayer(
            LineLayer(LINE, SRC).withProperties(
                PropertyFactory.lineColor("#555555"),
                PropertyFactory.lineWidth(0.7f),
                PropertyFactory.lineOpacity(0.7f),
            )
        )
    }
}

/** 달성 코드 → 주황, 나머지 → 회색 */
private fun fillExpression(codes: List<String>): Expression {
    if (codes.isEmpty()) return Expression.color(Color.parseColor(PENDING_COLOR))
    val labels = codes.joinToString(",") { "\"$it\"" }
    return Expression.Converter.convert(
        """["match",["get","code"],[$labels],"$ACHIEVED_COLOR","$PENDING_COLOR"]"""
    )
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
