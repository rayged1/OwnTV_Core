package tv.own.owntv.player

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.network.HttpClient
import java.io.File
import java.util.concurrent.Executors

/**
 * Small rolling on-disk history of playback failures (last [MAX] entries), so a user who dismissed
 * the error screen — or whose app restarted — can still read/report what happened from
 * More → "Error log". Most TV users can't pull logcat; this is their only record.
 *
 * Plain JSON file in filesDir; all IO runs on a single background thread so the player's error
 * path never blocks. Timestamps are wall-clock (for display), unlike the monotonic diagnostics.
 */
object PlaybackErrorLog {
    private const val MAX = 25
    private const val FILE_NAME = "playback_errors.json"
    private const val EXPORT_NAME = "owntv-playback-report.txt"

    /** What an entry is. The log used to hold hard failures only, which is exactly why a "the picture
     *  judders / the sound drifts" report produced an empty log. */
    enum class Kind {
        /** Playback stopped with an error screen. */
        ERROR,

        /** Playback carried on, but something notable happened — a decode rescue, an engine handoff,
         *  the audio safety net firing. These are the events a quality complaint needs. */
        EVENT,

        /** The user pressed "Report this stream" in the player: a full snapshot of a *working* stream. */
        REPORT,
        ;

        companion object {
            fun from(name: String?): Kind = entries.firstOrNull { it.name.equals(name, true) } ?: ERROR
        }
    }

    data class Entry(
        val atMs: Long,
        val engine: String,
        val live: Boolean,
        val reason: PlayerFailureReason?,
        /** Legacy single-string diagnostic, retained for old files only. */
        val spec: String?,
        val raw: String?,
        val model: String,
        val android: String,
        /** Pre-i18n English prose that didn't match any known [PlayerFailureReason] or legacy mapping. */
        val legacyReason: String? = null,
        /** Structured fields written by current builds. */
        val codec: String? = null,
        val resolution: String? = null,
        val decoderKind: String? = null,
        val decoderName: String? = null,
        val decoderHardware: Boolean = false,
        val decoderDirect: Boolean = false,
        val kind: Kind = Kind.ERROR,
    ) {
        /** Converts new stable fields back to the semantic presentation model. */
        fun mediaSpec(): MediaSpec? {
            val kind = decoderKind?.let { runCatching { DecoderKind.valueOf(it) }.getOrNull() }
            val decoder = when (kind) {
                DecoderKind.HARDWARE -> DecoderSpec.Hardware(direct = decoderDirect)
                DecoderKind.SOFTWARE -> DecoderSpec.Software(gpu = !decoderDirect)
                DecoderKind.NAMED, null -> (decoderName ?: decoderKind)?.let {
                    // Unknown future decoder ids remain visible as raw names rather than being
                    // silently dropped from the user's diagnostic history.
                    DecoderSpec.Named(it, hardware = decoderHardware, direct = decoderDirect)
                }
            }
            val structured = MediaSpec(codec = codec, resolution = resolution, decoder = decoder)
                .takeIf { it.codec != null || it.resolution != null || it.decoder != null }
            return structured ?: legacyMediaSpec()
        }

        /**
         * Reads the pre-structured `spec` format without making it the new persistence contract.
         * Known decoder forms are rendered through the current localized mapper; an unrecognised
         * legacy string remains available through the Settings screen's explicit raw fallback.
         */
        private fun legacyMediaSpec(): MediaSpec? {
            val fields = spec?.split(" • ")?.map(String::trim)?.filter(String::isNotEmpty) ?: return null
            if (fields.isEmpty()) return null
            val decoderToken = fields.last()
            val decoder = when {
                decoderToken == "hardware" || decoderToken == "hardware:direct" ->
                    DecoderSpec.Hardware(direct = decoderToken.endsWith(":direct"))
                decoderToken == "software" || decoderToken == "software:gpu" ->
                    DecoderSpec.Software(gpu = decoderToken.endsWith(":gpu"))
                decoderToken.endsWith(":hardware") ->
                    DecoderSpec.Named(decoderToken.removeSuffix(":hardware"), hardware = true)
                else -> null
            }
            val technical = if (decoder != null) fields.dropLast(1) else fields
            return MediaSpec(
                codec = technical.getOrNull(0),
                resolution = technical.getOrNull(1),
                decoder = decoder,
            ).takeIf { it.codec != null || it.resolution != null || it.decoder != null }
        }
    }

