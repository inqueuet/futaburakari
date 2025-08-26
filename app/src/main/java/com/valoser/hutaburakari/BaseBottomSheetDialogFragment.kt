package com.valoser.hutaburakari

import android.app.Dialog
import android.os.Bundle
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

open class BaseBottomSheetDialogFragment : BottomSheetDialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // Use Activity context; BaseActivity already applies fontScale and theme
        return BottomSheetDialog(requireContext(), theme)
    }
}
