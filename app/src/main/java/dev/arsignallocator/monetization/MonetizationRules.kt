package dev.arsignallocator.monetization

enum class EntitlementState {
    FREE,
    PENDING,
    PRO_CACHED,
    PRO_CONFIRMED,
}

enum class StorePurchaseState {
    PENDING,
    PURCHASED,
}

fun EntitlementState.allowsCompleteScan(): Boolean =
    this == EntitlementState.PRO_CACHED || this == EntitlementState.PRO_CONFIRMED

data class EntitlementDecision(
    val state: EntitlementState,
    val cachePro: Boolean,
)

data class PurchaseReconciliation(
    val decision: EntitlementDecision,
    val authoritative: Boolean,
    val hasVerificationError: Boolean,
)

fun reconcileSuccessfulPurchases(
    cachedPro: Boolean,
    purchases: Collection<StorePurchaseState>,
    authoritative: Boolean,
): EntitlementDecision = when {
    StorePurchaseState.PURCHASED in purchases ->
        EntitlementDecision(EntitlementState.PRO_CONFIRMED, cachePro = true)
    !authoritative && cachedPro ->
        EntitlementDecision(EntitlementState.PRO_CACHED, cachePro = true)
    StorePurchaseState.PENDING in purchases ->
        EntitlementDecision(EntitlementState.PENDING, cachePro = false)
    else ->
        EntitlementDecision(EntitlementState.FREE, cachePro = false)
}

fun reconcileVerifiedPurchases(
    cachedPro: Boolean,
    verifiedPurchases: Collection<StorePurchaseState>,
    invalidPurchaseCount: Int,
    authoritative: Boolean,
): PurchaseReconciliation {
    val hasVerificationError = invalidPurchaseCount > 0
    val validPurchase = StorePurchaseState.PURCHASED in verifiedPurchases
    val verifiedAuthority = authoritative && (!hasVerificationError || validPurchase)
    return PurchaseReconciliation(
        decision = reconcileSuccessfulPurchases(
            cachedPro = cachedPro,
            purchases = verifiedPurchases,
            authoritative = verifiedAuthority,
        ),
        authoritative = verifiedAuthority,
        hasVerificationError = hasVerificationError,
    )
}

class CallbackGeneration {
    private var current = 0L

    @Synchronized
    fun next(): Long = ++current

    @Synchronized
    fun advance(block: () -> Unit) {
        ++current
        block()
    }

    @Synchronized
    fun runIfCurrent(generation: Long, block: () -> Unit): Boolean {
        if (generation != current) return false
        block()
        return true
    }
}

class SuccessfulQueryGeneration {
    private var requested = 0L
    private var latestSuccess = 0L

    @Synchronized
    fun next(): Long = ++requested

    @Synchronized
    fun advance(block: () -> Unit) {
        latestSuccess = ++requested
        block()
    }

    @Synchronized
    fun runIfNewestSuccess(generation: Long, block: () -> Unit): Boolean {
        if (generation <= latestSuccess) return false
        latestSuccess = generation
        block()
        return true
    }

    @Synchronized
    fun isNewerThanLatestSuccess(generation: Long): Boolean = generation > latestSuccess
}

class AcknowledgementAttemptGate {
    private val activeTokens = mutableSetOf<String>()

    @Synchronized
    fun start(token: String): Boolean = activeTokens.add(token)

    @Synchronized
    fun finish(token: String) {
        activeTokens.remove(token)
    }
}

enum class AcknowledgementResponseAction {
    COMPLETE,
    RETRY,
    REQUERY,
    FAIL,
}

fun acknowledgementResponseAction(responseCode: Int): AcknowledgementResponseAction = when (
    responseCode
) {
    0 -> AcknowledgementResponseAction.COMPLETE
    -3, -1, 2, 6, 12 -> AcknowledgementResponseAction.RETRY
    -2 -> AcknowledgementResponseAction.FAIL
    // ITEM_NOT_OWNED can reflect stale Play cache; query ownership before retrying.
    8 -> AcknowledgementResponseAction.REQUERY
    else -> AcknowledgementResponseAction.FAIL
}

fun acknowledgementRetryDelayMillis(retry: Int): Long =
    (1_000L shl retry.coerceIn(0, 3)).coerceAtMost(8_000L)

data class ConsentState(
    val consentAllowsAds: Boolean = false,
    val privacyOptionsRequirement: PrivacyOptionsRequirement =
        PrivacyOptionsRequirement.UNKNOWN,
    val adsSdkReady: Boolean = false,
    val canRequestAds: Boolean = false,
    val isRefreshing: Boolean = false,
    val privacyError: String? = null,
)

enum class PrivacyOptionsRequirement {
    UNKNOWN,
    NOT_REQUIRED,
    REQUIRED,
}

