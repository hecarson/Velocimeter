package com.gmail.hecarson3.velocimeter

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class CompassView(private val context : Context, attributeSet: AttributeSet) : View(context, attributeSet) {

    private var compassAngleDeg = 0f
    private var velocityAngleDeg = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw compass
        canvas.save()
        canvas.rotate(compassAngleDeg, width / 2f, height / 2f)
        val compassDrawable = ContextCompat.getDrawable(context, R.drawable.compass)!!
        compassDrawable.setBounds(0, 0, width, height)
        compassDrawable.draw(canvas)
        canvas.restore()

        // Draw marker
        canvas.save()
        canvas.rotate(velocityAngleDeg, width / 2f, height / 2f)
        val markerDrawable = ContextCompat.getDrawable(context, R.drawable.marker)!!
        markerDrawable.setBounds(0, 0, width, height)
        markerDrawable.draw(canvas)
        canvas.restore()
    }

    fun update(compassAngleDeg: Float, velocityAngleDeg: Float) {
        this.compassAngleDeg = compassAngleDeg
        this.velocityAngleDeg = velocityAngleDeg

        invalidate()
    }

}