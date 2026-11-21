package com.maxrave.simpmusic.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

@Suppress("CheckReturnValue")
val SimpIcons.GridView: ImageVector
  get() {
    if (_GridView != null) {
      return _GridView!!
    }
    _GridView =
      ImageVector.Builder(
          name = "GridView",
          defaultWidth = 24.dp,
          defaultHeight = 24.dp,
          viewportWidth = 24f,
          viewportHeight = 24f,
        )
        .apply {
          path(
            fill = SolidColor(Color.Black),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Bevel,
            strokeLineMiter = 1f,
            pathFillType = PathFillType.Companion.NonZero,
          ) {
            // Top-left
            moveTo(5f, 3f)
            horizontalLineTo(9f)
            quadTo(11f, 3f, 11f, 5f)
            verticalLineTo(9f)
            quadTo(11f, 11f, 9f, 11f)
            horizontalLineTo(5f)
            quadTo(3f, 11f, 3f, 9f)
            verticalLineTo(5f)
            quadTo(3f, 3f, 5f, 3f)
            close()

            // Top-right
            moveTo(15f, 3f)
            horizontalLineTo(19f)
            quadTo(21f, 3f, 21f, 5f)
            verticalLineTo(9f)
            quadTo(21f, 11f, 19f, 11f)
            horizontalLineTo(15f)
            quadTo(13f, 11f, 13f, 9f)
            verticalLineTo(5f)
            quadTo(13f, 3f, 15f, 3f)
            close()

            // Bottom-left
            moveTo(5f, 13f)
            horizontalLineTo(9f)
            quadTo(11f, 13f, 11f, 15f)
            verticalLineTo(19f)
            quadTo(11f, 21f, 9f, 21f)
            horizontalLineTo(5f)
            quadTo(3f, 21f, 3f, 19f)
            verticalLineTo(15f)
            quadTo(3f, 13f, 5f, 13f)
            close()

            // Bottom-right
            moveTo(15f, 13f)
            horizontalLineTo(19f)
            quadTo(21f, 13f, 21f, 15f)
            verticalLineTo(19f)
            quadTo(21f, 21f, 19f, 21f)
            horizontalLineTo(15f)
            quadTo(13f, 21f, 13f, 19f)
            verticalLineTo(15f)
            quadTo(13f, 13f, 15f, 13f)
            close()
          }
        }
        .buildSimpIcon()
    return _GridView!!
  }

private var _GridView: ImageVector? = null
