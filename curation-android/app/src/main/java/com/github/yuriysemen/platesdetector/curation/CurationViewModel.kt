package com.github.yuriysemen.platesdetector.curation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
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

/** Vehicle-type classification removed — every box is this one class, assigned automatically. */
const val LICENSE_PLATE_CLASS_ID = 0

/** The current item's boxes and their class per box (always [LICENSE_PLATE_CLASS_ID] now, except
 *  for an already-decided item from before vehicle-type classification was removed). */
data class ReviewBoxes(val boxes: List<YoloBox>, val classes: List<Int?>) {
    val allClassified: Boolean get() = boxes.isNotEmpty() && boxes.indices.all { classes.getOrNull(it) != null }
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

    /** REQ-025 vehicle-category list (S3 with bundled fallback), loaded once per VM. */
    var categories by mutableStateOf<VehicleCategories?>(null)
        private set

    /** Set when the refresh/session token has died — CurationApp routes back to sign-in. */
    var sessionExpired by mutableStateOf(false)
        private set

    private var npJob: Job? = null
    private var ipJob: Job? = null
    private var doneJob: Job? = null
    private var heartbeatJob: Job? = null
    private var manifestSaveJob: Job? = null

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

    private suspend fun ensureCategories(): VehicleCategories =
        categories ?: repo.fetchCategories().also { categories = it }

    /**
     * The category list to classify/display against for the OPEN session: its own embedded
     * snapshot (taken at Start, REQ-025 offline-completion fix) when present, else the app-level
     * fetch/bundled fallback — only reached for a manifest written before the snapshot existed.
     */
    val sessionCategories: VehicleCategories? get() = session?.manifest?.categories ?: categories

