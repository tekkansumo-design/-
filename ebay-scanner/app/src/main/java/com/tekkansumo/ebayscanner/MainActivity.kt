package com.tekkansumo.ebayscanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), ScanEngine.Listener {

    private lateinit var prefs: Prefs
    private lateinit var engine: ScanEngine
    private lateinit var alerter: Alerter
    private val ebay = EbayClient()

    private lateinit var analysisThread: ExecutorService
    private var analyzer: FrameAnalyzer? = null
    @Volatile private var paused = false
    @Volatile private var wantImage = false

    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var priceView: TextView
    private lateinit var targetView: TextView
    private lateinit var marketView: TextView
    private lateinit var profitView: TextView
    private lateinit var samplesView: TextView
    private lateinit var alertBox: View
    private lateinit var alertTitle: TextView
    private lateinit var alertBody: TextView
    private lateinit var btnPause: Button
    private lateinit var btnSold: Button

    private var soldUrl: String? = null
    private val hideAlert = Runnable { alertBox.visibility = View.GONE }
    private val yen = NumberFormat.getIntegerInstance(Locale.JAPAN)

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else status.text = "カメラの許可が必要です"
    }
    private val askNoti = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = Prefs(this)
        alerter = Alerter(this)
        engine = ScanEngine(prefs, ebay, lifecycleScope, this)
        analysisThread = Executors.newSingleThreadExecutor()

        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        priceView = findViewById(R.id.price)
        targetView = findViewById(R.id.target)
        marketView = findViewById(R.id.market)
        profitView = findViewById(R.id.profit)
        samplesView = findViewById(R.id.samples)
        alertBox = findViewById(R.id.alertBox)
        alertTitle = findViewById(R.id.alertTitle)
        alertBody = findViewById(R.id.alertBody)
        btnPause = findViewById(R.id.btnPause)
        btnSold = findViewById(R.id.btnSold)

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnPause.setOnClickListener {
            paused = !paused
            btnPause.text = if (paused) "再開" else "一時停止"
            updateStatus()
        }
        findViewById<Button>(R.id.btnEditPrice).setOnClickListener { editPrice() }
        findViewById<Button>(R.id.btnNext).setOnClickListener {
            engine.setManualPrice(null)
            engine.clearTarget()
            alertBox.visibility = View.GONE
        }
        btnSold.setOnClickListener { soldUrl?.let(::open) }
        alertBox.setOnClickListener { soldUrl?.let(::open) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            askCamera.launch(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNoti.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshFx()
        updateStatus()
        // 設定（手数料・送料など）が変わっているかもしれないので計算し直す
        engine.reevaluate()
        if (!prefs.hasCredentials) {
            targetView.text = "まず「設定」で eBay API の鍵を入力してください"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analyzer?.close()
        analysisThread.shutdown()
        alerter.release()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val a = FrameAnalyzer(
                callbackExecutor = ContextCompat.getMainExecutor(this),
                wantImage = { wantImage },
                paused = { paused },
            ) { frame ->
                engine.onFrame(frame)
                wantImage = engine.wantsImage()
            }
            analyzer = a
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { it.setAnalyzer(analysisThread, a) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, analysis)
            wantImage = engine.wantsImage()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun refreshFx() {
        if (!prefs.autoFx) return
        val age = System.currentTimeMillis() - prefs.cachedFxAt
        if (prefs.cachedFx > 0f && age < 6 * 3600_000L) return
        lifecycleScope.launch {
            ebay.fetchUsdJpy()?.let {
                prefs.cachedFx = it.toFloat()
                prefs.cachedFxAt = System.currentTimeMillis()
                updateStatus()
                engine.reevaluate()
            }
        }
    }

    private fun updateStatus() {
        val fx = String.format(Locale.US, "%.1f", prefs.fx())
        status.text = when {
            paused -> "一時停止中"
            !prefs.hasCredentials -> "eBay の鍵が未設定"
            else -> "スキャン中 · \$1=¥$fx · 利益${yen.format(prefs.thresholdJpy)}円以上で通知"
        }
    }

    private fun editPrice() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "例: 1980"
            engine.price.confirmed?.let { setText(it.toString()) }
        }
        AlertDialog.Builder(this)
            .setTitle("仕入れ値（円）")
            .setView(input)
            .setPositiveButton("決定") { _, _ ->
                input.text.toString().toIntOrNull()?.let { engine.setManualPrice(it) }
            }
            .setNeutralButton("自動に戻す") { _, _ -> engine.setManualPrice(null) }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "ブラウザを開けませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun usd(v: Double) = String.format(Locale.US, "$%.2f", v)

    // ---- ScanEngine.Listener ----

    override fun onPrice(yen: Int?, manual: Boolean) {
        priceView.text = when {
            yen == null -> "読み取り中…"
            manual -> "¥${this.yen.format(yen)}（手入力）"
            else -> "¥${this.yen.format(yen)}"
        }
    }

    override fun onTarget(target: ScanEngine.Target?, searching: Boolean) {
        if (target == null) {
            targetView.text = "商品のバーコード・型番・全体を写してください"
            soldUrl = null
            btnSold.isEnabled = false
            return
        }
        val q = if (target.kind == ScanEngine.Kind.IMAGE) "写真" else target.query
        targetView.text = "${target.kind.label}: $q" + if (searching) "　eBay を検索中…" else ""
        if (target.kind != ScanEngine.Kind.IMAGE) {
            soldUrl = EbayClient.soldSearchUrl(target.query)
            btnSold.isEnabled = true
        }
    }

    override fun onLookup(lookup: ScanEngine.Lookup?, eval: ScanEngine.Eval?) {
        if (lookup == null) {
            marketView.text = ""
            profitView.text = ""
            samplesView.text = ""
            return
        }
        val s = lookup.stats
        if (s == null || eval == null) {
            marketView.text = "eBay に該当する出品が見つかりませんでした"
            profitView.text = ""
            samplesView.text = ""
            return
        }
        // 画像検索のときは、いちばん近い出品のタイトルで「売れた実績」を見られるようにする
        if (lookup.target.kind == ScanEngine.Kind.IMAGE) {
            lookup.items.firstOrNull()?.let {
                soldUrl = EbayClient.soldSearchUrl(it.title.take(80))
                btnSold.isEnabled = true
            }
        }
        val trust = lookup.target.kind.trust + if (lookup.target.model != null && !lookup.narrowed) "・型番不一致" else ""
        marketView.text = "eBay相場 ${usd(eval.sellUsd)}（≈¥${yen.format(eval.sellJpy)}）" +
            "　${s.count}件・${usd(s.low)}〜${usd(s.high)}・信頼度 $trust"
        val p = eval.profitJpy
        if (p == null) {
            profitView.text = "仕入れ上限 ¥${yen.format(eval.maxPurchaseJpy.coerceAtLeast(0))}"
            profitView.setTextColor(Color.WHITE)
        } else {
            val sign = if (p >= 0) "+" else "−"
            profitView.text = "見込み利益 $sign¥${yen.format(kotlin.math.abs(p))}" +
                "　（上限 ¥${yen.format(eval.maxPurchaseJpy.coerceAtLeast(0))}）"
            profitView.setTextColor(
                when {
                    eval.hit -> Color.parseColor("#22C55E")
                    p >= 0 -> Color.parseColor("#FACC15")
                    else -> Color.parseColor("#F87171")
                }
            )
        }
        samplesView.text = lookup.items.take(3).joinToString("\n") { "${usd(it.totalUsd)}  ${it.title}" }
    }

    override fun onError(message: String) {
        marketView.text = message
        profitView.text = ""
        samplesView.text = ""
    }

    override fun onHit(lookup: ScanEngine.Lookup, eval: ScanEngine.Eval) {
        val p = eval.profitJpy ?: return
        val title = "利益見込み +¥${yen.format(p)}"
        val name = lookup.items.firstOrNull()?.title ?: lookup.target.query
        val body = "仕入 ¥${yen.format(engine.price.confirmed ?: 0)} → eBay ${usd(eval.sellUsd)}" +
            "（${lookup.stats?.count ?: 0}件・信頼度 ${lookup.target.kind.trust}）\n$name\nタップで売れた実績を確認"
        alertTitle.text = title
        alertBody.text = body
        alertBox.visibility = View.VISIBLE
        alertBox.removeCallbacks(hideAlert)
        alertBox.postDelayed(hideAlert, 12_000)
        alerter.alert(title, body, soldUrl ?: EbayClient.soldSearchUrl(name), prefs.sound)
    }
}
