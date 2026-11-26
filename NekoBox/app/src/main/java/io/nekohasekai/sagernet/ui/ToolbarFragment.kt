package io.nekohasekai.sagernet.ui

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.AppBarLayout
import io.nekohasekai.sagernet.R

open class ToolbarFragment : Fragment {

    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    lateinit var toolbar: Toolbar

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar = view.findViewById(R.id.toolbar)
        toolbar.setNavigationIcon(R.drawable.ic_navigation_menu)
        toolbar.setNavigationOnClickListener {
            (activity as? MainActivity)?.binding?.drawerLayout?.openDrawer(GravityCompat.START)
        }
        // WHY: MainActivity retains ConfigurationFragment via hide/show, so Activity.findViewById(R.id.appbar)
        // only hits the first (hidden) fragment. Each ToolbarFragment must apply status-bar insets to its own AppBarLayout.
        if (Build.VERSION.SDK_INT >= 35) {
            view.findViewById<AppBarLayout>(R.id.appbar)?.let { appBar ->
                ViewCompat.getRootWindowInsets(view)?.let { rootInsets ->
                    val top = rootInsets.getInsets(WindowInsetsCompat.Type.systemBars()).top
                    appBar.updatePadding(top = top)
                }
                ViewCompat.setOnApplyWindowInsetsListener(appBar) { v, insets ->
                    val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
                    v.updatePadding(top = top)
                    insets
                }
                ViewCompat.requestApplyInsets(appBar)
            }
        }
    }

    open fun onKeyDown(ketCode: Int, event: KeyEvent) = false
    open fun onBackPressed(): Boolean = false
}
