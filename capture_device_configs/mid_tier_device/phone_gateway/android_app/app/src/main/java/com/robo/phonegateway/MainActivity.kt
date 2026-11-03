package com.robo.phonegateway

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.robo.phonegateway.auth.OidcAuth
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

private const val AUTH_REQUEST_CODE = 42

class MainActivity : ComponentActivity() {

    private lateinit var auth: OidcAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = OidcAuth(this)

        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        if (Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions.launch(missing.toTypedArray())
        }

        setContent {
            var signedIn by remember { mutableStateOf(auth.isSignedIn()) }
            var status by remember { mutableStateOf("idle") }
            LaunchedEffect(Unit) {
                launch { auth.discover() }
            }
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Phone Gateway")
                if (!signedIn) {
                    Button(onClick = {
                        kotlinx.coroutines.MainScope().launch {
                            val intent = auth.buildAuthRequest()
                            startActivityForResult(intent, AUTH_REQUEST_CODE)
                        }
                    }) { Text("Sign in with authentik") }
                } else {
                    Text("signed in")
                    Button(onClick = {
                        status = "starting"
                        ContextCompat.startForegroundService(
                            this@MainActivity,
                            Intent(this@MainActivity, GatewayService::class.java),
                        )
                    }) { Text("Start capture") }
                    Button(onClick = {
                        stopService(Intent(this@MainActivity, GatewayService::class.java))
                        status = "stopped"
                    }) { Text("Stop") }
                }
                Text(status)
            }
        }
    }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    @Deprecated("needed for AppAuth redirect")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == AUTH_REQUEST_CODE && data != null) {
            kotlinx.coroutines.MainScope().launch {
                runCatching { auth.handleAuthResponse(data) }
                setContent { Text("signed in") }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // AppAuth delivers the redirect here when the activity is singleTop;
        // handle it as an auth response too.
        if (intent.data?.scheme == "com.robo.phonegateway") {
            kotlinx.coroutines.MainScope().launch {
                runCatching { auth.handleAuthResponse(intent) }
            }
        }
    }
}
