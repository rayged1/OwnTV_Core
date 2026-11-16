package tv.own.owntv.core.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import tv.own.owntv.core.CoreBuildInfo
import java.io.File
import java.io.IOException

/**
 * In-app updates straight from GitHub Releases — or, for forks that serve them elsewhere, from a
 * small version manifest at a URL of the app's choosing. When
 * [tv.own.owntv.core.CoreBuildInfo.updateManifestUrl] is set the check reads that JSON
 * (`version`, `versionCode`, `apkUrl`, `notes`) and the code decides; when it is blank the check
 * asks [tv.own.owntv.core.CoreBuildInfo.releaseRepo]'s latest GitHub release and the release *name*
 * decides. Either way: compare versions, download the release APK, and hand it to the system
 * installer. GitHub's releases CI already publishes one APK per ABI flavour per tag
 * (`OwnTV-vX.Y.Z.apk` / `OwnTV-x86_64-vX.Y.Z.apk` on the television, `OwnTV-Mobile-…` on the phone),
 * and the asset matching this device's ABI is chosen, so updates also work on an x86_64 emulator.
 * **Which repository is asked is the host app's to say** — see [CoreBuildInfo.releaseRepo].
 */
class UpdateManager(
    private val context: Context,
    private val client: OkHttpClient,
    private val settings: tv.own.owntv.core.settings.SettingsRepository,
) {
    data class UpdateInfo(val version: String, val notes: String, val apkUrl: String, val versionCode: Long = -1L)

    sealed interface Failure {
        data class CheckHttp(val code: Int) : Failure
        data object NoCompatibleApk : Failure
        data object InvalidReleaseResponse : Failure
        data object CheckNetwork : Failure
        data class DownloadHttp(val code: Int) : Failure
        data object EmptyDownload : Failure
        data object DownloadNetwork : Failure
        data object Install : Failure

        /** Internal storage cannot hold the APK twice (download + install session). */
        data class NotEnoughSpace(val requiredBytes: Long) : Failure

        /** The file that arrived is truncated or not a readable OwnTV package — never install it. */
        data object DamagedDownload : Failure

        /** The system installer refused the package; [message] is its own wording when it gave one. */
        data class InstallRejected(val message: String?) : Failure
    }

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val info: UpdateInfo) : State
        data class Downloading(val percent: Int) : State

        /** The session is in and the system is holding it for the user's OK — waiting on their tap. */
        data object AwaitingUserAction : State
        data class Failed(val failure: Failure, val retryInfo: UpdateInfo? = null) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private class CheckHttpException(val code: Int) : IOException()
    private class DownloadHttpException(val code: Int) : IOException()
    private class NoCompatibleApkException : IOException()
    private class InvalidReleaseResponseException : IOException()
    private class EmptyDownloadException : IOException()
    private class NotEnoughSpaceException(val requiredBytes: Long) : IOException()
    private class DamagedDownloadException(reason: String) : IOException(reason)

    private fun failureFor(error: Throwable, checking: Boolean): Failure = when (error) {
        is CheckHttpException -> Failure.CheckHttp(error.code)
        is DownloadHttpException -> Failure.DownloadHttp(error.code)
        is NoCompatibleApkException -> Failure.NoCompatibleApk
        is InvalidReleaseResponseException -> Failure.InvalidReleaseResponse
        is EmptyDownloadException -> Failure.EmptyDownload
        is NotEnoughSpaceException -> Failure.NotEnoughSpace(error.requiredBytes)
        is DamagedDownloadException -> Failure.DamagedDownload
        else -> if (checking) Failure.CheckNetwork else Failure.DownloadNetwork
    }

    val currentVersion: String = CoreBuildInfo.versionName

    /** Queries GitHub's latest release (or the fork's version manifest); moves to Available / UpToDate / a semantic failure. */
    fun check() {
        if (_state.value is State.Checking || _state.value is State.Downloading) return
        _state.value = State.Checking
        scope.launch {
            runCatching {
                // The fork's apps read a version manifest from dl.rayge.xyz: a private code base has
                // no GitHub releases the app can see without shipping a token, and the original apps'
                // releases carry a different signature — the installer refuses them after the whole
                // download. Everything downstream (notes, download, installer session) is the same
                // machine; only the answer's shape differs.
                val manifestUrl = CoreBuildInfo.updateManifestUrl
                val info: UpdateInfo = if (manifestUrl.isNotBlank()) {
                    val request = Request.Builder()
                        .url(manifestUrl)
                        .header("User-Agent", "OwnTV")
                        .build()
                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) throw CheckHttpException(resp.code)
                        val body = resp.body.string()
                        if (body.isBlank()) throw InvalidReleaseResponseException()
                        readManifest(body)
                    }
                } else {
                    val request = Request.Builder()
                        .url("https://api.github.com/repos/${CoreBuildInfo.releaseRepo}/releases/latest")
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "OwnTV")
                        .build()
                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) throw CheckHttpException(resp.code)
                        val body = resp.body.string()
                        if (body.isBlank()) throw InvalidReleaseResponseException()
                        val o = runCatching { JSONObject(body) }.getOrElse { throw InvalidReleaseResponseException() }
                        val version = o.optString("tag_name").removePrefix("v").takeIf { it.isNotBlank() }
                            ?: throw InvalidReleaseResponseException()
                        val notes = o.optString("body").take(16_000)
                        val assets = o.optJSONArray("assets") ?: throw InvalidReleaseResponseException()
                        // Releases carry one APK per ABI flavor (arm = generic, x86_64 suffixed). Never
                        // silently install an APK for the wrong ABI.
                        val wantX86 = android.os.Build.SUPPORTED_ABIS.firstOrNull() == "x86_64"
                        val apkUrl = (0 until assets.length())
                            .asSequence()
                            .mapNotNull { assets.optJSONObject(it) }
                            .mapNotNull { asset ->
                                val name = asset.optString("name")
                                val url = asset.optString("browser_download_url")
                                if (!name.endsWith(".apk") || url.isBlank()) return@mapNotNull null
                                val isX86 = name.contains("x86_64", ignoreCase = true)
                                if (isX86 == wantX86) url else null
                            }
                            .firstOrNull()
                            ?: throw NoCompatibleApkException()
                        UpdateInfo(version, notes, apkUrl)
                    }
                }
                settings.recordUpdateCheck(System.currentTimeMillis())
                if (isNewer(info)) _state.value = State.Available(info)
                else _state.value = State.UpToDate
            }.onFailure { error ->
                Log.w(TAG, "update check failed: ${error.message}", error)
                _state.value = State.Failed(failureFor(error, checking = true))
            }
        }
    }

    /**
     * Reads a manifest update answer into [UpdateInfo], without guessing. `versionCode` decides —
     * the version *name* can't order the fork's schemes (`1.5p` and `1.5q` are numerically
     * identical), so a manifest without a code is treated as a malformed answer. The compare uses
     * the host app's own code, set from its `BuildConfig` at startup; when they match the APK is
     * the one the manifest already describes.
     */
    private fun readManifest(body: String): UpdateInfo {
        val o = runCatching { JSONObject(body) }.getOrElse { throw InvalidReleaseResponseException() }
        val version = o.optString("version").takeIf { it.isNotBlank() }
            ?: throw InvalidReleaseResponseException()
        val apkUrl = o.optString("apkUrl").takeIf { it.isNotBlank() }
            ?: throw InvalidReleaseResponseException()
        val notes = o.optString("notes").take(16_000)
        val remoteCode = if (o.has("versionCode")) o.optLong("versionCode") else -1L
        if (remoteCode < 0) throw InvalidReleaseResponseException()
        return UpdateInfo(version, notes, apkUrl, remoteCode)
    }

    /**
     * Offers the update. With a manifest the [UpdateInfo.versionCode] is the truth — the version
     * *name* cannot order the fork's schemes (`1.5p` and `1.5q` are numerically identical), while
     * the code is exactly what the installer compares anyway. The GitHub path has only names, so it
     * keeps the segment-wise numeric walk below.
     */
    private fun isNewer(info: UpdateInfo): Boolean {
        val code = CoreBuildInfo.versionCode
        if (code > 0 && info.versionCode > 0) return info.versionCode > code
        return isNewerName(info.version, currentVersion)
    }

    /** Downloads the release APK with progress, then opens the system installer. */
    fun downloadAndInstall() {
        val info = (_state.value as? State.Available)?.info ?: return
        _state.value = State.Downloading(0)
        scope.launch {
            runCatching {
                val dir = File(context.filesDir, "updates").apply { mkdirs() }
                val out = File(dir, "owntv-update.apk")
                out.delete() // never build on top of a previous half-download
                val request = Request.Builder().url(info.apkUrl).header("User-Agent", "OwnTV").build()
                try {
                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) throw DownloadHttpException(resp.code)
                        val body = resp.body
                        val total = body.contentLength()
                        // The APK is written once here and copied again into the install session, so
                        // the download needs room for two of it. Checking up front turns a silent short
                        // write — which reaches the installer as "App not installed" — into a real message.
                        if (total > 0 && dir.usableSpace < total * 2) throw NotEnoughSpaceException(total * 2)
                        var copied = 0L
                        body.byteStream().use { input ->
                            out.outputStream().use { output ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    output.write(buf, 0, n)
                                    copied += n
                                    if (total > 0) _state.value = State.Downloading((copied * 100 / total).toInt())
                                }
                            }
                        }
                        if (copied == 0L) throw EmptyDownloadException()
                        if (total > 0 && copied != total) {
                            throw DamagedDownloadException("truncated: got $copied of $total bytes")
                        }
                    }
                    verifyApk(out)
                    install(out)
                } catch (e: Throwable) {
                    out.delete() // a bad file must not linger and be retried as-is
                    throw e
                }
                _state.value = State.Available(info) // dialog stays sane if the user cancels install
            }.onFailure { error ->
                Log.w(TAG, "update download failed: ${error.message}", error)
                val failure = if (error is InstallException) Failure.Install else failureFor(error, checking = false)
                _state.value = State.Failed(failure, retryInfo = info)
            }
        }
    }

    /**
     * Refuses anything the system cannot read back as this app's package. A truncated or corrupted
     * APK is otherwise only rejected by the installer itself, which reports nothing to us and shows
     * the user a bare "App not installed" — the failure this whole guard exists to explain.
     */
    private fun verifyApk(apk: File) {
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: throw DamagedDownloadException("unparseable APK (${apk.length()} bytes)")
        if (info.packageName != context.packageName) {
            throw DamagedDownloadException("wrong package ${info.packageName}")
        }
    }

    /**
     * Installs through [PackageInstaller] rather than an `ACTION_VIEW` intent on a FileProvider URI.
     * Two reasons: the session reads the APK from our own storage (no cross-app URI grant to go wrong
     * on older TV installers), and its status broadcast carries the real reason a package was refused
     * — insufficient storage, a signature conflict, a bad file — instead of the system's silent
     * "App not installed".
     */
    private fun install(apk: File) {
        registerInstallReceiver()
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = try {
            installer.createSession(params)
        } catch (e: IOException) {
            throw InstallException(e)
        }
        installer.openSession(sessionId).use { session ->
            session.openWrite(SESSION_ENTRY, 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            val intent = Intent(INSTALL_STATUS_ACTION).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            session.commit(PendingIntent.getBroadcast(context, 0, intent, flags).intentSender)
        }
        apk.delete() // the session owns its own copy now
    }

    /** Registered once, for the app's lifetime: the confirmation round trip outlives any one call. */
    private fun registerInstallReceiver() {
        if (installReceiverRegistered) return
        installReceiverRegistered = true
        ContextCompat.registerReceiver(
            context,
            installStatusReceiver,
            IntentFilter(INSTALL_STATUS_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private var installReceiverRegistered = false

    private val installStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            when (status) {
                // The user still has to approve the install — this is the system prompt, not a failure.
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    if (confirm == null) {
                        // The session is committed but there is no prompt activity to open. Without
                        // this branch the state machine stayed on the download bar at 100% forever
                        // (measured 2026-11-16 on an S22: "hangs on pending"). Fail so Retry can run
                        // the whole flow again — by then the unknown-sources grant is usually in.
                        Log.w(TAG, "install pending but the system gave no prompt intent")
                        pendingConfirmIntent = null
                        _state.value = State.Failed(Failure.Install)
                        return
                    }
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    pendingConfirmIntent = confirm
                    runCatching { context.startActivity(confirm) }
                        .onSuccess { _state.value = State.AwaitingUserAction }
                        .onFailure { e ->
                            Log.w(TAG, "install prompt could not be shown: $e")
                            pendingConfirmIntent = null
                            _state.value = State.Failed(Failure.Install)
                        }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    Log.i(TAG, "update installed"); pendingConfirmIntent = null
                }
                // Declining the system prompt is a choice, not an error — leave the dialog as it was.
                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    Log.i(TAG, "install cancelled by user")
                    pendingConfirmIntent = null
                }
                else -> {
                    Log.w(TAG, "install refused status=$status message=$message")
                    pendingConfirmIntent = null
                    _state.value = State.Failed(Failure.InstallRejected(message?.takeIf { it.isNotBlank() }))
                }
            }
        }
    }

    private var pendingConfirmIntent: Intent? = null

    /**
     * Re-launches the system install prompt for a session still waiting on the user's OK (the
     * [State.AwaitingUserAction] state). The prompt activity can be missed — backgrounded behind a
     * lock screen, dismissed by a stray back press — and the session then sits committed forever;
     * the button that lands here is its second chance. A stale prompt that refuses to open fails
     * the install instead of hanging, so the sheet's Retry can rerun the whole flow cleanly.
     */
    fun reopenPrompt() {
        if (_state.value !is State.AwaitingUserAction) return
        val confirm = pendingConfirmIntent ?: run {
            pendingConfirmIntent = null
            _state.value = State.Failed(Failure.Install)
            return
        }
        runCatching { context.startActivity(confirm) }.onFailure { e ->
            Log.w(TAG, "install prompt could not be reopened: $e")
            pendingConfirmIntent = null
            _state.value = State.Failed(Failure.Install)
        }
    }

    /** Retries the failed phase without losing a successfully resolved release asset. */
    fun retry() {
        val failed = _state.value as? State.Failed ?: return
        val info = failed.retryInfo
        if (info != null && failed.failure !is Failure.CheckHttp && failed.failure !is Failure.NoCompatibleApk &&
            failed.failure !is Failure.InvalidReleaseResponse && failed.failure !is Failure.CheckNetwork
        ) {
            _state.value = State.Available(info)
            downloadAndInstall()
        } else {
            check()
        }
    }

    fun reset() {
        pendingConfirmIntent = null
        if (_state.value !is State.Downloading) _state.value = State.Idle
    }

    /** Numeric segment-wise compare: "1.10.0" > "1.9.3"; non-numeric junk compares as 0. */
    private fun isNewerName(remote: String, local: String): Boolean {
        val r = remote.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private class InstallException(cause: Throwable) : IOException(cause)

    companion object {
        private const val TAG = "UpdateManager"
        private const val SESSION_ENTRY = "owntv-update"
        private const val INSTALL_STATUS_ACTION = "tv.own.owntv.UPDATE_INSTALL_STATUS"
    }
}
