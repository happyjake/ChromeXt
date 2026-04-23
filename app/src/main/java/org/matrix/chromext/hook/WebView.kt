package org.matrix.chromext.hook

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import java.lang.ref.WeakReference
import org.matrix.chromext.Chrome
import org.matrix.chromext.Listener
import org.matrix.chromext.script.Local
import org.matrix.chromext.script.ScriptDbManager
import org.matrix.chromext.utils.Log
import org.matrix.chromext.utils.findField
import org.matrix.chromext.utils.findMethod
import org.matrix.chromext.utils.findMethodOrNull
import org.matrix.chromext.utils.hookAfter
import org.matrix.chromext.utils.hookBefore
import org.matrix.chromext.utils.invokeMethod

object WebViewHook : BaseHook() {

  var ViewClient: Class<*>? = null
  var ChromeClient: Class<*>? = null
  var WebView: Class<*>? = null
  val records = mutableListOf<WeakReference<Any>>()

  private val hookedViewClients = mutableSetOf<Class<*>>()
  private val hookedChromeClients = mutableSetOf<Class<*>>()

  fun evaluateJavascript(code: String?, view: Any?) {
    val webView = (view ?: Chrome.getTab())
    if (code != null && code.length > 0 && webView != null) {
      val webSettings = webView.invokeMethod { name == "getSettings" }
      if (webSettings?.invokeMethod { name == "getJavaScriptEnabled" } == true)
          Handler(Chrome.getContext().mainLooper).post {
            webView.invokeMethod(code, null) { name == "evaluateJavascript" }
          }
    }
  }

  private fun onUpdateUrl(url: String, view: Any?) {
    if (url.startsWith("javascript") || view == null) return
    Chrome.updateTab(view)
    ScriptDbManager.invokeScript(url, view)
  }

  private fun hookViewClient(cls: Class<*>) {
    if (cls === WebViewClient::class.java) return
    if (!hookedViewClients.add(cls)) return
    runCatching {
          findMethod(cls, true) { name == "onPageStarted" }
              .hookAfter {
                if (Chrome.isQihoo && it.thisObject::class.java.declaredMethods.size > 1)
                    return@hookAfter
                onUpdateUrl(it.args[1] as String, it.args[0])
              }
          Log.d("onPageStarted hooked on ${cls.name}")
        }
        .onFailure { Log.d("Failed to hook onPageStarted on ${cls.name}: $it") }
  }

  private fun hookChromeClient(cls: Class<*>) {
    if (cls === WebChromeClient::class.java) return
    if (!hookedChromeClients.add(cls)) return
    runCatching {
          findMethod(cls, true) { name == "onConsoleMessage" && parameterCount == 1 }
              .hookAfter {
                // Don't use ConsoleMessage to specify this method since Mi Browser uses its own
                // implementation
                // This should be the way to communicate with the front-end of ChromeXt
                val chromeClient = it.thisObject
                val consoleMessage = it.args[0]
                val messageLevel = consoleMessage.invokeMethod { name == "messageLevel" }
                val sourceId = consoleMessage.invokeMethod { name == "sourceId" } as String
                val lineNumber = consoleMessage.invokeMethod { name == "lineNumber" }
                val message = consoleMessage.invokeMethod { name == "message" } as String
                if (messageLevel.toString() == "TIP" &&
                    sourceId.startsWith("local://ChromeXt/init") &&
                    lineNumber == Local.anchorInChromeXt) {
                  val webView =
                      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        records
                            .find {
                              if (Chrome.isQihoo) {
                                    val mProvider = findField(WebView!!) { name == "mProvider" }
                                    mProvider.get(it.get())
                                  } else {
                                    it.get()
                                  }
                                  ?.invokeMethod { name == "getWebChromeClient" } == chromeClient
                            }
                            ?.get()
                      } else Chrome.getTab()
                  Listener.startAction(message, webView, chromeClient, sourceId)
                } else {
                  Log.d(messageLevel.toString() + ": [${sourceId}@${lineNumber}] ${message}")
                }
              }
          Log.d("onConsoleMessage hooked on ${cls.name}")
        }
        .onFailure { Log.d("Failed to hook onConsoleMessage on ${cls.name}: $it") }
  }

  override fun init() {

    // Pre-hook any vendor-specific client classes that MainHook captured up-front
    // (Mi Browser / Qihoo Browser paths). No-op for the generic case.
    ViewClient?.let { hookViewClient(it) }
    ChromeClient?.let { hookChromeClient(it) }

    // Catch every client class the app actually assigns to a WebView, not just
    // whichever subclass wins a constructor race. `setWebView[Chrome]Client` is
    // the single mandatory entry point for wiring a client to a WebView.
    findMethodOrNull(WebView!!) { name == "setWebViewClient" }
        ?.hookAfter {
          val client = it.args.getOrNull(0) ?: return@hookAfter
          hookViewClient(client::class.java)
        }

    findMethod(WebView!!) { name == "setWebChromeClient" }
        .hookAfter {
          val webView = it.thisObject
          val client = it.args.getOrNull(0)
          records.removeAll(records.filter { it.get() == null || it.get() == webView })
          if (client != null) {
            records.add(WeakReference(webView))
            hookChromeClient(client::class.java)
          }
        }

    findMethod(WebView!!) { name == "onAttachedToWindow" }
        .hookAfter { Chrome.updateTab(it.thisObject) }

    findMethod(Activity::class.java) { name == "onStop" }
        .hookBefore { ScriptDbManager.updateScriptStorage() }
    isInit = true
  }
}
