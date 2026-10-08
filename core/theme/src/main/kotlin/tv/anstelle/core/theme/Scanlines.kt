package tv.anstelle.core.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import kotlin.math.roundToInt

/**
 * A CRT's scanlines over everything drawn before it, video included: one dark line in every
 * three, about a dp apart. The lines come from a tiny bitmap repeated by a shader, built once
 * per size, so drawing a frame allocates nothing. Works from API 25, unlike a RuntimeShader.
 */
fun Modifier.scanlines(alpha: Float): Modifier = if (alpha <= 0f) {
    this
} else {
    drawWithCache {
        val brush = ShaderBrush(ImageShader(lineTile(density.roundToInt().coerceAtLeast(1)), TileMode.Repeated, TileMode.Repeated))
        onDrawWithContent {
            drawContent()
            drawRect(brush, alpha = alpha)
        }
    }
}

/** A 1-pixel-wide tile: [unit] dark rows over two units of clear. */
private fun lineTile(unit: Int): ImageBitmap {
    val tile = ImageBitmap(1, unit * 3)
    Canvas(tile).drawRect(0f, 0f, 1f, unit.toFloat(), Paint().apply { color = Color.Black })
    return tile
}
