package ani.dantotsu.media.anime

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.math.MathUtils
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.viewpager2.widget.ViewPager2
import ani.dantotsu.*
import ani.dantotsu.databinding.FragmentAnimeWatchBinding
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.parsers.AnimeParser
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.HAnimeSources
import ani.dantotsu.settings.ExtensionsActivity
import ani.dantotsu.settings.InstalledAnimeExtensionsFragment
import ani.dantotsu.settings.PlayerSettings
import ani.dantotsu.settings.UserInterfaceSettings
import ani.dantotsu.settings.extensionprefs.AnimeSourcePreferencesFragment
import ani.dantotsu.subcriptions.Notifications
import ani.dantotsu.subcriptions.Notifications.Group.ANIME_GROUP
import ani.dantotsu.subcriptions.Subscription.Companion.getChannelId
import ani.dantotsu.subcriptions.SubscriptionHelper
import ani.dantotsu.subcriptions.SubscriptionHelper.Companion.saveSubscription
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.navigationrail.NavigationRailView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputLayout
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

class AnimeWatchFragment : Fragment() {
    private var _binding: FragmentAnimeWatchBinding? = null
    private val binding get() = _binding!!
    private val model: MediaDetailsViewModel by activityViewModels()

    private lateinit var media: Media

    private var start = 0
    private var end: Int? = null
    private var style: Int? = null
    private var reverse = false

    private lateinit var headerAdapter: AnimeWatchAdapter
    private lateinit var episodeAdapter: EpisodeAdapter

    var screenWidth = 0f
    private var progress = View.VISIBLE

    var continueEp: Boolean = false
    var loaded = false

    lateinit var playerSettings: PlayerSettings
    lateinit var uiSettings: UserInterfaceSettings
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentAnimeWatchBinding.inflate(inflater, container, false)
        return _binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.animeSourceRecycler.updatePadding(bottom = binding.animeSourceRecycler.paddingBottom + navBarHeight)
        screenWidth = resources.displayMetrics.widthPixels.dp

        var maxGridSize = (screenWidth / 100f).roundToInt()
        maxGridSize = max(4, maxGridSize - (maxGridSize % 2))

        playerSettings =
            loadData("player_settings", toast = false) ?: PlayerSettings().apply { saveData("player_settings", this) }
        uiSettings = loadData("ui_settings", toast = false) ?: UserInterfaceSettings().apply { saveData("ui_settings", this) }

        val gridLayoutManager = GridLayoutManager(requireContext(), maxGridSize)

        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val style = episodeAdapter.getItemViewType(position)

