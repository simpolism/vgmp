package org.vlessert.vgmp.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment

/**
 * Full-screen dialog content must explicitly avoid Android 15's enforced
 * edge-to-edge system bars. Keeping this here makes every dialog screen use
 * the same inset policy.
 */
abstract class InsetAwareDialogFragment : DialogFragment() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val initialPaddingLeft = view.paddingLeft
        val initialPaddingTop = view.paddingTop
        val initialPaddingRight = view.paddingRight
        val initialPaddingBottom = view.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(view) { insetView, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            insetView.updatePadding(
                left = initialPaddingLeft + systemBars.left,
                top = initialPaddingTop + systemBars.top,
                right = initialPaddingRight + systemBars.right,
                bottom = initialPaddingBottom + systemBars.bottom
            )
            windowInsets
        }
        view.doOnAttach { ViewCompat.requestApplyInsets(it) }
    }
}
