package one.codehz.container

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.support.design.widget.CollapsingToolbarLayout
import android.support.design.widget.TabLayout
import android.support.v4.app.FragmentPagerAdapter
import android.support.v4.view.ViewPager
import android.support.v7.graphics.Palette
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import com.lody.virtual.helper.utils.VLog
import com.lody.virtual.os.VUserHandle
import one.codehz.container.base.BaseActivity
import one.codehz.container.ext.get
import one.codehz.container.ext.setBackground
import one.codehz.container.ext.systemService
import one.codehz.container.ext.virtualCore
import one.codehz.container.fragment.BasicDetailFragment
import one.codehz.container.fragment.ComponentDetailFragment
import one.codehz.container.fragment.SpecialSettingsFragment
import one.codehz.container.models.AppModel
import one.codehz.container.provider.MainProvider

class DetailActivity : BaseActivity(R.layout.application_detail) {
    companion object {
        val RESULT_DELETE_APK = 1
        val REQUEST_USER = 0
        val REQUEST_USER_FOR_SHORTCUT = 1
        val SELECT_SERVICES = 2

        fun launch(context: Activity, appModel: AppModel, iconView: View, startFn: (Intent, Bundle) -> Unit) {
            startFn(Intent(context, DetailActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.Builder().scheme("container").authority("detail").appendPath(appModel.packageName).build()
            }, ActivityOptions.makeSceneTransitionAnimation(context, iconView, "app_icon").toBundle())
        }
    }

    val package_name: String by lazy { intent.data.path.substring(1) }
    val model by lazy { AppModel(this, virtualCore.findApp(package_name)) }

    val iconView by lazy<ImageView> { this[R.id.icon] }
    val viewPager by lazy<ViewPager> { this[R.id.viewPager] }
    val tabLayout by lazy<TabLayout> { this[R.id.tabs] }
    val collapsingToolbar by lazy<CollapsingToolbarLayout> { this[R.id.collapsing_toolbar] }
    var bgcolor = 0
    val handler = Handler()

    inner class DetailPagerAdapter : FragmentPagerAdapter(supportFragmentManager) {
        override fun getItem(position: Int) = when (position) {
            0 -> BasicDetailFragment(model) {
                it.setBackground(bgcolor)
                it.show()
            }
            1 -> ComponentDetailFragment(model)
            2 -> SpecialSettingsFragment(model)
            else -> throw IllegalArgumentException()
        }

        override fun getPageTitle(position: Int) = when (position) {
            0 -> getString(R.string.basic_info)!!
            1 -> getString(R.string.component_filter)!!
            2 -> getString(R.string.special_settings)!!
            else -> throw IllegalArgumentException()
        }

        override fun getCount() = 3
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setResult(Activity.RESULT_CANCELED)

        postponeEnterTransition()

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        iconView.setImageDrawable(model.icon)
        Palette.from(model.icon.bitmap).apply { maximumColorCount(1) }.generate { palette ->
            try {
                val (main_color) = palette.swatches
                val dark_color = Color.HSVToColor(floatArrayOf(0f, 0f, 0f).apply { Color.colorToHSV(main_color.rgb, this) }.apply { this[2] *= 0.8f })

                window.statusBarColor = dark_color
                window.navigationBarColor = dark_color
                bgcolor = dark_color
                collapsingToolbar.background = ColorDrawable(main_color.rgb)

                setTaskDescription(ActivityManager.TaskDescription(getString(R.string.task_detail_prefix, model.name), model.icon.bitmap, dark_color))
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                startPostponedEnterTransition()
            }
        }

        tabLayout.setupWithViewPager(viewPager)

        with(viewPager) {
            adapter = DetailPagerAdapter()
            offscreenPageLimit = 3
        }

        collapsingToolbar.title = model.name
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.detail_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finishAfterTransition()
                true
            }
            R.id.run -> {
                LoadingActivity.launch(this, model, VUserHandle.USER_OWNER, iconView)
                true
            }
            R.id.run_as -> {
                startActivityForResult(Intent(this, UserSelectorActivity::class.java), REQUEST_USER)
                true
            }
            R.id.uninstall -> {
                setResult(RESULT_DELETE_APK, intent)
                finishAfterTransition()
                true
            }
            R.id.send_to_desktop -> {
                startActivity(Intent(this, ShortcutMakerActivity::class.java).apply { putExtra(ShortcutMakerActivity.EXTRA_PACKAGE, model.packageName) })
                true
            }
            else -> false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQUEST_USER -> if (resultCode == Activity.RESULT_OK) {
                data!!
                handler.post {
                    LoadingActivity.launch(this, model, data.getIntExtra(UserSelectorActivity.KEY_USER_ID, 0), iconView)
                }
            }
            REQUEST_USER_FOR_SHORTCUT -> if (resultCode == Activity.RESULT_OK) {
                data!!
                sendBroadcast(Intent("com.android.launcher.action.INSTALL_SHORTCUT").apply {
                    val size = systemService<ActivityManager>(Context.ACTIVITY_SERVICE).launcherLargeIconSize
                    val scaledIcon = Bitmap.createScaledBitmap(model.icon.bitmap, size, size, false)
                    putExtra(Intent.EXTRA_SHORTCUT_INTENT, Intent(this@DetailActivity, VLoadingActivity::class.java).apply {
                        this.data = Uri.Builder().scheme("container").authority("launch").appendPath(model.packageName).fragment(data.getIntExtra(UserSelectorActivity.KEY_USER_ID, 0).toString()).build()
                    })
                    putExtra(Intent.EXTRA_SHORTCUT_NAME, model.name)
                    putExtra(Intent.EXTRA_SHORTCUT_ICON, scaledIcon)
                    putExtra("duplicate", false)
                })
            }
            SELECT_SERVICES -> {
                data?.getStringArrayExtra("LIST")?.forEach {
                    contentResolver.insert(MainProvider.COMPONENT_URI, ContentValues().apply {
                        put("package", model.packageName)
                        put("type", "service")
                        put("action", it)
                        VLog.d("DA", toString())
                    })
                }
            }
        }
    }
}