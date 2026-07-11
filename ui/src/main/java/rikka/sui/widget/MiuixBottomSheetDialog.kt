package rikka.sui.widget

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import rikka.sui.R

class MiuixBottomSheetDialog(
    context: Context,
    private val contentView: View,
) : Dialog(context, R.style.MiuixBottomSheetDialogStyle) {

    private lateinit var layout: MiuixBottomSheetLayout
    private var dimOverlay: View? = null
    private var backCallback: Any? = null

    private fun setupDimOverlay() {
        var actContext = context
        while (actContext is android.content.ContextWrapper) {
            if (actContext is android.app.Activity) break
            actContext = actContext.baseContext
        }
        val activity = actContext as? android.app.Activity ?: return

        dimOverlay = View(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            setBackgroundColor(if (isDark) 0x99000000.toInt() else 0x4D000000)
            alpha = 0f
        }
        (activity.window.decorView as ViewGroup).addView(dimOverlay)
    }

    private fun removeDimOverlay() {
        dimOverlay?.let {
            (it.parent as? ViewGroup)?.removeView(it)
        }
        dimOverlay = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupDimOverlay()

        layout = MiuixBottomSheetLayout(context)

        layout.onDimAlphaChange = { alpha ->
            dimOverlay?.alpha = alpha
        }
        layout.onDimAlphaAnimate = { alpha, duration ->
            dimOverlay?.animate()?.alpha(alpha)?.setDuration(duration)?.setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))?.start()
        }

        (contentView.parent as? ViewGroup)?.removeView(contentView)

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        lp.gravity = android.view.Gravity.BOTTOM
        layout.addView(contentView, lp)

        layout.onDismissRequest = {
            super.dismiss()
        }

        setContentView(
            layout,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        @Suppress("DEPRECATION")
        window?.let {
            it.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            it.setBackgroundDrawableResource(android.R.color.transparent)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                it.setDecorFitsSystemWindows(false)
            } else {
                it.decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                it.isNavigationBarContrastEnforced = false
                it.isStatusBarContrastEnforced = false
            }

            it.statusBarColor = Color.TRANSPARENT
            it.navigationBarColor = Color.TRANSPARENT
            it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
                    layout.dismiss()
                    true
                } else {
                    false
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backCallback == null) {
            val callback = OnBackInvokedCallback { layout.dismiss() }
            window?.onBackInvokedDispatcher?.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback,
            )
            backCallback = callback
        }
    }

    override fun show() {
        super.show()
        layout.show()
    }

    override fun dismiss() {
        layout.dismiss()
    }

    override fun onStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            (backCallback as? OnBackInvokedCallback)?.let { callback ->
                window?.onBackInvokedDispatcher?.unregisterOnBackInvokedCallback(callback)
            }
            backCallback = null
        }
        removeDimOverlay()
        super.onStop()
    }
}