    private const val TAG = "PlaybackErrorLog"

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "owntv-errorlog").apply { isDaemon = true } }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** Append an error (fire-and-forget; trims to the newest [MAX]). */
    fun log(context: Context, engine: String, live: Boolean, info: ErrorInfo) {
        if (info.reason == null && info.raw == null) return // nothing useful to keep
        append(
            context,
            Entry(
                atMs = System.currentTimeMillis(),
                engine = engine,
                live = live,
                reason = info.reason,
                spec = null,
                raw = info.raw?.let(HttpClient::redactUrl),
                model = deviceModel(),
                android = androidVersion(),
                codec = info.spec?.codec,
                resolution = info.spec?.resolution,
                decoderKind = info.spec?.decoder?.storageKind,
                decoderName = (info.spec?.decoder as? DecoderSpec.Named)?.value,
                decoderHardware = info.spec?.decoder?.isHardware == true,
                decoderDirect = info.spec?.decoder?.isDirect == true,
            ),
        )
    }

    /**
     * Record something that happened *without* stopping playback — a decode rescue, an engine handoff,
     * the stereo safety net. [reason] is stable semantic state; [detail] is the technical line under it.
     *
     * This is the other half of the error log: a user whose picture judders or whose sound drifts has no failure to
     * report, so the log stayed empty and every report became a guessing game. Callers must keep these
     * rare — one per genuine event, never per frame or per retry tick.
     */
    fun event(context: Context, engine: String, live: Boolean, reason: PlayerFailureReason, detail: String? = null) {
        append(
            context,
            Entry(
                atMs = System.currentTimeMillis(),
                engine = engine,
                live = live,
                reason = reason,
                spec = null,
                raw = detail?.let(HttpClient::redactUrl),
                model = deviceModel(),
                android = androidVersion(),
                kind = Kind.EVENT,
            ),
        )
    }

    /** The player's "Report this stream" action: a snapshot of a stream that is playing right now. */
    fun report(context: Context, engine: String, live: Boolean, title: String?, snapshot: String) {
        append(
            context,
            Entry(
                atMs = System.currentTimeMillis(),
                engine = engine,
                live = live,
                reason = PlayerFailureReason.STREAM_REPORT,
                spec = title?.let(HttpClient::redactUrl),
                raw = snapshot.let(HttpClient::redactUrl),
                model = deviceModel(),
                android = androidVersion(),
                kind = Kind.REPORT,
            ),
        )
    }

    private fun append(context: Context, entry: Entry) {
        val appContext = context.applicationContext
        io.execute {
            runCatching {
                // A truncated or corrupt file must not silence logging for good: the read used to throw
                // inside the same runCatching as the write, so every later error was swallowed too and the
                // Settings log stayed frozen on whatever it held. Start a fresh log instead.
                val entries = runCatching { readSync(appContext) }.getOrElse {
                    android.util.Log.w(TAG, "playback error log unreadable — starting a new one", it)
                    emptyList()
                }.toMutableList()
                entries.add(entry)
                writeSync(appContext, entries.takeLast(MAX))
            }
        }
    }

    private fun deviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    private fun androidVersion(): String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    /** Newest-first list for the Settings viewer. Safe to call from a coroutine on Dispatchers.IO. */
    fun read(context: Context): List<Entry> = runCatching { readSync(context).reversed() }.getOrDefault(emptyList())

    /**
     * Clear the log. Queued on the same single thread the appends use, so an append that had already read
     * the entries cannot write them straight back after the delete — then waited for, so a viewer
     * re-reading immediately afterwards still sees an empty log.
     */
    fun clear(context: Context) {
        val appContext = context.applicationContext
        val done = java.util.concurrent.CountDownLatch(1)
        val queued = runCatching {
            io.execute {
                runCatching { file(appContext).delete() }
                done.countDown()
            }
        }.isSuccess
        if (!queued) { // executor gone — nothing is appending either
            runCatching { file(appContext).delete() }
            return
        }
        runCatching { done.await(2, java.util.concurrent.TimeUnit.SECONDS) }
    }

    private fun readSync(context: Context): List<Entry> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val persistedReason = parsePersistedReason(o.optString("reason").takeIf { it.isNotEmpty() })
            Entry(
                atMs = o.optLong("atMs"),
                engine = o.optString("engine"),
                live = o.optBoolean("live"),
                reason = persistedReason.semantic,
                legacyReason = persistedReason.legacyText,
                spec = o.optString("spec").takeIf { it.isNotEmpty() }?.let(HttpClient::redactUrl),
                raw = o.optString("raw").takeIf { it.isNotEmpty() }?.let(HttpClient::redactUrl),
                model = o.optString("model"),
                android = o.optString("android"),
                codec = o.optString("codec").takeIf { it.isNotEmpty() },
                resolution = o.optString("resolution").takeIf { it.isNotEmpty() },
                decoderKind = o.optString("decoderKind").takeIf { it.isNotEmpty() },
                decoderName = o.optString("decoderName").takeIf { it.isNotEmpty() },
                decoderHardware = o.optBoolean("decoderHardware"),
                decoderDirect = o.optBoolean("decoderDirect"),
                // Written since the diagnostics phase; anything older is a hard error by definition.
                kind = Kind.from(o.optString("kind").takeIf { it.isNotEmpty() }),
            )
        }
    }

    private fun writeSync(context: Context, entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("atMs", e.atMs)
                    .put("engine", e.engine)
                    .put("live", e.live)
                    // Preserve an unknown pre-i18n reason when an old file is rewritten after a new
                    // failure. Known legacy prose is upgraded to the stable enum name.
                    .put("reason", e.persistedReasonValue())
                    // `spec` remains for backward compatibility with pre-structured entries.
                    .put("spec", e.spec ?: "")
                    .put("codec", e.codec ?: "")
                    .put("resolution", e.resolution ?: "")
                    .put("decoderKind", e.decoderKind ?: "")
                    .put("decoderName", e.decoderName ?: "")
                    .put("decoderHardware", e.decoderHardware)
                    .put("decoderDirect", e.decoderDirect)
                    .put("raw", e.raw ?: "")
                    .put("model", e.model)
                    .put("android", e.android)
                    .put("kind", e.kind.name),
            )
        }
        file(context).writeText(arr.toString())
    }

    /**
     * Write the whole log — plus the live diagnostics ring, when it has anything — to the public
     * **Download** folder and return its user-visible path. Android 10+ goes through MediaStore, which
     * needs no storage permission and creates the standard folder when necessary; Android 8–9 creates
     * the directory directly. Runs synchronously on the caller's IO dispatcher; the file is a few kB.
     */
    fun export(context: Context): String? = runCatching {
        val appContext = context.applicationContext
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        val live = LiveDiagnosticsLog.snapshot()
        val liveLines = live.lines().filter { it.isNotBlank() }
        // Self-describing window: the ring's first and last stamp say WHICH session a follow-up
        // export carries (Ray, 2026-11-17 — exports kept being mistaken for fresher than they are).
        val covered: String? = when {
            liveLines.isEmpty() -> null
            else -> "Session covered: ${liveLines.first().take(8)}–${liveLines.last().take(8)} device clock"
        }
        val text = buildString {
            appendLine("OwnTV playback report")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Exported ${stamp.format(java.util.Date())}")
            covered?.let(::appendLine)
            appendLine()
            // The crash comes first: it is the only part of this report that survives the process
            // dying, and it is the reason most people are asked to export at all.
            tv.own.owntv.core.util.CrashRecorder.read(appContext)?.let {
                appendLine("--- last crash ---")
                appendLine(it)
                appendLine()
            }
            read(appContext).forEach { e ->
                appendLine("[${stamp.format(java.util.Date(e.atMs))}] ${e.kind} · ${e.engine} · ${if (e.live) "live" else "vod"}")
                e.reason?.let { appendLine("  reason: $it") }
                e.spec?.let { appendLine("  spec  : $it") }
                e.raw?.let { appendLine("  raw   : $it") }
                appendLine()
            }
            if (live.isNotBlank()) {
                appendLine("--- live diagnostics (most recent) ---")
                appendLine(live)
            }
        }
        writeToDownloads(appContext, text)
    }.getOrNull()

    private fun writeToDownloads(context: Context, text: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { return writeToMediaStoreDownloads(context, text) }
        }
        runCatching {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            check(downloads.exists() || downloads.mkdirs()) { "Could not create the Download folder" }
            File(downloads, EXPORT_NAME).writeText(text)
            return "${Environment.DIRECTORY_DOWNLOADS}/$EXPORT_NAME"
        }
        // Android 8–9 without the legacy storage permission: the public Download folder simply isn't
        // writable, and the export failed with nothing to show for it. The app's own external files
        // directory needs no permission and is still reachable over USB / a file manager, so the report
        // lands there and the returned path names where it actually is.
        val fallbackDir = context.getExternalFilesDir(null) ?: context.filesDir
        val fallback = File(fallbackDir, EXPORT_NAME)
        fallback.writeText(text)
        return fallback.absolutePath
    }

    /**
     * Scoped-storage writer. Reuses OwnTV's existing report instead of creating `(1)`, `(2)`, … files.
     *
     * `@RequiresApi` states what the single caller above already guarantees with its `SDK_INT >= Q`
     * check. It is documentation that lint can read: AGP 9.4's lint no longer follows that guard
     * across the call boundary and reported the `MediaStore.Downloads` collection as unguarded. The
     * code was always correct; only the proof was implicit.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    @android.annotation.SuppressLint("InlinedApi")
    private fun writeToMediaStoreDownloads(context: Context, text: String): String {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/"
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val existing = runCatching {
            resolver.query(
                collection,
                projection,
                selection,
                arrayOf(EXPORT_NAME, relativePath),
                "${MediaStore.MediaColumns._ID} DESC",
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    ContentUris.withAppendedId(collection, cursor.getLong(0))
                } else {
                    null
                }
            }
        }.getOrNull()

        if (existing != null) {
            val replaced = runCatching {
                resolver.openOutputStream(existing, "wt")?.use { it.write(text.toByteArray()) }
                    ?: error("Could not open the existing playback report")
            }.isSuccess
            if (replaced) return "$relativePath$EXPORT_NAME"
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, EXPORT_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("Could not create the playback report")
        try {
            resolver.openOutputStream(uri, "w")?.use { it.write(text.toByteArray()) }
                ?: error("Could not open the playback report")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        return "$relativePath$EXPORT_NAME"
    }
}

private val DecoderSpec.storageKind: String
    get() = when (this) {
        is DecoderSpec.Hardware -> DecoderKind.HARDWARE.name
        is DecoderSpec.Software -> DecoderKind.SOFTWARE.name
        is DecoderSpec.Named -> DecoderKind.NAMED.name
    }

private val DecoderSpec.isHardware: Boolean
    get() = when (this) {
        is DecoderSpec.Hardware -> true
        is DecoderSpec.Software -> false
        is DecoderSpec.Named -> hardware
    }

private val DecoderSpec.isDirect: Boolean
    get() = when (this) {
        is DecoderSpec.Hardware -> direct
        is DecoderSpec.Software -> !gpu
        is DecoderSpec.Named -> direct
    }

internal data class PersistedPlaybackReason(
    val semantic: PlayerFailureReason?,
    val legacyText: String?,
)

/**
 * Reads both generations of the JSON `reason` field. Current entries contain an enum name; old
 * entries contain controlled English prose. Known prose becomes semantic state so it localizes in
 * the current UI, while unknown prose remains visible and survives the next log rewrite.
 */
