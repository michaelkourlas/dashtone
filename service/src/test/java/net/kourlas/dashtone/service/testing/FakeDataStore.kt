package net.kourlas.dashtone.service.testing

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [DataStore] for tests -- avoids the real file-backed implementation entirely, which
 * sidesteps a genuine androidx.datastore-core limitation on Windows: its JVM [FileStorage]
 * backend writes to a temp file and renames it over the target, and a second write to the same
 * DataStore instance can fail that rename with "Unable to rename... multiple instances of
 * DataStore", even though there's only ever one instance -- something (most likely Windows
 * Defender's real-time scan of the file the first write just created) holds a transient handle
 * that blocks the second write's replace. [updateData] is mutex-guarded to match [DataStore]'s
 * own single-writer-at-a-time contract.
 */
class FakeDataStore<T>(initial: T) : DataStore<T> {
    private val state = MutableStateFlow(initial)
    private val mutex = Mutex()

    override val data get() = state

    override suspend fun updateData(transform: suspend (t: T) -> T): T = mutex.withLock {
        val updated = transform(state.value)
        state.value = updated
        updated
    }
}
