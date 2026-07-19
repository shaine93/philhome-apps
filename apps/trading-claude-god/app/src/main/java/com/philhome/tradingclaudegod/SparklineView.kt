package com.philhome.tradingclaudegod

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * Mini-graphique (sparkline) : trace une petite courbe des derniers cours → on « voit » la tendance
 * d'un coup d'œil. Vert si le mois est haussier, rouge sinon. Volontairement épuré (pas d'axes).
 */
class SparklineView(context: Context) : View(context) {

    private var pts: List<Double> = emptyList()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f * context.resources.displayMetrics.density
    }

    fun set(points: List<Double>, up: Boolean) {
        pts = points
        line.color = if (up) Ui.GREEN else Ui.RED
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (pts.size < 2) return
        val pad = 3f * resources.displayMetrics.density
        val w = width - 2 * pad
        val h = height - 2 * pad
        val min = pts.min()
        val max = pts.max()
        val range = (max - min).takeIf { it > 0 } ?: 1.0
        val path = Path()
        pts.forEachIndexed { i, v ->
            val x = pad + w * i / (pts.size - 1)
            val y = pad + h * (1 - ((v - min) / range)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, line)
    }
}