    fun startReview(upload: UploadRef) {
        if (busy != null) return
        viewModelScope.launch {
            busy = BusyState("Downloading ${upload.filename}…", 0, 3)
            val ok = runCatchingSession {
                val cats = ensureCategories()
                val manifest = repo.startPackage(upload, auth.currentUserEmail(), cats) { c, t ->
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
                ensureCategories()
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
        viewModelScope.launch { flushManifestSave() }
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

    // ── Box add/delete. Vehicle-type classification removed (was REQ-025) — every
    // box is the single "license_plate" class (id 0) automatically, not curator-chosen. ──

    /** Boxes for the current item, all implicitly class 0 (license_plate). Decided items show
     *  their baked-in classes, which may still be non-zero for a package reviewed before this. */
    fun currentBoxes(): ReviewBoxes {
        val s = session ?: return ReviewBoxes(emptyList(), emptyList())
        val item = s.currentItem
        return if (item.status == ItemStatus.ACCEPTED) {
            val boxes = YoloLabel.parse(item.labelContent)
            ReviewBoxes(boxes, boxes.map { it.classId })
        } else {
            val text = item.workingLabel
                ?: item.labelContent
                ?: s.current.labelFile.takeIf { it.exists() }?.readText()
            val boxes = YoloLabel.parse(text)
            ReviewBoxes(boxes, boxes.indices.map { LICENSE_PLATE_CLASS_ID })
        }
    }

    fun addBox(box: YoloBox) {
        val s = session ?: return
        if (s.currentItem.status != ItemStatus.PENDING) return
        val rb = currentBoxes()
        val boxes = rb.boxes + box
        val classes = rb.classes + LICENSE_PLATE_CLASS_ID
        val updated = s.manifest.withItemBoxes(s.index, YoloLabel.format(boxes, classes), classes)
        session = s.copy(manifest = updated)
        saveManifestDebounced(updated)
    }

    /** Replace one box's geometry after a move/resize drag (REQ-024); class id is unaffected. */
    fun moveBox(boxIndex: Int, updated: YoloBox) {
        val s = session ?: return
        if (s.currentItem.status != ItemStatus.PENDING) return
        val rb = currentBoxes()
        if (boxIndex !in rb.boxes.indices) return
        val boxes = rb.boxes.toMutableList().also { it[boxIndex] = updated }
        val newManifest = s.manifest.withItemBoxes(s.index, YoloLabel.format(boxes, rb.classes), rb.classes)
        session = s.copy(manifest = newManifest)
        saveManifestDebounced(newManifest)
    }

    fun deleteBox(boxIndex: Int) {
        val s = session ?: return
        if (s.currentItem.status != ItemStatus.PENDING) return
        val rb = currentBoxes()
        if (boxIndex !in rb.boxes.indices) return
        val boxes = rb.boxes.filterIndexed { i, _ -> i != boxIndex }
        val classes = rb.classes.filterIndexed { i, _ -> i != boxIndex }
        val working = if (boxes.isEmpty()) null else YoloLabel.format(boxes, classes)
        val updated = s.manifest.withItemBoxes(s.index, working, classes)
        session = s.copy(manifest = updated)
        saveManifestDebounced(updated)
    }

    fun canAcceptCurrent(): Boolean =
        session?.currentItem?.status == ItemStatus.PENDING && currentBoxes().allClassified

    fun accept() {
        val rb = currentBoxes()
        if (!rb.allClassified) return
        decide(ItemStatus.ACCEPTED, YoloLabel.format(rb.boxes, rb.classes), null)
    }

    fun reject() = decide(ItemStatus.REJECTED, null, null)

    /** Accept/reject the current item, persist the manifest, advance to the next pending item. */
    private fun decide(status: ItemStatus, labelContent: String?, reason: String?) {
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
        manifestSaveJob?.cancel()
        viewModelScope.launch { runCatchingSession { repo.putManifest(updated) } }
    }

    /** Revert the current item's Accept/Reject back to PENDING so it can be re-edited and
     *  re-decided — stays on this item (unlike [decide], which advances to the next pending one).
     *  Clears the old decidedBy/decidedAt/labelContent/reason; workingLabel (the curator's box
     *  edits) is untouched, so re-opening for edit shows exactly what was there before. */
    fun undecide() {
        val s = session ?: return
        if (s.currentItem.status == ItemStatus.PENDING) return
        val updated = s.manifest.withDecision(s.index, ItemStatus.PENDING)
        session = s.copy(manifest = updated)
        manifestSaveJob?.cancel()
        viewModelScope.launch { runCatchingSession { repo.putManifest(updated) } }
    }

    private fun saveManifestDebounced(m: CurationManifest) {
        manifestSaveJob?.cancel()
        manifestSaveJob = viewModelScope.launch {
            delay(MANIFEST_SAVE_DEBOUNCE_MS)
            runCatchingSession { repo.putManifest(m) }
        }
    }

    private suspend fun flushManifestSave() {
        manifestSaveJob?.cancel()
        session?.manifest?.let { runCatchingSession { repo.putManifest(it) } }
    }

    // ── Complete / release ────────────────────────────────────────────────

    fun complete(manifest: CurationManifest, onDone: () -> Unit) {
        if (busy != null) return
        viewModelScope.launch {
            busy = BusyState("Uploading ${manifest.filename}…", 0, 1)
            val ok = runCatchingSession {
                // Always the package's own snapshot when it has one — never a freshly-fetched
                // list, so an offline (or delayed) Complete can't mix a package's labels with a
                // different category-list version's nc/names. ensureCategories() (network-or-
                // bundled) is only a best-effort fallback for a pre-fix manifest with no snapshot.
                val cats = manifest.categories ?: ensureCategories()
                repo.completePackage(manifest, cats) { c, t ->
                    busy = BusyState("Uploading ${manifest.filename}…", c, t)
                }
            }
            busy = null
            if (ok) {
                // Completing from inside an open ReviewScreen session (REQ-035): the manifest
                // completePackage() just deleted from curation/ must not get resurrected by a
                // stale heartbeat/flush, so drop the session directly rather than relying on the
                // caller to separately call closeSession().
                if (session?.manifest?.packageId == manifest.packageId) {
                    heartbeatJob?.cancel()
                    session = null
                }
                onDone(); refreshInProgress(); refreshDone()
            }
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
        } catch (e: CancellationException) {
            // Always rethrow — this fires when a newer debounced save (or session close) cancels
            // an older one still in flight, which is normal coroutine cancellation, not a failure.
            // Catching it here without rethrowing broke structured concurrency and surfaced a
            // confusing "StandaloneCoroutine was cancelled" error banner for something that
            // wasn't actually wrong.
            throw e
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
        manifestSaveJob?.cancel()
    }

    companion object {
        /** How often the review screen re-PUTs the manifest as a keep-alive. */
        const val HEARTBEAT_MS = 3 * 60 * 1000L
        /** Coalesce rapid box-class picks into one manifest write. */
        const val MANIFEST_SAVE_DEBOUNCE_MS = 800L
    }
}
