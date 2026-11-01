package com.dhannow.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    /** The https://dhannow.com/app/... link that opened or resumed the app, until Dart takes it. */
    private var link: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        link = intent?.dataString
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        link = intent.dataString
    }

    // One small channel instead of plugins: E9 "Open settings", and the app link (SMS/email links
    // to /app/open, provider returns to /app/cb).
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "dhannow/system").setMethodCallHandler { call, result ->
            when (call.method) {
                "openAppSettings" -> {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    result.success(null)
                }
                "takeLink" -> {
                    result.success(link)
                    link = null
                }
                else -> result.notImplemented()
            }
        }
    }
}
