package com.meracast

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView

/**
 * Protocol chooser — the app's launch screen.
 *
 * User picks between:
 *   1. Miracast / Wi‑Fi Display — screen mirroring (existing)
 *   2. Meracast Media Stream — app-to-app file streaming (new)
 */
class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        findViewById<MaterialCardView>(R.id.card_miracast).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        findViewById<MaterialCardView>(R.id.card_media).setOnClickListener {
            startActivity(Intent(this, MediaActivity::class.java))
        }
    }
}
