package ani.dantotsu.media.user

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.Refresh
import ani.dantotsu.databinding.ActivityListBinding
import ani.dantotsu.loadData
import ani.dantotsu.settings.UserInterfaceSettings
import ani.dantotsu.themes.ThemeManager
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ListActivity : AppCompatActivity() {
    private lateinit var binding: ActivityListBinding
    private val scope = lifecycleScope
    private var selectedTabIdx = 0

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityListBinding.inflate(layoutInflater)

        val typedValue = TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorOnPrimary, typedValue, true)
        val primaryColor = typedValue.data
        val typedValue2 = TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue2, true)
        val primaryTextColor = typedValue2.data
        val typedValue3 = TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorSecondary, typedValue3, true)
        val secondaryColor = typedValue3.data

        window.statusBarColor = primaryColor
        window.navigationBarColor = primaryColor
        binding.listTabLayout.setBackgroundColor(primaryColor)
        binding.listAppBar.setBackgroundColor(primaryColor)
        binding.listTitle.setTextColor(primaryTextColor)
        binding.listTabLayout.setTabTextColors(primaryTextColor, primaryTextColor)
        binding.listTabLayout.setSelectedTabIndicatorColor(primaryTextColor)
        val uiSettings = loadData<UserInterfaceSettings>("ui_settings") ?: UserInterfaceSettings()
        if (!uiSettings.immersiveMode) {
            this.window.statusBarColor =
                ContextCompat.getColor(this, R.color.nav_bg_inv)
            binding.root.fitsSystemWindows = true

        }else{
            binding.root.fitsSystemWindows = false
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        setContentView(binding.root)

        val anime = intent.getBooleanExtra("anime", true)
        binding.listTitle.text = intent.getStringExtra("username") + "'s " + (if (anime) "Anime" else "Manga") + " List"

        binding.listTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                this@ListActivity.selectedTabIdx = tab?.position ?: 0
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) { }
            override fun onTabReselected(tab: TabLayout.Tab?) { }
        })

        val model: ListViewModel by viewModels()
        model.getLists().observe(this) {
            val defaultKeys = listOf("Reading", "Watching", "Completed", "Paused", "Dropped", "Planning", "Favourites", "Rewatching", "Rereading", "All")
            val userKeys : Array<String> = resources.getStringArray(R.array.keys)

            if (it != null) {
                binding.listProgressBar.visibility = View.GONE
                binding.listViewPager.adapter = ListViewPagerAdapter(it.size, false,this)
                val keys = it.keys.toList().map { key -> userKeys.getOrNull(defaultKeys.indexOf(key))?: key }
                val values = it.values.toList()
                val savedTab = this.selectedTabIdx
                TabLayoutMediator(binding.listTabLayout, binding.listViewPager) { tab, position ->
                    tab.text = "${keys[position]} (${values[position].size})"
                }.attach()
                binding.listViewPager.setCurrentItem(savedTab, false)
            }
        }

        val live = Refresh.activity.getOrPut(this.hashCode()) { MutableLiveData(true) }
        live.observe(this) {
            if (it) {
                scope.launch {
                    withContext(Dispatchers.IO) { model.loadLists(anime, intent.getIntExtra("userId", 0)) }
                    live.postValue(false)
                }
            }
        }

       binding.listSort.setOnClickListener {
           val popup = PopupMenu(this, it)
           popup.setOnMenuItemClickListener { item ->
               val sort = when (item.itemId) {
                   R.id.score -> "score"
                   R.id.title -> "title"
                   R.id.updated -> "updatedAt"
                   R.id.release -> "release"
                   else -> null
               }

               binding.listProgressBar.visibility = View.VISIBLE
               binding.listViewPager.adapter = null
               scope.launch {
                   withContext(Dispatchers.IO) { model.loadLists(anime, intent.getIntExtra("userId", 0), sort) }
               }
               true
           }
           popup.inflate(R.menu.list_sort_menu)
           popup.show()
       }
    }
}