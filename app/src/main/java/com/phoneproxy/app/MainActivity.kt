package com.phoneproxy.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phoneproxy.app.proxy.NetworkUtils
import com.phoneproxy.app.proxy.ProxyConfig
import com.phoneproxy.app.proxy.ProxyController

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        setContent {
            MaterialTheme {
                ProxyScreen()
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun ProxyScreen() {
    val context = LocalContext.current
    val state by ProxyController.state.collectAsStateWithLifecycle()

    var portText by rememberSaveable { mutableStateOf(ProxyConfig.DEFAULT_PORT.toString()) }
    var authEnabled by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    val addresses = remember(state.running) { NetworkUtils.localAddresses() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Phone Proxy", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Runs a SOCKS5 + HTTP proxy on this phone. Set your PC to use it so the " +
                "PC's traffic is forwarded through the VPN active on this phone.",
            style = MaterialTheme.typography.bodyMedium,
        )

        StatusCard(state.running, state.port, state.authEnabled, state.error)

        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it.filter(Char::isDigit).take(5) },
            label = { Text("Port") },
            singleLine = true,
            enabled = !state.running,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Require username / password")
            Switch(
                checked = authEnabled,
                onCheckedChange = { authEnabled = it },
                enabled = !state.running,
            )
        }

        if (authEnabled) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true,
                enabled = !state.running,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                enabled = !state.running,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (state.running) {
            Button(
                onClick = { context.startService(ProxyService.stopIntent(context)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Stop proxy") }
        } else {
            Button(
                onClick = {
                    val port = portText.toIntOrNull()
                        ?.coerceIn(ProxyConfig.MIN_PORT, ProxyConfig.MAX_PORT)
                        ?: ProxyConfig.DEFAULT_PORT
                    val intent = ProxyService.startIntent(
                        context,
                        port,
                        if (authEnabled) username else null,
                        if (authEnabled) password else null,
                    )
                    ContextCompat.startForegroundService(context, intent)
                },
                enabled = portText.toIntOrNull()
                    ?.let { it in ProxyConfig.MIN_PORT..ProxyConfig.MAX_PORT } == true &&
                    (!authEnabled || (username.isNotEmpty() && password.isNotEmpty())),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start proxy") }
        }

        if (state.running && addresses.isNotEmpty()) {
            AddressCard(addresses, state.port)
        }
    }
}

@Composable
private fun StatusCard(running: Boolean, port: Int, authEnabled: Boolean, error: String?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                if (running) "Running" else "Stopped",
                style = MaterialTheme.typography.titleLarge,
                color = if (running) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (running) {
                Spacer(Modifier.height(4.dp))
                Text("Port $port" + if (authEnabled) " · auth required" else "")
            }
            if (error != null) {
                Spacer(Modifier.height(4.dp))
                Text("Error: $error", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun AddressCard(
    addresses: List<com.phoneproxy.app.proxy.LocalAddress>,
    port: Int,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Configure your PC to use", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            addresses.forEach { addr ->
                val tag = if (addr.isLikelyTether) " (tether: ${addr.interfaceName})" else " (${addr.interfaceName})"
                Text("${addr.ip}:$port$tag")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Use it as both a SOCKS5 and an HTTP/HTTPS proxy.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
