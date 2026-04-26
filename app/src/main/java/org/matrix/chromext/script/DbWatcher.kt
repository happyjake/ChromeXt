package org.matrix.chromext.script

import android.os.FileObserver
import android.os.Handler
import android.os.HandlerThread
import org.matrix.chromext.Chrome
import org.matrix.chromext.utils.Log

// Watches the userscript SQLite file for external writes and reloads
// ScriptDbManager.scripts when one happens. Needed because the chromext-share
// Magisk module bind-mounts a single userscript DB across every host — when
// the action.sh sync (or any other host) writes to the master, we want the
// running host process to pick up the new scripts without a force-stop.
//
// inotify watches the inode behind the watched path. Bind-mounts share the
// same inode across paths, so a write through any path fires the watch.
//
// CLOSE_WRITE fires on every RW close even without actual writes — and
// SQLite opens RW even for reads, so using CLOSE_WRITE creates a self-feed
// loop where our own reload() retriggers itself. MODIFY only fires on real
// write(2) syscalls. Then a 250ms debounce coalesces multi-write commits.
object DbWatcher {
  private const val DEBOUNCE_MS = 250L

  @Volatile private var observer: FileObserver? = null
  private val thread by lazy { HandlerThread("DbWatcher").also { it.start() } }
  private val handler by lazy { Handler(thread.looper) }
  private val reloadJob =
      Runnable {
        Log.d("DbWatcher: reloading after debounce")
        runCatching { ScriptDbManager.reload() }
            .onFailure { Log.ex(it, "DbWatcher reload") }
      }

  fun start() {
    if (observer != null) return
    val ctx = runCatching { Chrome.getContext() }.getOrNull() ?: return
    val dbPath = ctx.getDatabasePath(ScriptDbHelper.DATABASE_NAME)
    if (!dbPath.exists()) {
      Log.d("DbWatcher: ${dbPath} not present yet, skipping")
      return
    }

    val watch =
        @Suppress("DEPRECATION")
        object : FileObserver(dbPath.absolutePath, MODIFY) {
          override fun onEvent(event: Int, path: String?) {
            if (event and MODIFY == 0) return
            handler.removeCallbacks(reloadJob)
            handler.postDelayed(reloadJob, DEBOUNCE_MS)
          }
        }
    watch.startWatching()
    observer = watch
    Log.i("DbWatcher: watching ${dbPath.absolutePath}")
  }
}