data class ConsentSnapshot(
    val consentAllowsAds: Boolean,
    val privacyOptionsRequirement: PrivacyOptionsRequirement,
) {
    constructor(
        consentAllowsAds: Boolean,
        privacyOptionsRequired: Boolean,
    ) : this(
        consentAllowsAds,
        if (privacyOptionsRequired) {
            PrivacyOptionsRequirement.REQUIRED
        } else {
            PrivacyOptionsRequirement.NOT_REQUIRED
        },
    )
}

fun updatedConsentState(
    previous: ConsentState,
    snapshot: ConsentSnapshot?,
    adsEligible: Boolean,
    adsSdkReady: Boolean,
): ConsentState {
    val consentAllowsAds = snapshot?.consentAllowsAds ?: previous.consentAllowsAds
    val privacyOptionsRequirement =
        snapshot?.privacyOptionsRequirement
            ?.takeUnless { it == PrivacyOptionsRequirement.UNKNOWN }
            ?: previous.privacyOptionsRequirement
    return ConsentState(
        consentAllowsAds = consentAllowsAds,
        privacyOptionsRequirement = privacyOptionsRequirement,
        adsSdkReady = adsSdkReady,
        canRequestAds = adsEligible && consentAllowsAds && adsSdkReady,
        isRefreshing = previous.isRefreshing,
        privacyError = previous.privacyError,
    )
}

class ConsentRetryGate(private val maxAttempts: Int) {
    private var attempts = 0
    private var inFlight = false
    private var completed = false

    init {
        require(maxAttempts > 0)
    }

    @Synchronized
    fun begin(): Boolean {
        if (completed || inFlight || attempts >= maxAttempts) return false
        attempts += 1
        inFlight = true
        return true
    }

    @Synchronized
    fun finish(success: Boolean): Int? {
        check(inFlight)
        inFlight = false
        completed = success
        return if (!success && attempts < maxAttempts) attempts - 1 else null
    }
}

fun consentRetryDelayMillis(failure: Int): Long =
    (1_000L shl failure.coerceIn(0, 2)).coerceAtMost(4_000L)

data class AdsEligibility(
    val adsEnabled: Boolean,
    val isDebugBuild: Boolean,
    val debugTestAdsEnabled: Boolean,
    val entitlement: EntitlementState,
    val billingAuthoritative: Boolean,
)

fun canInitializeAds(eligibility: AdsEligibility): Boolean =
    eligibility.adsEnabled &&
        eligibility.billingAuthoritative &&
        (!eligibility.isDebugBuild || eligibility.debugTestAdsEnabled) &&
        eligibility.entitlement != EntitlementState.PRO_CACHED &&
        eligibility.entitlement != EntitlementState.PRO_CONFIRMED

data class AdRequestContext(
    val eligibility: AdsEligibility,
    val screenAllowsAds: Boolean,
    val consentAllowsAds: Boolean,
    val adsSdkReady: Boolean,
)

fun canRequestBanner(context: AdRequestContext): Boolean =
    canInitializeAds(context.eligibility) &&
        context.consentAllowsAds &&
        context.adsSdkReady &&
        context.screenAllowsAds

enum class CompleteScanRoute {
    START,
    SHOW_PRO,
}

fun completeScanRoute(entitlement: EntitlementState): CompleteScanRoute = when (entitlement) {
    EntitlementState.PRO_CACHED,
    EntitlementState.PRO_CONFIRMED,
    -> CompleteScanRoute.START
    EntitlementState.FREE,
    EntitlementState.PENDING,
    -> CompleteScanRoute.SHOW_PRO
}

enum class BannerSurface {
    PICKER,
    TRACKING_PORTRAIT,
    TRACKING_NARROW_LANDSCAPE,
    TRACKING_RAIL,
    SETTINGS,
    HISTORY,
    SURVEY,
    PERMISSIONS,
    ONBOARDING,
    PURCHASE,
    COMPLETE_SCAN,
}

fun bannerFormat(surface: BannerSurface): BannerFormat? = when (surface) {
    BannerSurface.PICKER,
    BannerSurface.TRACKING_PORTRAIT,
    BannerSurface.TRACKING_NARROW_LANDSCAPE,
    -> BannerFormat.ANCHORED_ADAPTIVE
    BannerSurface.TRACKING_RAIL -> BannerFormat.FIXED_320_BY_50
    BannerSurface.SETTINGS,
    BannerSurface.HISTORY,
    BannerSurface.SURVEY,
    BannerSurface.PERMISSIONS,
    BannerSurface.ONBOARDING,
    BannerSurface.PURCHASE,
    BannerSurface.COMPLETE_SCAN,
    -> null
}
