package dev.arsignallocator.monetization

import android.app.Activity
import android.view.ViewGroup
import androidx.annotation.MainThread
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import dev.arsignallocator.BuildConfig

enum class BannerFormat {
    ANCHORED_ADAPTIVE,
    FIXED_320_BY_50,
}

internal interface ManagedBannerAd {
    val view: AdView

    fun load(onLoaded: () -> Unit, onFailed: () -> Unit)

    fun destroyAndRemove()
}

internal fun interface ManagedBannerAdFactory {
    fun create(activity: Activity, widthDp: Int, format: BannerFormat): ManagedBannerAd
}

private object GoogleBannerAdFactory : ManagedBannerAdFactory {
    override fun create(
        activity: Activity,
        widthDp: Int,
        format: BannerFormat,
    ): ManagedBannerAd {
        val adSize = when (format) {
            BannerFormat.ANCHORED_ADAPTIVE ->
                AdSize.getLargeAnchoredAdaptiveBannerAdSize(activity, widthDp)
            BannerFormat.FIXED_320_BY_50 -> AdSize.BANNER
        }
        return GoogleBannerAd(
            activity,
            AdView(activity),
            BannerAdRequest.Builder(BuildConfig.ADMOB_BANNER_ID, adSize).build(),
        )
    }
}

private class GoogleBannerAd(
    private val activity: Activity,
    override val view: AdView,
    private val request: BannerAdRequest,
) : ManagedBannerAd {
    private var destroyed = false

    override fun load(onLoaded: () -> Unit, onFailed: () -> Unit) {
        view.loadAd(
            request,
            object : AdLoadCallback<BannerAd> {
                override fun onAdLoaded(ad: BannerAd) {
                    activity.runOnUiThread(onLoaded)
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    activity.runOnUiThread(onFailed)
                }
            },
        )
    }

    override fun destroyAndRemove() {
        if (destroyed) return
        destroyed = true
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
    }
}

class BannerAdLoader internal constructor(
    owner: LifecycleOwner,
    private val factory: ManagedBannerAdFactory,
) : DefaultLifecycleObserver {
    private var current: ManagedBannerAd? = null
    private var request: Pair<Int, BannerFormat>? = null
    private var destroyed = owner.lifecycle.currentState == Lifecycle.State.DESTROYED

    constructor(owner: LifecycleOwner) : this(owner, GoogleBannerAdFactory)

    init {
        if (!destroyed) owner.lifecycle.addObserver(this)
    }

    @MainThread
    fun update(
        activity: Activity,
        widthDp: Int,
        format: BannerFormat,
        requestContext: AdRequestContext,
        onLoaded: (AdView) -> Unit,
        onFailed: () -> Unit,
    ) {
        if (destroyed || !canRequestBanner(requestContext)) {
            clear()
            return
        }
        val requested = widthDp to format
        if (current != null && request == requested) return
        clear()

        val banner = factory.create(activity, widthDp, format)
        current = banner
        request = requested
        banner.load(
            onLoaded = {
                if (current === banner) {
                    onLoaded(banner.view)
                } else {
                    banner.destroyAndRemove()
                }
            },
            onFailed = {
                if (current === banner) {
                    clear()
                    onFailed()
                }
            },
        )
    }

    @MainThread
    override fun onDestroy(owner: LifecycleOwner) {
        destroyed = true
        clear()
    }

    private fun clear() {
        val banner = current
        current = null
        request = null
        banner?.destroyAndRemove()
    }
}
