package tv.own.owntv.core

/**
 * The handful of build-time facts core needs from whichever app is hosting it.
 *
 * A library module gets its own `BuildConfig`, in its own package, and it carries none of these:
 * `VERSION_NAME`/`VERSION_CODE` do not exist for a library at all, and `TMDB_EDGE_KEY` and
 * `DEV_TOOLS` are the *app's* build inputs. Every one of them is genuinely per-app — the phone app
 * ships its own version and injects its own edge key — so core takes them rather than baking them in.
 *
 * Assigned once from `Application.onCreate`, before anything reads them. It is a settable holder and
 * not a Koin binding on purpose: `CrashRecorder.install()` runs before `startKoin`, and a crash
 * report without a version number is exactly the report that is useless. Same shape as the other
 * two early hooks bound alongside it.
 */
object CoreBuildInfo {

    /** The host app's `BuildConfig.VERSION_NAME`, e.g. `4.2.3`. */
    var versionName: String = ""

    /** The host app's `BuildConfig.VERSION_CODE`. */
    var versionCode: Int = 0

    /**
     * Shared secret the default metadata Worker's edge rule requires (`x-owntv-key`). Blank is a
     * supported configuration — forks and fresh clones build without the secret and fall back to the
     * unprotected worker base URL.
     */
    var edgeKey: String = ""

    /**
     * The host app's maintainer-only build switch. Core uses it for one Logcat trace and nothing
     * else; every piece of dev-tool *UI* lives in the app, where `BuildConfig.DEV_TOOLS` is still a
     * compile-time constant and R8 still removes the branch.
     */
    var devTools: Boolean = false

    /**
     * The host app's `BuildConfig.DEBUG`. Read by the engine to decide between a hard `error()` and a
     * logged warning, and to turn up mpv's own log level. Defaulting to `false` means a host that
     * forgets to set it gets the release behaviour, which is the safe direction.
     */
    var debug: Boolean = false

    /** The host app's `BuildConfig.DIAGNOSTIC_BUILD` — starts the live diagnostics log switched on. */
    var diagnosticBuild: Boolean = false

    /**
     * Whether this host integrates with the Android TV home screen — the Watch Next row and the
     * Recent Live preview channel. True for the TV app, which is what it was written for; false for
     * the phone/tablet app, which has no `LEANBACK_LAUNCHER` entry, so a row it published would be
     * a row nothing could open.
     *
     * A host fact and not a device check: core deliberately does not probe `FEATURE_LEANBACK`,
     * because the question is not "is this a TV?" but "does this app belong on a TV home screen?".
     * The mobile app answers no even when it is sideloaded onto a TV.
     *
     * Defaults to **true** so the TV app needs no change at all, and so nothing here can regress an
     * app that already ships this feature. It is read through
     * [tv.own.owntv.core.settings.SettingsRepository.androidTvHomeEnabled], which is the single gate
     * every publish path already passes through.
     */
    var tvHome: Boolean = true

    /**
     * The `owner/repo` whose GitHub Releases carry this app's APKs, read by
     * [tv.own.owntv.core.update.UpdateManager] when it asks what the newest version is.
     *
     * Genuinely per-app: the two apps publish to two repositories and their assets carry different
     * names, so a single baked-in value would have had the phone offering the television's APK —
     * a different `applicationId`, which the system installer refuses, after a download.
     *
     * Defaults to the TV app's repository so that app needs no change, the same reasoning as
     * [tvHome]. **Asset matching needs nothing per app**: it selects on `.apk` plus the `x86_64`
     * marker, which both repositories' release names carry.
     */
    var releaseRepo: String = "ahXN00/OwnTV"

    /**
     * A direct URL to a version manifest, served outside GitHub. When this is set,
     * [tv.own.owntv.core.update.UpdateManager] reads *this* instead of asking [releaseRepo]'s
     * releases: the manifest is a small JSON object — `version`, `versionCode`, `apkUrl`, `notes` —
     * and [versionCode] decides whether an update is offered, because a name compare cannot order
     * letter-suffixed versions (the fork's television scheme, `1.5p` against `1.5q`, is all zeros
     * past the dot). This is how the fork's apps ask dl.rayge.xyz, which only the fork's links know -
     * GitHub would otherwise offer the original app's APK, a different signature, after a download.
     *
     * Blank keeps the GitHub behaviour; both blank (`releaseRepo = ""`) disables the updater.
     */
    var updateManifestUrl: String = ""
}
