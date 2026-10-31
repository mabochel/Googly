package com.mabochel.bluey

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.mabochel.bluey.link.PairingRecord
import com.mabochel.bluey.ui.BlueyTheme
import com.mabochel.bluey.ui.RootScreen
import com.mabochel.bluey.ui.RootUiState

class MainActivity : ComponentActivity() {
    private val model get() = (application as BlueyApplication).model
    private val ui = RootUiState()
    private var micCallback: ((Boolean) -> Unit)? = null
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micCallback?.invoke(granted)
        micCallback = null
    }
    /** True while Google's QR scanner covers the screen, so that doesn't count as leaving the app. */
    private var scanning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        model.live.requestMicrophone = { done ->
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                done(true)
            } else {
                micCallback = done
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> model.foregrounded()
                Lifecycle.Event.ON_STOP -> if (!scanning) model.backgrounded()
                else -> {}
            }
        })
        if (!model.link.isPaired) prepareScanner()
        handlePairingLink(intent)

        setContent {
            BlueyTheme {
                RootScreen(model, ui, scanQr = ::scan, pairText = ::pair)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairingLink(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** A "bluey-pair:…" link opened from the camera app or a paste. */
    private fun handlePairingLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val text = intent.dataString ?: return
        if (text.startsWith(PairingRecord.PREFIX)) {
            ui.showPairSheet = true
            pair(Uri.decode(text))
        }
    }

    private fun pair(text: String) {
        val record = PairingRecord.parse(text)
        when {
            record == null -> ui.pairError = "That isn't a Bluey pairing code."
            model.link.pair(record) -> {
                ui.pairError = ""
                ui.showPairSheet = false
            }
            else -> ui.pairError = "Couldn't save pairing securely. Try again."
        }
    }

    private fun scan() {
        ui.pairError = ""
        scanning = true
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode -> pair(barcode.rawValue ?: "") }
            .addOnFailureListener {
                ui.pairError = "Couldn't open the scanner yet (Google Play services may still be downloading it). " +
                    "Try again in a minute, or paste the code below."
                prepareScanner()
            }
            .addOnCompleteListener { scanning = false }
    }

    /** Google's scanner is downloaded on demand; ask for it early so the first scan just works. */
    private fun prepareScanner() {
        runCatching {
            val scanner = GmsBarcodeScanning.getClient(this)
            ModuleInstall.getClient(this).installModules(ModuleInstallRequest.newBuilder().addApi(scanner).build())
        }
    }
}
