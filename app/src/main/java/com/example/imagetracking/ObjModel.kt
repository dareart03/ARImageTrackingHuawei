package com.example.imagetracking

import android.content.Context
import java.nio.FloatBuffer

/**
 * 3D-модель из OBJ: вершины вида [x, y, z, nx, ny, nz, r, g, b].
 * Модель нормализуется: высота = 1, основание лежит в плоскости y = 0.
 */
class ObjModel(data: FloatArray) {
    val vertexCount: Int = data.size / FLOATS
    val buffer: FloatBuffer = Gl.floatBuffer(data)

    companion object {
        const val FLOATS = 9
    }
}

/** Минимальный загрузчик OBJ (+ MTL, только цвет Kd): v, vn, f (с триангуляцией), usemtl. */
object ObjLoader {

    fun load(context: Context, objName: String): ObjModel {
        val pos = ArrayList<FloatArray>()
        val nrm = ArrayList<FloatArray>()
        val colors = HashMap<String, FloatArray>()
        val out = ArrayList<Float>()
        var color = floatArrayOf(0.8f, 0.8f, 0.8f)

        context.assets.open(objName).bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val t = line.split(Regex("\\s+"))
                when (t[0]) {
                    "mtllib" -> loadMtl(context, t[1], colors)
                    "usemtl" -> color = colors[t[1]] ?: color
                    "v" -> pos.add(floatArrayOf(t[1].toFloat(), t[2].toFloat(), t[3].toFloat()))
                    "vn" -> nrm.add(floatArrayOf(t[1].toFloat(), t[2].toFloat(), t[3].toFloat()))
                    "f" -> {
                        val corners = (1 until t.size).map { parseCorner(t[it], pos.size, nrm.size) }
                        for (i in 1 until corners.size - 1) {
                            emit(out, pos, nrm, corners[0], corners[i], corners[i + 1], color)
                        }
                    }
                }
            }
        }

        // нормализация: основание на y = 0, высота = 1
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in out.indices step ObjModel.FLOATS) {
            val y = out[i + 1]
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        val h = if (maxY > minY) maxY - minY else 1f
        for (i in out.indices step ObjModel.FLOATS) {
            out[i] = out[i] / h
            out[i + 1] = (out[i + 1] - minY) / h
            out[i + 2] = out[i + 2] / h
        }
        return ObjModel(out.toFloatArray())
    }

    private fun loadMtl(context: Context, name: String, colors: HashMap<String, FloatArray>) {
        var cur: String? = null
        try {
            context.assets.open(name).bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val t = raw.trim().split(Regex("\\s+"))
                    if (t[0] == "newmtl" && t.size > 1) cur = t[1]
                    if (t[0] == "Kd" && t.size > 3 && cur != null) {
                        colors[cur!!] = floatArrayOf(t[1].toFloat(), t[2].toFloat(), t[3].toFloat())
                    }
                }
            }
        } catch (e: java.io.IOException) {
            // нет MTL — останется серый цвет
        }
    }

    /** "v", "v/vt", "v//vn" или "v/vt/vn" → (индекс вершины, индекс нормали или -1), с нуля. */
    private fun parseCorner(s: String, nPos: Int, nNrm: Int): IntArray {
        val p = s.split("/")
        val v = resolve(p[0].toInt(), nPos)
        val n = if (p.size > 2 && p[2].isNotEmpty()) resolve(p[2].toInt(), nNrm) else -1
        return intArrayOf(v, n)
    }

    private fun resolve(idx: Int, size: Int) = if (idx > 0) idx - 1 else size + idx

    private fun emit(
        out: ArrayList<Float>, pos: List<FloatArray>, nrm: List<FloatArray>,
        a: IntArray, b: IntArray, c: IntArray, color: FloatArray
    ) {
        val fn = if (a[1] < 0 || b[1] < 0 || c[1] < 0) faceNormal(pos[a[0]], pos[b[0]], pos[c[0]]) else null
        for (k in arrayOf(a, b, c)) {
            val p = pos[k[0]]
            val n = if (k[1] >= 0) nrm[k[1]] else fn!!
            out.add(p[0]); out.add(p[1]); out.add(p[2])
            out.add(n[0]); out.add(n[1]); out.add(n[2])
            out.add(color[0]); out.add(color[1]); out.add(color[2])
        }
    }

    private fun faceNormal(a: FloatArray, b: FloatArray, c: FloatArray): FloatArray {
        val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
        val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
        val nx = uy * vz - uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy - uy * vx
        val l = Math.sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat().let { if (it < 1e-9f) 1f else it }
        return floatArrayOf(nx / l, ny / l, nz / l)
    }
}
