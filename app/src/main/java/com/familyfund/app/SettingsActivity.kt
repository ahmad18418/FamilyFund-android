package com.familyfund.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val prefs = Prefs(this)
        val input = findViewById<EditText>(R.id.inputUrl)
        val sw = findViewById<SwitchMaterial>(R.id.switchBio)
        input.setText(prefs.serverUrl)
        sw.isChecked = prefs.biometricEnabled

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            val url = input.text?.toString()?.trim().orEmpty()
            if (url.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) {
                Toast.makeText(this, "آدرس باید با http:// یا https:// شروع شود", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.serverUrl = url
            prefs.biometricEnabled = sw.isChecked
            Toast.makeText(this, "ذخیره شد", Toast.LENGTH_SHORT).show()
            // برگشت به صفحه اصلی برای اعمال بیومتریک
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            finish()
        }

        findViewById<MaterialButton>(R.id.btnChangePassword).setOnClickListener {
            val base = prefs.serverUrl.trimEnd('/')
            val target = "$base/profile/edit"
            // باز کردن داخل اپ اصلی با URL مشخص
            val i = Intent(this, MainActivity::class.java)
            i.putExtra(MainActivity.EXTRA_OPEN_URL, target)
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(i)
            finish()
        }
    }
}
