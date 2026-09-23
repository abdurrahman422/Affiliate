package com.ownerlink.agent.remote

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** A deliberately simple home surface shown only on Affiliate's secondary display. */
class WorkspaceHomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(8, 15, 32), Color.rgb(28, 20, 54), Color.rgb(10, 42, 58)),
        )
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(56, 56, 56, 56)
            this.background = background
            addView(TextView(context).apply {
                text = "Affiliate\nRemote Workspace"
                setTextColor(Color.WHITE)
                textSize = 34f
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = "This private secondary display is ready.\nChoose an app from the authenticated dashboard."
                setTextColor(Color.rgb(190, 205, 235))
                textSize = 17f
                gravity = Gravity.CENTER
                setPadding(0, 30, 0, 0)
            })
        }
        setContentView(content)
    }
}
