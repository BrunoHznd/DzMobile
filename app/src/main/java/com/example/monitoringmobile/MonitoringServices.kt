package com.example.monitoringmobile

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.monitoringmobile.R
import com.google.gson.Gson
import java.io.File
import java.io.FileReader
import java.net.HttpURLConnection
import java.time.LocalTime
import java.util.*

class MonitoringService : Service() {

    private val UUID_KEY = "unique_device_uuid"
    private lateinit var sharedPreferences: SharedPreferences
    private val CHANNEL_ID = "MonitoringChannel"
    private val NOTIFICATION_ID = 1
    private val TAG = "MonitoringService"
    private var locationManager: LocationManager? = null
    private lateinit var handler: Handler
    private lateinit var runnable: Runnable
    private lateinit var wakeLock: PowerManager.WakeLock

    private var lastLocation: Location? = null
    private val gson = Gson()

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
        }
        override fun onProviderDisabled(provider: String) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupLocationManager()

        sharedPreferences = getSharedPreferences("DevicePreferences", Context.MODE_PRIVATE)
        val uuid = getOrCreateUUID()
        println("UUID: $uuid")

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Monitoring:WakeLock")
        handler = Handler(Looper.getMainLooper())

        if (!isGpsEnabled()) {
            promptToEnableGps()
        }

        runnable = object : Runnable {
            @RequiresApi(Build.VERSION_CODES.O)
            override fun run() {
                val currentTime = LocalTime.now()
                if (currentTime.hour in 7..17) {
                    if (!wakeLock.isHeld) wakeLock.acquire()
                    collectAndLogData()
                    if (wakeLock.isHeld) wakeLock.release()
                }
                handler.postDelayed(this, 10000)
            }
        }

        handler.post(runnable)
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(runnable)
        locationManager?.removeUpdates(locationListener)
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun collectAndLogData() {
        val memoryInfo = getMemoryInfo()
        val storageInfo = getStorageInfo()
        val networkInfo = getNetworkInfo()
        val locationInfo = getLocationInfo()
        val batteryStatus = getBatteryStatus(applicationContext)

        val monitoringData = MonitoringData(
            memoryInfo, storageInfo, networkInfo, locationInfo, batteryStatus
        )

        val jsonData = gson.toJson(monitoringData)
        Log.d(TAG, jsonData)
        sendDataToServer(jsonData)

    }

    private fun getOrCreateUUID(): String {
        val uuid = sharedPreferences.getString(UUID_KEY, null)
        return uuid ?: UUID.randomUUID().toString().also {
            sharedPreferences.edit().putString(UUID_KEY, it).apply()
        }
    }

    private fun getMemoryInfo(): MemoryInfo {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val totalMemoryInGB = (memoryInfo.totalMem / (1024 * 1024 * 1024)).toDouble()
        val availableMemoryInGB = (memoryInfo.availMem / (1024 * 1024 * 1024)).toDouble()

        return MemoryInfo(totalMemoryInGB, availableMemoryInGB)
    }

    private fun getStorageInfo(): StorageInfo {
        val statFs = StatFs(Environment.getExternalStorageDirectory().absolutePath)
        val totalSpaceInGB = (statFs.totalBytes / (1024 * 1024 * 1024)).toDouble()
        val availableSpaceInGB = (statFs.availableBytes / (1024 * 1024 * 1024)).toDouble()
        return StorageInfo(totalSpaceInGB, availableSpaceInGB)
    }

    private fun getNetworkInfo(): NetworkInfo {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        var connectionType = "No Connection"

        if (capabilities != null) {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                connectionType = "Wi-Fi"
            } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                connectionType = "Mobile Data"
            }
        }
        return NetworkInfo(connectionType)
    }

    private fun setupLocationManager() {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permissions not granted.")
            return
        }
        locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0f, locationListener)
    }

    private fun getLocationInfo(): LocationInfo {
        val providers = locationManager?.getProviders(true)
        var bestLocation: Location? = null

        providers?.forEach { provider ->
            val l = locationManager?.getLastKnownLocation(provider)
            if (l != null && (bestLocation == null || l.accuracy < bestLocation!!.accuracy)) {
                bestLocation = l
            }
        }

        return bestLocation?.let {
            LocationInfo(it.latitude, it.longitude)
        } ?: LocationInfo(null, null)
    }

    fun getBatteryStatus(context: Context): BatteryStatus {
        val batteryStatusIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1

        val batteryPercentage = if (level != -1 && scale != -1) (level / scale.toFloat()) * 100 else -1f
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val chargingSource = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "Not Charging"
        }
        return BatteryStatus(batteryPercentage, charging, chargingSource)
    }

    private fun isGpsEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    private fun promptToEnableGps() {
        val intent = Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }

    data class BatteryStatus(val batteryPercentage: Float, val isCharging: Boolean, val chargingSource: String)
    data class MonitoringData(
        val memory: MemoryInfo,
        val storage: StorageInfo,
        val network: NetworkInfo,
        val location: LocationInfo,
        val batteryStatus: BatteryStatus
    )
    data class MemoryInfo(val totalMemory: Double, val availableMemory: Double)
    data class StorageInfo(val totalSpace: Double, val availableSpace: Double)
    data class NetworkInfo(val connectionType: String)
    data class LocationInfo(val latitude: Double?, val longitude: Double?)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Foreground Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Celular Monitorado - Mar Brasil - Bruno Oliveira")
            .setContentText("Monitorando o seu dispositivo - Bruno Oliveira")
            .setContentIntent(pendingIntent)
            .build()
    }
    private fun getDeviceIdentifier(): String {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        return androidId ?: getOrCreateUUID()
    }
    private fun sendDataToServer(jsonData: String) {
        Thread {
            try {
                val url = URL("http://92.113.38.123:8080/api/monitoramento") // Ajuste a porta e rota
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.write(jsonData.toByteArray())
                conn.outputStream.flush()
                conn.outputStream.close()

                val responseCode = conn.responseCode
                Log.d(TAG, "POST Response Code: $responseCode")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao enviar dados: ${e.message}", e)
            }
        }.start()
    }

}
