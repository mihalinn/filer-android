package local.filemanager

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

private sealed interface StlLoad {
    data object Busy : StlLoad
    data class Ok(val mesh: Mesh) : StlLoad
    data class Fail(val message: String) : StlLoad
}

private fun loadStl(fs: Fs, item: FileItem): StlLoad = try {
    StlLoad.Ok(fs.open(item.path).use { MeshFormats.parse(FileOps.extensionOf(item.name), it, item.size) })
} catch (e: OpException) {
    StlLoad.Fail(e.message ?: "読み込めません")
} catch (e: Exception) {
    StlLoad.Fail(e.message ?: "読み込めません")
} catch (e: OutOfMemoryError) {
    StlLoad.Fail("大きすぎて読み込めません")
}

/** 3D モデル(STL・OBJ・PLY・OFF・3MF)の表示。1 本指のドラッグで回す、ピンチで拡大・縮小、2 本指で移動、ダブルタップで元の向きに戻す。 */
@Composable
internal fun StlPane(fs: Fs, item: FileItem, bars: Boolean, onTap: () -> Unit) {
    val load = produceState<StlLoad>(StlLoad.Busy, item.path) {
        value = withContext(Dispatchers.IO) { loadStl(fs, item) }
    }.value
    when (load) {
        StlLoad.Busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Pv.text) }
        is StlLoad.Fail -> Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }) {
            Text(load.message, color = Pv.text, modifier = Modifier.align(Alignment.Center).padding(32.dp))
        }
        is StlLoad.Ok -> StlView(load.mesh, bars, onTap)
    }
}

