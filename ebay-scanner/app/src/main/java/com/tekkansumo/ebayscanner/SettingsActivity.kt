package com.tekkansumo.ebayscanner

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Suppress("UseSwitchCompatOrMaterialCode")
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val p = Prefs(this)
        val clientId = findViewById<EditText>(R.id.clientId)
        val clientSecret = findViewById<EditText>(R.id.clientSecret)
        val threshold = findViewById<EditText>(R.id.threshold)
        val fee = findViewById<EditText>(R.id.fee)
        val shipping = findViewById<EditText>(R.id.shipping)
        val autoFx = findViewById<Switch>(R.id.autoFx)
        val manualFx = findViewById<EditText>(R.id.manualFx)
        val fxNow = findViewById<TextView>(R.id.fxNow)
        val imageSearch = findViewById<Switch>(R.id.imageSearch)
        val preferJp = findViewById<Switch>(R.id.preferJp)
        val sound = findViewById<Switch>(R.id.sound)

        clientId.setText(p.clientId)
        clientSecret.setText(p.clientSecret)
        threshold.setText(p.thresholdJpy.toString())
        fee.setText(trim(p.feePercent))
        shipping.setText(p.shippingJpy.toString())
        autoFx.isChecked = p.autoFx
        manualFx.setText(trim(p.manualFx))
        imageSearch.isChecked = p.imageSearch
        preferJp.isChecked = p.preferJapanSellers
        sound.isChecked = p.sound
        fxNow.text = if (p.cachedFx > 0f) {
            "自動取得したレート: ${String.format(Locale.US, "%.2f", p.cachedFx)} 円（" +
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(p.cachedFxAt)) + "）"
        } else "自動取得したレート: まだありません（自動取得できないときは手動のレートを使います）"

        findViewById<Button>(R.id.save).setOnClickListener {
            p.clientId = clientId.text.toString()
            p.clientSecret = clientSecret.text.toString()
            threshold.text.toString().toIntOrNull()?.let { p.thresholdJpy = it.coerceAtLeast(0) }
            fee.text.toString().toFloatOrNull()?.let { p.feePercent = it.coerceIn(0f, 60f) }
            shipping.text.toString().toIntOrNull()?.let { p.shippingJpy = it.coerceAtLeast(0) }
            p.autoFx = autoFx.isChecked
            manualFx.text.toString().toFloatOrNull()?.let { if (it > 50f) p.manualFx = it }
            p.imageSearch = imageSearch.isChecked
            p.preferJapanSellers = preferJp.isChecked
            p.sound = sound.isChecked
            Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun trim(f: Float) = if (f % 1f == 0f) f.toInt().toString() else f.toString()
}
