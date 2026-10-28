package tr.sayim.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sayım uygulaması: bilgisayardaki Sayım programının formunu telefonun İÇİNDEN (internetsiz) açar.
 *  - Sayfa uygulamanın içindedir (sabit adres: appassets.androidplatform.net), bu yüzden bilgisayarın tünel adresi
 *    değişse bile telefondaki bekleyen kayıtlar kaybolmaz; internet gelince kendiliğinden gönderilir.
 *  - İlk açılışta bilgisayardaki "Telefon Eşleştir" QR'ı okutulur (adres + PIN); sonrası kendiliğinden bağlanır.
 *  - Sunucu yeni bir form sürümü yayımlarsa sayfa kendiliğinden indirilip saklanır (yeniden APK kurmak gerekmez).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var prefs: SharedPreferences
    private val ana = Handler(Looper.getMainLooper())

    private var dosyaCb: ValueCallback<Array<Uri>>? = null
    private var kameraUri: Uri? = null
    private var bekleyenIzin: PermissionRequest? = null
    private var bekleyenPaylasim: String? = null
    private var bekleyenEslestirme: String? = null
    private var sayfaHazir = false

    @Volatile
    private var hazirGeldi = false

    private val izinIste = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val r = bekleyenIzin
        bekleyenIzin = null
        if (r != null) {
            if (ok) r.grant(r.resources) else r.deny()
        }
    }

    private val tara = registerForActivityResult(ScanContract()) { sonuc ->
        val metin = sonuc.contents
        if (metin != null) jsCagir("sayimEslestirSonuc", metin)
    }

    private val dosyaSec = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val cb = dosyaCb
        dosyaCb = null
        var sonuc: Array<Uri>? = null
        if (res.resultCode == RESULT_OK) {
            val d = res.data
            val clip = d?.clipData
            val tek = d?.data
            val kamera = kameraUri
            sonuc = when {
                clip != null && clip.itemCount > 0 -> Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                tek != null -> arrayOf(tek)
                kamera != null -> arrayOf(kamera)
                else -> null
            }
        }
        kameraUri = null
        cb?.onReceiveValue(sonuc)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("sayim", Context.MODE_PRIVATE)

        web = WebView(this)
        setContentView(
            web,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val yukleyici = WebViewAssetLoader.Builder()
            .addPathHandler("/uygulama/", ArayuzYolu(this))
            .build()

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = true
        s.mediaPlaybackRequiresUserGesture = false
        // Yerel ağ adresi (http://192.168...) ile de çalışabilsin
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        web.addJavascriptInterface(Kopru(), "SayimApp")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return yukleyici.shouldInterceptRequest(request.url)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == WebViewAssetLoader.DEFAULT_DOMAIN) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (e: Exception) {
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                sayfaHazir = true
                bekleyenleriGonder()
                guvenlikZamanlayici()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { kameraIzniIleVer(request) }
            }

            override fun onShowFileChooser(
                w: WebView,
                cb: ValueCallback<Array<Uri>>,
                p: WebChromeClient.FileChooserParams
            ): Boolean {
                return dosyaSecici(cb, p)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            izinIste.launch(Manifest.permission.CAMERA)
        }

        intentIsle(intent)
        yukle()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intentIsle(intent)
    }

    private fun yukle() {
        hazirGeldi = false
        sayfaHazir = false
        web.loadUrl("https://" + WebViewAssetLoader.DEFAULT_DOMAIN + "/uygulama/index.html")
    }

    /** Sunucudan inen sayfa bozuksa (15 sn içinde "hazır" demezse) silinir, uygulamanın içindeki sayfaya dönülür. */
    private fun guvenlikZamanlayici() {
        val f = File(filesDir, "arayuz.html")
        if (!f.exists() || hazirGeldi) return
        ana.postDelayed({
            if (!hazirGeldi && f.exists()) {
                f.delete()
                yukle()
            }
        }, 15000)
    }

    private fun intentIsle(i: Intent?) {
        if (i == null) return
        val veri = i.data
        if (veri != null && veri.scheme == "sayim") {
            bekleyenEslestirme = veri.toString()
        } else if (i.action == Intent.ACTION_SEND) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT)
            if (t != null) bekleyenPaylasim = t
        }
        bekleyenleriGonder()
    }

    private fun bekleyenleriGonder() {
        if (!sayfaHazir) return
        val e = bekleyenEslestirme
        if (e != null) {
            bekleyenEslestirme = null
            jsCagir("sayimEslestirSonuc", e)
        }
        val p = bekleyenPaylasim
        if (p != null) {
            bekleyenPaylasim = null
            jsCagir("sayimPaylas", p)
        }
    }

    private fun jsCagir(fn: String, arg: String) {
        web.evaluateJavascript("if (window.$fn) window.$fn(" + JSONObject.quote(arg) + ");", null)
    }

    private fun kameraIzniIleVer(r: PermissionRequest) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            r.grant(r.resources)
        } else {
            bekleyenIzin = r
            izinIste.launch(Manifest.permission.CAMERA)
        }
    }

    private fun dosyaSecici(cb: ValueCallback<Array<Uri>>, p: WebChromeClient.FileChooserParams): Boolean {
        dosyaCb?.onReceiveValue(null)
        dosyaCb = cb
        kameraUri = null

        var kamera: Intent? = null
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            try {
                val klasor = File(cacheDir, "foto")
                klasor.mkdirs()
                val ad = "cek_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
                val uri = FileProvider.getUriForFile(this, "$packageName.dosya", File(klasor, ad))
                val k = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                k.putExtra(MediaStore.EXTRA_OUTPUT, uri)
                k.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                kamera = k
                kameraUri = uri
            } catch (e: Exception) {
                kamera = null
                kameraUri = null
            }
        }

        val istek: Intent
        if (p.isCaptureEnabled && kamera != null) {
            istek = kamera
        } else {
            val secici = Intent.createChooser(p.createIntent(), "Fotoğraf seç")
            if (kamera != null) secici.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(kamera))
            istek = secici
        }
        return try {
            dosyaSec.launch(istek)
            true
        } catch (e: Exception) {
            dosyaCb = null
            cb.onReceiveValue(null)
            false
        }
    }

    private fun tarayiciAc() {
        val o = ScanOptions()
        o.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        o.setPrompt("Bilgisayardaki eşleştirme QR'ını okutun")
        o.setBeepEnabled(false)
        o.setOrientationLocked(false)
        tara.launch(o)
    }

    override fun onDestroy() {
        ana.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Sayfadaki JavaScript'in çağırdığı köprü: window.SayimApp */
    inner class Kopru {
        @JavascriptInterface
        fun taban(): String = prefs.getString("taban", "") ?: ""

        @JavascriptInterface
        fun jeton(): String = prefs.getString("jeton", "") ?: ""

        @JavascriptInterface
        fun kaydet(taban: String) {
            prefs.edit().putString("taban", taban.trim().trimEnd('/')).apply()
        }

        @JavascriptInterface
        fun jetonYaz(j: String) {
            prefs.edit().putString("jeton", j).apply()
        }

        @JavascriptInterface
        fun eslestir() {
            runOnUiThread { tarayiciAc() }
        }

        @JavascriptInterface
        fun pano(): String {
            return try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val klip = cm.primaryClip
                if (klip != null && klip.itemCount > 0) klip.getItemAt(0).coerceToText(this@MainActivity).toString() else ""
            } catch (e: Exception) {
                ""
            }
        }

        @JavascriptInterface
        fun hazir() {
            hazirGeldi = true
        }

        @JavascriptInterface
        fun arayuzKaydet(html: String): Boolean {
            if (html.length < 20000 || !html.contains("</html>")) return false
            return try {
                val gecici = File(filesDir, "arayuz.tmp")
                val hedef = File(filesDir, "arayuz.html")
                gecici.writeText(html, Charsets.UTF_8)
                if (hedef.exists()) hedef.delete()
                gecici.renameTo(hedef)
            } catch (e: Exception) {
                false
            }
        }
    }
}

/** Sayfayı sunar: sunucudan indirilmiş yeni sürüm varsa onu, yoksa APK'nın içindeki index.html'i. */
class ArayuzYolu(private val ctx: Context) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? {
        return try {
            val f = File(ctx.filesDir, "arayuz.html")
            val girdi = if (f.exists()) FileInputStream(f) else ctx.assets.open("index.html")
            WebResourceResponse("text/html", "utf-8", girdi)
        } catch (e: Exception) {
            null
        }
    }
}
