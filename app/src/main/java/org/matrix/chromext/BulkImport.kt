package org.matrix.chromext

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import org.json.JSONObject
import org.matrix.chromext.script.Script
import org.matrix.chromext.script.ScriptDbHelper
import org.matrix.chromext.script.ScriptDbManager
import org.matrix.chromext.script.parseScript
import org.matrix.chromext.utils.Log

// Pulls a Violentmonkey WebDAV dump and bulk-inserts enabled scripts into the
// userscript DB. Runs entirely inside the host browser's process so no root
// or privileged I/O is needed on the device side. After insert, any existing
// row whose id isn't present in the enabled set is removed — making the
// WebDAV dump the source of truth (disable in VM → tap widget → gone here).
object BulkImport {
  const val ACTION = "org.matrix.chromext.BULK_IMPORT"
  private val hrefRegex = Regex("<[A-Za-z]*:?href>([^<]+)</")
  private val fileRegex = Regex("(vm(?:@|%40)2-[^/]+)$")

  @Volatile private var registered = false

  fun registerReceiver() {
    if (registered) return
    val ctx = Chrome.getContext()
    val receiver =
        object : BroadcastReceiver() {
          override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION) return
            val url = intent.getStringExtra("url") ?: return
            val config =
                JSONObject()
                    .put("url", url)
                    .put("user", intent.getStringExtra("user") ?: "")
                    .put("pass", intent.getStringExtra("pass") ?: "")
                    .toString()
            Log.i("BulkImport: received broadcast")
            run(config)
          }
        }
    val filter = IntentFilter(ACTION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    } else {
      @Suppress("UnspecifiedRegisterReceiverFlag") ctx.registerReceiver(receiver, filter)
    }
    registered = true
    Log.d("BulkImport: receiver registered for $ACTION")
  }

  fun run(configStr: String) {
    Chrome.IO.submit {
      runCatching { importAll(configStr) }.onFailure { Log.ex(it, "BulkImport failed") }
    }
  }

  // Per-file Last-Modified cache key prefix for the id-of-filename companion.
  private const val MTIME_PREFS = "chromext_bulk_mtime"
  private const val ID_SUFFIX = ".id"

  private fun importAll(configStr: String) {
    val cfg = JSONObject(configStr)
    val baseUrl = cfg.getString("url").let { if (it.endsWith("/")) it else "$it/" }
    val user = cfg.optString("user")
    val pass = cfg.optString("pass")
    val authHeader =
        if (user.isNotEmpty()) {
          val token = Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
          "Basic $token"
        } else null

    val ctx = Chrome.getContext()
    val mtimeCache = ctx.getSharedPreferences(MTIME_PREFS, Context.MODE_PRIVATE)

    Log.i("BulkImport: listing $baseUrl")
    val entries = listWebDav(baseUrl, authHeader)
    if (entries.isEmpty()) {
      Log.e("BulkImport: no vm@2-* entries at $baseUrl")
      return
    }
    Log.i("BulkImport: got ${entries.size} filenames")

    val scripts = mutableListOf<Script>()
    // name → (remote-mtime, parsed script id) for successful refreshes; flushed
    // to prefs after the insert so we can skip these files next run.
    val newMtimes = mutableMapOf<String, Pair<String, String>>()
    // Ids of files whose cached mtime matched remote — not re-fetched but still
    // present on the server, so they must survive the diff-based deletion pass.
    val unchangedIds = mutableSetOf<String>()
    var disabled = 0
    var badHeader = 0
    var errored = 0
    var skipped = 0

    for ((name, mtime) in entries) {
      val cachedMtime = mtimeCache.getString(name, null)
      val cachedId = mtimeCache.getString(name + ID_SUFFIX, null)
      if (cachedMtime != null && cachedId != null && mtime.isNotEmpty() && cachedMtime == mtime) {
        unchangedIds.add(cachedId)
        skipped++
        continue
      }
      try {
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val body = fetch(baseUrl + encoded, authHeader)
        val doc = JSONObject(body)
        if (doc.optJSONObject("more")?.optInt("enabled", 1) == 0) {
          disabled++
          // Drop cache so a later re-enable triggers a fresh fetch.
          mtimeCache.edit().remove(name).remove(name + ID_SUFFIX).apply()
          continue
        }
        val code = doc.optString("code")
        val parsed = parseScript(code)
        if (parsed == null) {
          badHeader++
          continue
        }
        scripts.add(parsed)
        if (mtime.isNotEmpty()) newMtimes[name] = mtime to parsed.id
      } catch (e: Exception) {
        Log.e("BulkImport: $name → ${e.message}")
        errored++
      }
    }

    if (scripts.isNotEmpty()) {
      ScriptDbManager.insert(*scripts.toTypedArray())
    }
    if (newMtimes.isNotEmpty()) {
      val editor = mtimeCache.edit()
      newMtimes.forEach { (name, pair) ->
        editor.putString(name, pair.first)
        editor.putString(name + ID_SUFFIX, pair.second)
      }
      editor.apply()
    }

    val keepIds = scripts.map { it.id }.toSet() + unchangedIds
    var removed = 0
    if (keepIds.isNotEmpty()) {
      val db = ScriptDbHelper(ctx).writableDatabase
      try {
        val toDelete = mutableListOf<String>()
        db.query("script", arrayOf("id"), null, null, null, null, null).use { c ->
          while (c.moveToNext()) {
            val id = c.getString(0)
            if (id !in keepIds) toDelete.add(id)
          }
        }
        toDelete.forEach {
          db.delete("script", "id = ?", arrayOf(it))
          Log.d("BulkImport: removed $it")
          removed++
        }
      } finally {
        db.close()
      }
    }
    ScriptDbManager.reload()
    Log.i(
        "BulkImport done: inserted=${scripts.size}, skipped=$skipped, " +
            "disabled=$disabled, badHeader=$badHeader, errored=$errored, removed=$removed")
  }

  // WebDAV PROPFIND listing with per-entry Last-Modified. Each response block
  // yields a (filename, mtime) pair. Files without mtime (server that doesn't
  // return getlastmodified) come back with an empty string and the caller
  // treats them as "always re-fetch".
  private fun listWebDav(url: String, auth: String?): List<Pair<String, String>> {
    val body = propfind(url, auth)
    val responseRegex = Regex("<[A-Za-z]*:?response>([\\s\\S]*?)</[A-Za-z]*:?response>")
    val mtimeRegex = Regex("<[A-Za-z]*:?getlastmodified>([^<]+)</")
    val results = mutableListOf<Pair<String, String>>()
    val seen = mutableSetOf<String>()
    for (rm in responseRegex.findAll(body)) {
      val block = rm.groupValues[1]
      val hrefMatch = hrefRegex.find(block) ?: continue
      val raw = hrefMatch.groupValues[1].replace("&amp;", "&")
      val trimmed = raw.trimEnd('/')
      val fileMatch = fileRegex.find(trimmed) ?: continue
      val decoded = URLDecoder.decode(fileMatch.groupValues[1], "UTF-8")
      if (!seen.add(decoded)) continue
      val mtime = mtimeRegex.find(block)?.groupValues?.get(1)?.trim() ?: ""
      results.add(decoded to mtime)
    }
    return results
  }

  private fun propfind(url: String, auth: String?): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
      forceMethod(conn, "PROPFIND")
      conn.setRequestProperty("Depth", "1")
      conn.setRequestProperty("Content-Type", "application/xml; charset=utf-8")
      conn.useCaches = false
      conn.setRequestProperty("Cache-Control", "no-cache")
      auth?.let { conn.setRequestProperty("Authorization", it) }
      conn.connectTimeout = 15_000
      conn.readTimeout = 30_000
      // Request displayname + getlastmodified. getlastmodified lets us short-
      // circuit re-fetches when the remote file hasn't changed since last sync.
      conn.doOutput = true
      val requestBody =
          "<?xml version=\"1.0\"?><propfind xmlns=\"DAV:\"><prop><displayname/><getlastmodified/></prop></propfind>"
      conn.outputStream.use { it.write(requestBody.toByteArray()) }
      return conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
      conn.disconnect()
    }
  }

  // HttpURLConnection's setRequestMethod rejects PROPFIND outright. The method
  // string is held both on the outer wrapper and on the internal okhttp
  // delegate, so we set the `method` field on *every* level of the delegate
  // chain — failing to propagate to the delegate means the actual request
  // silently falls back to GET and the server returns 405.
  private fun forceMethod(conn: HttpURLConnection, method: String) {
    var target: Any = conn
    val seen = mutableSetOf<Any>()
    while (seen.add(target)) {
      setMethodField(target, method)
      target = findDelegate(target) ?: return
    }
  }

  private fun setMethodField(target: Any, method: String) {
    var c: Class<*>? = target.javaClass
    while (c != null) {
      try {
        val f = c.getDeclaredField("method")
        f.isAccessible = true
        f.set(target, method)
        return
      } catch (_: NoSuchFieldException) {}
      c = c.superclass
    }
  }

  private fun findDelegate(target: Any): Any? {
    var c: Class<*>? = target.javaClass
    while (c != null) {
      try {
        val f = c.getDeclaredField("delegate")
        f.isAccessible = true
        return f.get(target)
      } catch (_: NoSuchFieldException) {}
      c = c.superclass
    }
    return null
  }

  private fun fetch(url: String, auth: String?): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
      conn.useCaches = false
      conn.setRequestProperty("Cache-Control", "no-cache")
      auth?.let { conn.setRequestProperty("Authorization", it) }
      conn.connectTimeout = 15_000
      conn.readTimeout = 60_000
      return conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
      conn.disconnect()
    }
  }
}