                return when (position) {
                    0    -> maxGridSize
                    else -> when (style) {
                        0    -> maxGridSize
                        1    -> 2
                        2    -> 1
                        else -> maxGridSize
                    }
                }
            }
        }

        binding.animeSourceRecycler.layoutManager = gridLayoutManager

        model.scrolledToTop.observe(viewLifecycleOwner) {
            if (it) binding.animeSourceRecycler.scrollToPosition(0)
        }

        continueEp = model.continueMedia ?: false
        model.getMedia().observe(viewLifecycleOwner) {
            if (it != null) {
                media = it
                media.selected = model.loadSelected(media)

                subscribed = SubscriptionHelper.getSubscriptions(requireContext()).containsKey(media.id)

                style = media.selected!!.recyclerStyle
                reverse = media.selected!!.recyclerReversed

                progress = View.GONE
                binding.mediaInfoProgressBar.visibility = progress

                if (!loaded) {
                    model.watchSources = if (media.isAdult) HAnimeSources else AnimeSources

                    headerAdapter = AnimeWatchAdapter(it, this, model.watchSources!!)
                    episodeAdapter = EpisodeAdapter(style ?: uiSettings.animeDefaultView, media, this)

                    binding.animeSourceRecycler.adapter = ConcatAdapter(headerAdapter, episodeAdapter)

                    lifecycleScope.launch(Dispatchers.IO) {
                        awaitAll(
                            async { model.loadKitsuEpisodes(media) },
                            async { model.loadFillerEpisodes(media) }
                        )
                        model.loadEpisodes(media, media.selected!!.sourceIndex)
                    }
                    loaded = true
                } else {
                    reload()
                }
            }
        }
        model.getEpisodes().observe(viewLifecycleOwner) { loadedEpisodes ->
            if (loadedEpisodes != null) {
                val episodes = loadedEpisodes[media.selected!!.sourceIndex]
                if (episodes != null) {
                    episodes.forEach { (i, episode) ->
                        if (media.anime?.fillerEpisodes != null) {
                            if (media.anime!!.fillerEpisodes!!.containsKey(i)) {
                                episode.title = episode.title ?: media.anime!!.fillerEpisodes!![i]?.title
                                episode.filler = media.anime!!.fillerEpisodes!![i]?.filler ?: false
                            }
                        }
                        if (media.anime?.kitsuEpisodes != null) {
                            if (media.anime!!.kitsuEpisodes!!.containsKey(i)) {
                                episode.desc = episode.desc ?: media.anime!!.kitsuEpisodes!![i]?.desc
                                episode.title = episode.title ?: media.anime!!.kitsuEpisodes!![i]?.title
                                episode.thumb = episode.thumb ?: media.anime!!.kitsuEpisodes!![i]?.thumb ?: FileUrl[media.cover]
                            }
                        }
                    }
                    media.anime?.episodes = episodes

                    //CHIP GROUP
                    val total = episodes.size
                    val divisions = total.toDouble() / 10
                    start = 0
                    end = null
                    val limit = when {
                        (divisions < 25) -> 25
                        (divisions < 50) -> 50
                        else             -> 100
                    }
                    headerAdapter.clearChips()
                    if (total > limit) {
                        val arr = media.anime!!.episodes!!.keys.toTypedArray()
                        val stored = ceil((total).toDouble() / limit).toInt()
                        val position = MathUtils.clamp(media.selected!!.chip, 0, stored - 1)
                        val last = if (position + 1 == stored) total else (limit * (position + 1))
                        start = limit * (position)
                        end = last - 1
                        headerAdapter.updateChips(
                            limit,
                            arr,
                            (1..stored).toList().toTypedArray(),
                            position
                        )
                    }

                    headerAdapter.subscribeButton(true)
                    reload()
                }
            }
        }

        model.getKitsuEpisodes().observe(viewLifecycleOwner) { i ->
            if (i != null)
                media.anime?.kitsuEpisodes = i
        }

        model.getFillerEpisodes().observe(viewLifecycleOwner) { i ->
            if (i != null)
                media.anime?.fillerEpisodes = i
        }
    }

    fun onSourceChange(i: Int): AnimeParser {
        media.anime?.episodes = null
        reload()
        val selected = model.loadSelected(media)
        model.watchSources?.get(selected.sourceIndex)?.showUserTextListener = null
        selected.sourceIndex = i
        selected.server = null
        model.saveSelected(media.id, selected, requireActivity())
        media.selected = selected
        return model.watchSources?.get(i)!!
    }

    fun onLangChange(i: Int) {
        val selected = model.loadSelected(media)
        selected.langIndex = i
        model.saveSelected(media.id, selected, requireActivity())
        media.selected = selected
    }

    fun onDubClicked(checked: Boolean) {
        val selected = model.loadSelected(media)
        model.watchSources?.get(selected.sourceIndex)?.selectDub = checked
        selected.preferDub = checked
        model.saveSelected(media.id, selected, requireActivity())
        media.selected = selected
        lifecycleScope.launch(Dispatchers.IO) { model.forceLoadEpisode(media, selected.sourceIndex) }
    }

    fun loadEpisodes(i: Int, invalidate: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) { model.loadEpisodes(media, i, invalidate) }
    }

    fun onIconPressed(viewType: Int, rev: Boolean) {
        style = viewType
        reverse = rev
        media.selected!!.recyclerStyle = style
        media.selected!!.recyclerReversed = reverse
        model.saveSelected(media.id, media.selected!!, requireActivity())
        reload()
    }

    fun onChipClicked(i: Int, s: Int, e: Int) {
        media.selected!!.chip = i
        start = s
        end = e
        model.saveSelected(media.id, media.selected!!, requireActivity())
        reload()
    }

    var subscribed = false
    fun onNotificationPressed(subscribed: Boolean, source: String) {
        this.subscribed = subscribed
        saveSubscription(requireContext(), media, subscribed)
        if (!subscribed)
            Notifications.deleteChannel(requireContext(), getChannelId(true, media.id))
        else
            Notifications.createChannel(
                requireContext(),
                ANIME_GROUP,
                getChannelId(true, media.id),
                media.userPreferredName
            )
        snackString(
            if (subscribed) getString(R.string.subscribed_notification, source)
            else getString(R.string.unsubscribed_notification)
        )
    }
    fun openSettings(pkg: AnimeExtension.Installed){
        val changeUIVisibility: (Boolean) -> Unit = { show ->
            val activity = requireActivity() as MediaDetailsActivity
            val visibility = if (show) View.VISIBLE else View.GONE
            activity.findViewById<AppBarLayout>(R.id.mediaAppBar).visibility = visibility
            activity.findViewById<ViewPager2>(R.id.mediaViewPager).visibility = visibility
            activity.findViewById<CardView>(R.id.mediaCover).visibility = visibility
            activity.findViewById<CardView>(R.id.mediaClose).visibility = visibility
            try{
                activity.findViewById<CustomBottomNavBar>(R.id.mediaTab).visibility = visibility
            }catch (e: ClassCastException){
                activity.findViewById<NavigationRailView>(R.id.mediaTab).visibility = visibility
            }
            activity.findViewById<FrameLayout>(R.id.fragmentExtensionsContainer).visibility =
                if (show) View.GONE else View.VISIBLE
        }
        val allSettings = pkg.sources.filterIsInstance<ConfigurableAnimeSource>()
        if (allSettings.isNotEmpty()) {
            var selectedSetting = allSettings[0]
            if (allSettings.size > 1) {
                val names = allSettings.map { it.lang }.toTypedArray()
                var selectedIndex = 0
                AlertDialog.Builder(requireContext())
                    .setTitle("Select a Source")
                    .setSingleChoiceItems(names, selectedIndex) { _, which ->
                        selectedIndex = which
                    }
                    .setPositiveButton("OK") { dialog, _ ->
                        selectedSetting = allSettings[selectedIndex]
                        dialog.dismiss()

                        // Move the fragment transaction here
                        val fragment =
                            AnimeSourcePreferencesFragment().getInstance(selectedSetting.id){
                                changeUIVisibility(true)
                                loadEpisodes(media.selected!!.sourceIndex, true)
                            }
                        parentFragmentManager.beginTransaction()
                            .setCustomAnimations(R.anim.slide_up, R.anim.slide_down)
                            .replace(R.id.fragmentExtensionsContainer, fragment)
                            .addToBackStack(null)
                            .commit()
                    }
                    .setNegativeButton("Cancel") { dialog, _ ->
                        dialog.cancel()
                        changeUIVisibility(true)
                        return@setNegativeButton
                    }
                    .show()
            } else {
                // If there's only one setting, proceed with the fragment transaction
                val fragment = AnimeSourcePreferencesFragment().getInstance(selectedSetting.id){
                    changeUIVisibility(true)
                    loadEpisodes(media.selected!!.sourceIndex, true)
                }
                parentFragmentManager.beginTransaction()
                    .setCustomAnimations(R.anim.slide_up, R.anim.slide_down)
                    .replace(R.id.fragmentExtensionsContainer, fragment)
                    .addToBackStack(null)
                    .commit()
            }

            changeUIVisibility(false)
        } else {
            Toast.makeText(requireContext(), "Source is not configurable", Toast.LENGTH_SHORT)
                .show()
        }
    }

        fun onEpisodeClick(i: String) {
            model.continueMedia = false
            model.saveSelected(media.id, media.selected!!, requireActivity())
            model.onEpisodeClick(media, i, requireActivity().supportFragmentManager)
        }

        @SuppressLint("NotifyDataSetChanged")
        private fun reload() {
            val selected = model.loadSelected(media)

            //Find latest episode for subscription
            selected.latest =
                media.anime?.episodes?.values?.maxOfOrNull { it.number.toFloatOrNull() ?: 0f } ?: 0f
            selected.latest =
                media.userProgress?.toFloat()?.takeIf { selected.latest < it } ?: selected.latest

            model.saveSelected(media.id, selected, requireActivity())
            headerAdapter.handleEpisodes()
            episodeAdapter.notifyItemRangeRemoved(0, episodeAdapter.arr.size)
            var arr: ArrayList<Episode> = arrayListOf()
            if (media.anime!!.episodes != null) {
                val end = if (end != null && end!! < media.anime!!.episodes!!.size) end else null
                arr.addAll(
                    media.anime!!.episodes!!.values.toList()
                        .slice(start..(end ?: (media.anime!!.episodes!!.size - 1)))
                )
                if (reverse)
                    arr = (arr.reversed() as? ArrayList<Episode>) ?: arr
            }
            episodeAdapter.arr = arr
            episodeAdapter.updateType(style ?: uiSettings.animeDefaultView)
            episodeAdapter.notifyItemRangeInserted(0, arr.size)
        }

                override fun onDestroy() {
            model.watchSources?.flushText()
            super.onDestroy()
        }

        var state: Parcelable? = null
    override fun onResume() {
        super.onResume()
        binding.mediaInfoProgressBar.visibility = progress
        binding.animeSourceRecycler.layoutManager?.onRestoreInstanceState(state)
    }

    override fun onPause() {
        super.onPause()
        state = binding.animeSourceRecycler.layoutManager?.onSaveInstanceState()
    }

}