package com.github.yuriysemen.platesdetector.curation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Per-tab loading state. */
sealed interface TabState<out T> {
    data object Loading : TabState<Nothing>
    data class Error(val message: String, val sessionExpired: Boolean = false) : TabState<Nothing>
    data class Data<T>(val items: List<T>) : TabState<T>
}

/** Blocking full-screen progress (download/unzip, complete, release). */
data class BusyState(val label: String, val current: Int, val total: Int)

/** An open review session for one In-Progress package. */
data class ReviewSession(
    val manifest: CurationManifest,
    val items: List<CachedItem>,
    val index: Int,
) {
    val current: CachedItem get() = items[index]
    val currentItem: ManifestItem get() = manifest.items[index]
}

class CurationViewModel(
    private val repo: CurationRepository,
    private val auth: CuratorAuthManager,
) : ViewModel() {

    var notProcessed by mutableStateOf<TabState<UploadRef>>(TabState.Loading)
        private set
    var inProgress by mutableStateOf<TabState<InProgressItem>>(TabState.Loading)
        private set
    var done by mutableStateOf<TabState<DoneManifest>>(TabState.Loading)
        private set

    var busy by mutableStateOf<BusyState?>(null)
        private set
    var session by mutableStateOf<ReviewSession?>(null)
        private set
    var errorBanner by mutableStateOf<String?>(null)
        private set

    /** Set when the refresh/session token has died — CurationApp routes back to sign-in. */
    var sessionExpired by mutableStateOf(false)
        private set

    private var npJob: Job? = null
    private var ipJob: Job? = null
    private var doneJob: Job? = null
    private var heartbeatJob: Job? = null

    fun clearErrorBanner() { errorBanner = null }

    fun refreshAll() {
        refreshNotProcessed(); refreshInProgress(); refreshDone()
    }

    fun refreshNotProcessed() {
        if (npJob?.isActive == true) return
        notProcessed = TabState.Loading
        npJob = viewModelScope.launch { notProcessed = load { repo.listNotProcessed() } }
    }

    fun refreshInProgress() {
        if (ipJob?.isActive == true) return
        inProgress = TabState.Loading
        ipJob = viewModelScope.launch { inProgress = load { repo.listInProgress() } }
    }

    fun refreshDone() {
        if (doneJob?.isActive == true) return
        done = TabState.Loading
        doneJob = viewModelScope.launch { done = load { repo.listDone() } }
    }

    fun startReview(upload: UploadRef) {
        if (busy != null) return
        viewModelScope.launch {
            busy = BusyState("Downloading ${upload.filename}…", 0, 3)
            val ok = runCatchingSession {
                val manifest = repo.startPackage(upload, auth.currentUserEmail()) { c, t ->
                    busy = BusyState("Preparing ${upload.filename}…", c, t)
                }
                val items = repo.ensureLocalCopy(manifest) { _, _ -> }
                openSession(manifest, items)
            }
            busy = null
            if (ok) { refreshNotProcessed(); refreshInProgress() }
        }
    }

    /** Open (or take over, if stale) an In-Progress package. Touches the manifest so its S3
     *  `LastModified` — the "last activity" clock — resets on open. */
    fun openInProgress(manifest: CurationManifest) {
        if (busy != null) return
        viewModelScope.launch {
            busy = BusyState("Opening ${manifest.filename}…", 0, 2)
            runCatchingSession {
                val items = repo.ensureLocalCopy(manifest) { c, t ->
                    busy = BusyState("Downloading ${manifest.filename}…", c, t)
                }
                repo.putManifest(manifest)
                openSession(manifest, items)
            }
            busy = null
        }
    }

    private fun openSession(manifest: CurationManifest, items: List<CachedItem>) {
        if (items.isEmpty()) { errorBanner = "Package ${manifest.filename} has no images."; return }
        val start = manifest.firstPendingIndex().let { if (it >= 0) it else 0 }
        session = ReviewSession(manifest, items, start.coerceIn(0, items.size - 1))
        startHeartbeat()
    }

    fun closeSession() {
        heartbeatJob?.cancel()
        session = null
        refreshInProgress()
    }

    /** Periodically re-PUTs the current manifest so a curator lingering on one hard image still
     *  counts as active (stale detection is based on the manifest's S3 `LastModified`). */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                val m = session?.manifest ?: break
                runCatching { repo.putManifest(m) }
            }
        }
    }

    fun navigate(delta: Int) {
        val s = session ?: return
        session = s.copy(index = (s.index + delta).coerceIn(0, s.items.size - 1))
    }

    fun jumpTo(index: Int) {
        val s = session ?: return
        if (index in s.items.indices) session = s.copy(index = index)
    }

    /** Accept/reject the current item, persist the manifest, advance to the next pending item. */
    fun decide(status: ItemStatus, labelContent: String?, reason: String?) {
        val s = session ?: return
        val updated = s.manifest.withDecision(
            s.index, status, labelContent, reason,
            decidedBy = auth.currentUserEmail(), decidedAt = nowIso(),
        )
        val nextPending = updated.items.withIndex()
            .firstOrNull { it.index > s.index && it.value.status == ItemStatus.PENDING }?.index
            ?: updated.firstPendingIndex()
        session = s.copy(
            manifest = updated,
            index = if (nextPending >= 0) nextPending else s.index,
        )
        viewModelScope.launch { runCatchingSession { repo.putManifest(updated) } }
    }

    fun complete(manifest: CurationManifest, onDone: () -> Unit) {
        if (busy != null) return
        viewModelScope.launch {
            busy = BusyState("Uploading ${manifest.filename}…", 0, 1)
            val ok = runCatchingSession {
                repo.completePackage(manifest) { c, t ->
                    busy = BusyState("Uploading ${manifest.filename}…", c, t)
                }
            }
            busy = null
            if (ok) { onDone(); refreshInProgress(); refreshDone() }
        }
    }

    fun release(manifest: CurationManifest, onDone: () -> Unit) {
        if (busy != null) return
        viewModelScope.launch {
            busy = BusyState("Releasing ${manifest.filename}…", 0, 1)
            val ok = runCatchingSession { repo.releasePackage(manifest.packageId) }
            busy = null
            if (ok) { onDone(); refreshInProgress(); refreshNotProcessed() }
        }
    }

    // ── error handling ─────────────────────────────────────────────────────

    private suspend fun <T> load(block: suspend () -> List<T>): TabState<T> =
        try {
            TabState.Data(block())
        } catch (e: SessionExpiredException) {
            auth.markSessionExpired(); sessionExpired = true
            TabState.Error("Session expired — please sign in again.", sessionExpired = true)
        } catch (e: Exception) {
            TabState.Error(describe(e))
        }

    private suspend fun runCatchingSession(block: suspend () -> Unit): Boolean =
        try {
            block(); true
        } catch (e: SessionExpiredException) {
            auth.markSessionExpired(); sessionExpired = true; false
        } catch (e: Exception) {
            errorBanner = describe(e); false
        }

    private suspend fun describe(e: Throwable): String {
        val base = e.message ?: e.javaClass.simpleName
        val stale = base.contains("not authorized", ignoreCase = true) &&
            runCatching { !auth.tokenHasCuratorRoleClaim() }.getOrDefault(false)
        return if (stale)
            "$base\n\nYour sign-in token predates this account's curator access — sign out and in again."
        else base
    }

    override fun onCleared() {
        heartbeatJob?.cancel()
    }

    companion object {
        /** How often the review screen re-PUTs the manifest as a keep-alive. */
        const val HEARTBEAT_MS = 3 * 60 * 1000L
    }
}