internal fun parsePersistedReason(value: String?): PersistedPlaybackReason {
    val key = value?.takeIf { it.isNotBlank() }
        ?: return PersistedPlaybackReason(semantic = null, legacyText = null)
    val semantic = runCatching { PlayerFailureReason.valueOf(key) }.getOrNull()
        ?: LEGACY_REASON_MAP[key]
    return PersistedPlaybackReason(
        semantic = semantic,
        legacyText = key.takeIf { semantic == null },
    )
}

internal fun PlaybackErrorLog.Entry.persistedReasonValue(): String =
    reason?.name ?: legacyReason.orEmpty()

/** Maps pre-i18n English prose stored in the `reason` field to the current [PlayerFailureReason]. */
private val LEGACY_REASON_MAP: Map<String, PlayerFailureReason> = mapOf(
    "Hardware video decoder is busy or can't handle this stream" to PlayerFailureReason.DECODER_BUSY,
    "Hardware video decoder error — transient, try again" to PlayerFailureReason.DECODER_TRANSIENT,
    "This device's decoder doesn't support this video format/profile (e.g. HEVC 10-bit)" to PlayerFailureReason.UNSUPPORTED_VIDEO,
    "Device ran out of memory for the decoder — try closing other apps" to PlayerFailureReason.DECODER_MEMORY,
    "DRM / secure-decoder error" to PlayerFailureReason.DRM,
    "Provider blocked the connection — too many streams at once (HTTP 509)" to PlayerFailureReason.HTTP_509,
    "Provider denied access (HTTP 403) — credentials, subscription, or IP block" to PlayerFailureReason.HTTP_403,
    "Provider rejected your login (HTTP 401)" to PlayerFailureReason.HTTP_401,
    "Stream not found on the provider (HTTP 404)" to PlayerFailureReason.HTTP_404,
    "Provider rejected the request (HTTP 400) — bad or expired link" to PlayerFailureReason.HTTP_400,
    "Provider's SSL certificate is invalid or expired" to PlayerFailureReason.SSL,
    "Stream format not recognized (bad or partial stream)" to PlayerFailureReason.FORMAT,
    "Network problem reaching the provider" to PlayerFailureReason.NETWORK,
    "Audio output error — the device couldn't play this audio format" to PlayerFailureReason.AUDIO,
)
