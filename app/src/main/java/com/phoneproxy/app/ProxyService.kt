package com.phoneproxy.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.phoneproxy.app.proxy.ProxyConfig
import com.phoneproxy.app.proxy.ProxyController
import com.phoneproxy.app.proxy.ProxyServer
import com.phoneproxy.app.proxy.ProxyState
import com.phoneproxy.app.proxy.UpstreamConnector

/**
 * Foreground service that owns the running [ProxyServer]. Running in the
 * foreground keeps the proxy alive while the screen is off and prevents the
 * system from killing it under memory pressure.
 */
class ProxyService : Service() {

    private var server: ProxyServer? = null
    private var currentConfig: ProxyConfig? = null
    private var upstream: UpstreamConnector? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopProxy()
                return START_NOT_STICKY
            }
            else -> startProxy(intent)
        }
        return START_STICKY
    }

    private fun startProxy(intent: Intent?) {
        if (server != null) return

        val port = intent?.getIntExtra(EXTRA_PORT, ProxyConfig.DEFAULT_PORT)
            ?: ProxyConfig.DEFAULT_PORT
        val username = intent?.getStringExtra(EXTRA_USERNAME)?.takeIf { it.isNotEmpty() }
        val password = intent?.getStringExtra(EXTRA_PASSWORD)?.takeIf { it.isNotEmpty() }
        val config = ProxyConfig(port = port, username = username, password = password)

        val connector = UpstreamConnector(applicationContext)
        connector.start()
        val newServer = ProxyServer(config, connector)
        try {
            newServer.start()
        } catch (e: Exception) {
            Log.e(TAG, "failed to start proxy", e)
            connector.stop()
            ProxyController.update(
                ProxyState(
                    running = false,
                    port = port,
                    authEnabled = config.authEnabled,
                    error = e.message ?: "Failed to bind port $port",
                )
            )
            stopSelf()
            return
        }

        server = newServer
        currentConfig = config
        upstream = connector
        startForeground(NOTIFICATION_ID, buildNotification(config))
        ProxyController.update(
            ProxyState(
                running = true,
                port = port,
                authEnabled = config.authEnabled,
                vpnActive = connector.vpnActive,
                error = null,
            )
        )
    }

    private fun stopProxy() {
        server?.stop()
        server = null
        upstream?.stop()
        upstream = null
        val port = currentConfig?.port ?: ProxyConfig.DEFAULT_PORT
        val authEnabled = currentConfig?.authEnabled ?: false
        currentConfig = null
        ProxyController.update(
            ProxyState(running = false, port = port, authEnabled = authEnabled, error = null)
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        upstream?.stop()
        upstream = null
        super.onDestroy()
    }

    private fun buildNotification(config: ProxyConfig): Notification {
        createChannel()

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ProxyService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val auth = if (config.authEnabled) " (auth on)" else ""
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Listening on port ${config.port}$auth")
            .setSmallIcon(R.drawable.ic_stat_proxy)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(null, "Stop", stopIntent).build()
            )
        return builder.build()
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Proxy service",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Shows when the proxy is running" }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "ProxyService"
        private const val CHANNEL_ID = "proxy_service"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.phoneproxy.app.action.START"
        const val ACTION_STOP = "com.phoneproxy.app.action.STOP"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_USERNAME = "extra_username"
        const val EXTRA_PASSWORD = "extra_password"

        fun startIntent(
            context: Context,
            port: Int,
            username: String?,
            password: String?,
        ): Intent = Intent(context, ProxyService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_PORT, port)
            putExtra(EXTRA_USERNAME, username)
            putExtra(EXTRA_PASSWORD, password)
        }

        fun stopIntent(context: Context): Intent =
            Intent(context, ProxyService::class.java).apply { action = ACTION_STOP }
    }
}