@Composable
private fun StlView(mesh: Mesh, bars: Boolean, onTap: () -> Unit) {
    val renderer = remember(mesh) { StlRenderer(mesh) }
    // 背景の色(ダーク / ライト)。変わったら、描き直す
    val darkBg = Pv.dark
    renderer.dark = darkBg
    var glView by remember(mesh) { mutableStateOf<GLSurfaceView?>(null) }
    LaunchedEffect(darkBg) { glView?.requestRender() }
    Box(
        Modifier.fillMaxSize().background(Pv.modelBg)
            .pointerInput(renderer) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val fingers = event.changes.count { it.pressed }
                        if (fingers == 1) {
                            val d = event.changes.first { it.pressed }.positionChange()
                            renderer.rotate(d.x, d.y)
                        } else if (fingers >= 2) {
                            renderer.zoomBy(event.calculateZoom())
                            val p = event.calculatePan()
                            renderer.pan(p.x, p.y, size.height.toFloat())
                        }
                        glView?.requestRender()
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(renderer) {
                detectTapGestures(onTap = { onTap() }, onDoubleTap = { renderer.reset(); glView?.requestRender() })
            },
    ) {
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(3)
                    setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                }
            },
            update = { glView = it },
            modifier = Modifier.fillMaxSize(),
        )
        if (bars) {
            fun mm(v: Float) = if (v >= 100f) "%.0f".format(v) else "%.1f".format(v)
            Text(
                (if (mesh.points) "点 ${mesh.vertexCount} 個" else "三角形 ${mesh.triangles} 枚") +
                    "・幅 ${mm(mesh.sizeX)} × 奥行 ${mm(mesh.sizeY)} × 高さ ${mm(mesh.sizeZ)}",
                color = Color(0xFFCCCCCC), fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

private const val VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aColor;
uniform mat4 uMvp;
uniform mat4 uMv;
uniform float uPointSize;
out vec3 vPos;
out vec3 vColor;
void main() {
    vPos = (uMv * vec4(aPos, 1.0)).xyz;
    vColor = aColor;
    gl_PointSize = uPointSize;
    gl_Position = uMvp * vec4(aPos, 1.0);
}
"""

// 面の向きは、画面上の位置の変化(dFdx / dFdy)から求める(STL の法線の向きが不揃いでも、表裏とも明るく見える)
private const val FRAGMENT_SHADER = """#version 300 es
precision highp float;
in vec3 vPos;
in vec3 vColor;
uniform float uPoints;
out vec4 outColor;
void main() {
    if (uPoints > 0.5) {
        outColor = vec4(vColor, 1.0);
        return;
    }
    vec3 n = normalize(cross(dFdx(vPos), dFdy(vPos)));
    float key = abs(dot(n, normalize(vec3(0.35, 0.55, 1.0))));
    float fill = abs(dot(n, normalize(vec3(-0.7, -0.25, 0.4)))) * 0.3;
    outColor = vec4(vColor * (0.22 + 0.68 * key + fill), 1.0);
}
"""

private class StlRenderer(private val mesh: Mesh) : GLSurfaceView.Renderer {
    @Volatile private var yaw = 35f
    @Volatile var dark = true
    @Volatile private var pitch = 25f
    @Volatile private var zoom = 1f
    @Volatile private var panX = 0f
    @Volatile private var panY = 0f

    private var program = 0
    private var vbo = 0
    private var cbo = 0
    private var uMvp = 0
    private var uMv = 0
    private var uPoints = 0
    private var uPointSize = 0
    private var aspect = 1f
    private val cx = (mesh.min[0] + mesh.max[0]) / 2
    private val cy = (mesh.min[1] + mesh.max[1]) / 2
    private val cz = (mesh.min[2] + mesh.max[2]) / 2
    private val radius = (sqrt(mesh.sizeX * mesh.sizeX + mesh.sizeY * mesh.sizeY + mesh.sizeZ * mesh.sizeZ) / 2f).coerceAtLeast(1e-6f)

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val mv = FloatArray(16)
    private val mvp = FloatArray(16)

    fun rotate(dx: Float, dy: Float) {
        yaw += dx * 0.45f
        pitch = (pitch + dy * 0.45f).coerceIn(-89f, 89f)
    }

    fun zoomBy(z: Float) {
        zoom = (zoom * z).coerceIn(0.2f, 12f)
    }

    /** 画面上の移動量(ピクセル)を、モデルの移動に直す。 */
    fun pan(dx: Float, dy: Float, viewHeightPx: Float) {
        val k = 2.2f / viewHeightPx.coerceAtLeast(1f)
        panX += dx * k
        panY -= dy * k
    }

    fun reset() {
        yaw = 35f
        pitch = 25f
        zoom = 1f
        panX = 0f
        panY = 0f
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.13f, 0.15f, 0.17f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        program = link(VERTEX_SHADER, FRAGMENT_SHADER)
        uMvp = GLES30.glGetUniformLocation(program, "uMvp")
        uMv = GLES30.glGetUniformLocation(program, "uMv")
        uPoints = GLES30.glGetUniformLocation(program, "uPoints")
        uPointSize = GLES30.glGetUniformLocation(program, "uPointSize")
        val ids = IntArray(2)
        GLES30.glGenBuffers(2, ids, 0)
        vbo = ids[0]
        cbo = ids[1]
        mesh.vertices.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, mesh.vertexCount * 3 * 4, mesh.vertices, GLES30.GL_STATIC_DRAW)
        mesh.colors?.let { c ->
            c.position(0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, mesh.vertexCount * 3 * 4, c, GLES30.GL_STATIC_DRAW)
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        aspect = width.toFloat() / height.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (dark) GLES30.glClearColor(0.13f, 0.15f, 0.17f, 1f) else GLES30.glClearColor(0.925f, 0.937f, 0.945f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (program == 0) return
        // モデル: 中心を原点に → 大きさをそろえる → 縦(Z が上)に起こして回す
        val s = zoom / radius
        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, pitch - 90f, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, yaw, 0f, 0f, 1f)
        Matrix.scaleM(model, 0, s, s, s)
        Matrix.translateM(model, 0, -cx, -cy, -cz)
        Matrix.setIdentityM(view, 0)
        Matrix.translateM(view, 0, panX, panY, -3.4f)
        Matrix.perspectiveM(proj, 0, 40f, aspect, 0.1f, 60f)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, mv, 0)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(uMv, 1, false, mv, 0)
        GLES30.glUniform1f(uPoints, if (mesh.points) 1f else 0f)
        GLES30.glUniform1f(uPointSize, 4f)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        if (mesh.colors != null) {
            // 頂点ごとの色
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cbo)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 12, 0)
        } else {
            // 色の無いモデルは、全体を 1 色で(配色に合わせる)
            GLES30.glDisableVertexAttribArray(1)
            if (dark) GLES30.glVertexAttrib3f(1, 0.56f, 0.72f, 0.88f) else GLES30.glVertexAttrib3f(1, 0.30f, 0.52f, 0.78f)
        }
        GLES30.glDrawArrays(if (mesh.points) GLES30.GL_POINTS else GLES30.GL_TRIANGLES, 0, mesh.vertexCount)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val sh = GLES30.glCreateShader(type)
            GLES30.glShaderSource(sh, src)
            GLES30.glCompileShader(sh)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, ok, 0)
            return if (ok[0] == 0) 0 else sh
        }
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        if (v == 0 || f == 0) return 0
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        return if (ok[0] == 0) 0 else p
    }
}
