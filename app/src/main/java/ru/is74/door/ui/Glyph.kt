package ru.is74.door.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun Glyph(name: String, color: Color = Muted, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) {
        val s = size.width / 24f
        fun line(x:Float,y:Float,x2:Float,y2:Float) = drawLine(color,Offset(x*s,y*s),Offset(x2*s,y2*s),1.8f*s,StrokeCap.Round)
        when(name) {
            "door" -> { drawRect(color,Offset(6*s,3*s),Size(12*s,18*s),style=Stroke(1.8f*s)); drawCircle(color,s,Offset(14*s,12*s)); line(3f,21f,21f,21f) }
            "play" -> { val p=Path().apply { moveTo(8*s,5*s);lineTo(19*s,12*s);lineTo(8*s,19*s);close() };drawPath(p,color) }
            "pause" -> { drawRect(color,Offset(7*s,5*s),Size(3*s,14*s));drawRect(color,Offset(14*s,5*s),Size(3*s,14*s)) }
            "clock" -> { drawCircle(color,9*s,center,style=Stroke(1.8f*s));line(12f,7f,12f,12f);line(12f,12f,16f,14f) }
            "chevron" -> { line(8f,5f,15f,12f); line(15f,12f,8f,19f) }
            "down" -> { line(6f,9f,12f,15f); line(12f,15f,18f,9f) }
            "sound", "muted" -> { val p=Path().apply { moveTo(3*s,9*s);lineTo(7*s,9*s);lineTo(12*s,5*s);lineTo(12*s,19*s);lineTo(7*s,15*s);lineTo(3*s,15*s);close() };drawPath(p,color,style=Stroke(1.6f*s)); if(name=="muted") {line(16f,9f,21f,15f);line(21f,9f,16f,15f)} else {drawArc(color,-60f,120f,false,Offset(8*s,5*s),Size(13*s,14*s),style=Stroke(1.8f*s))} }
            "call" -> { drawRoundRect(color,Offset(6*s,4*s),Size(12*s,15*s),androidx.compose.ui.geometry.CornerRadius(6*s),style=Stroke(1.8f*s));line(9f,21f,15f,21f);line(12f,2f,12f,4f) }
            "logout" -> {line(10f,4f,4f,4f);line(4f,4f,4f,20f);line(4f,20f,10f,20f);line(9f,12f,21f,12f);line(17f,8f,21f,12f);line(17f,16f,21f,12f)}
            "refresh" -> {drawArc(color,40f,290f,false,Offset(4*s,4*s),Size(16*s,16*s),style=Stroke(1.8f*s));line(20f,3f,20f,9f);line(20f,9f,14f,9f)}
            "download" -> {line(12f,3f,12f,16f);line(7f,11f,12f,16f);line(12f,16f,17f,11f);line(4f,18f,4f,21f);line(4f,21f,20f,21f);line(20f,21f,20f,18f)}
            else -> {drawCircle(color,8*s,center,style=Stroke(1.8f*s));line(12f,7f,12f,13f);drawCircle(color,s,Offset(12*s,17*s))}
        }
    }
}
