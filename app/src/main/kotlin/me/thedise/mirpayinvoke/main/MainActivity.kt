package me.thedise.mirpayinvoke.main

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.NfcManager
import android.content.ComponentName

class MainActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())

    // Кеш для ускорения повторных запусков
    private var cachedMirPayPackage: String? = null
    private var cachedGooglePayPackage: String? = null

    // Отслеживание NFC транзакций
    private var nfcAdapter: NfcAdapter? = null
    private var nfcReceiver: BroadcastReceiver? = null
    private var paymentStartTime: Long = 0

    // Флаги состояния запуска
    private var mirPayLaunchAttempted = false
    private var googlePayLaunchAttempted = false

    companion object {
        private const val TAG = "MainActivity"
        private val MIR_PAY_PACKAGES = listOf(
            "ru.nspk.mirpay",
            "ru.nspk.wallet",
            "com.mir.pay",
            "ru.mir.pay"
        )
        private val GOOGLE_PAY_PACKAGES = listOf(
            "com.google.android.apps.walletnfcrel",
            "com.google.android.apps.nfcprovision",
            "com.google.android.gms.nfc",
            "com.google.android.apps.wallet",
            "com.android.nfc",
            "com.samsung.android.spay"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "=== onCreate() called ===")
        Log.d(TAG, "Task ID: ${taskId}")
        Log.d(TAG, "Instance: ${this.hashCode()}")

        installSplashScreen()

        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        Log.d(TAG, "=== Initializing NFC monitoring ===")
        initNfcMonitoring()

        Log.d(TAG, "=== Starting payment sequence ===")
        resetLaunchFlags()
        paymentStartTime = System.currentTimeMillis()
        startPaymentSequence()

        Log.d(TAG, "=== onCreate() completed ===")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "=== onNewIntent() called ===")
        Log.d(TAG, "Intent action: ${intent.action}")
        Log.d(TAG, "Intent flags: ${intent.flags}")

        // При новом Intent перезапускаем последовательность
        if (intent.action == Intent.ACTION_MAIN) {
            Log.d(TAG, "=== Restarting payment sequence via onNewIntent ===")
            resetLaunchFlags()
            paymentStartTime = System.currentTimeMillis()
            startPaymentSequence()
        }
    }

    private fun resetLaunchFlags() {
        mirPayLaunchAttempted = false
        googlePayLaunchAttempted = false
        Log.d(TAG, "Launch flags reset")
    }

    private fun initNfcMonitoring() {
        try {
            val nfcManager = getSystemService(Context.NFC_SERVICE) as NfcManager
            nfcAdapter = nfcManager.defaultAdapter

            if (nfcAdapter != null) {
                Log.d(TAG, "NFC adapter initialized")
                setupNfcReceiver()
            } else {
                Log.w(TAG, "NFC not available on this device")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize NFC monitoring", e)
        }
    }

    private fun setupNfcReceiver() {
        nfcReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    NfcAdapter.ACTION_ADAPTER_STATE_CHANGED -> {
                        Log.d(TAG, "NFC adapter state changed")
                    }
                    "android.nfc.action.TECH_DISCOVERED",
                    "android.nfc.action.TAG_DISCOVERED",
                    "android.nfc.action.NDEF_DISCOVERED" -> {
                        Log.d(TAG, "=== NFC transaction detected! ===")
                        onNfcTransactionDetected()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
            addAction("android.nfc.action.TECH_DISCOVERED")
            addAction("android.nfc.action.TAG_DISCOVERED")
            addAction("android.nfc.action.NDEF_DISCOVERED")
        }

        registerReceiver(nfcReceiver, filter)
        Log.d(TAG, "NFC receiver registered")
    }

    private fun onNfcTransactionDetected() {
        Log.d(TAG, "Payment transaction detected, will close apps in 3 seconds")

        handler.postDelayed({
            closePaymentApps()
        }, 3000)
    }

    private fun closePaymentApps() {
        try {
            Log.d(TAG, "Minimizing payment applications")
            goToHomeScreen()

            handler.postDelayed({
                Log.d(TAG, "Payment sequence completed, closing launcher")
                finish()
            }, 1000)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to minimize payment apps", e)
        }
    }

    private fun goToHomeScreen() {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            if (homeIntent.resolveActivity(packageManager) != null) {
                startActivity(homeIntent)
                Log.d(TAG, "Sent to home screen - payment apps minimized")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to go to home screen", e)
        }
    }

    fun startPaymentSequence() {
        Log.d(TAG, "Starting payment sequence")
        Log.d(TAG, "Cached Mir Pay: $cachedMirPayPackage")
        Log.d(TAG, "Cached Google Pay: $cachedGooglePayPackage")

        // Проверяем состояние приложений
        checkAppStates()

        // Запускаем последовательность с гарантированным запуском обоих приложений
        launchBothAppsSequentially()

        // Альтернативное отслеживание
        handler.postDelayed({
            if (System.currentTimeMillis() - paymentStartTime > 25000) {
                Log.d(TAG, "Payment timeout reached, closing apps")
                closePaymentApps()
            }
        }, 30000)

        startPaymentMonitoring()
    }

    private fun checkAppStates() {
        if (cachedMirPayPackage != null) {
            val mirPayState = getAppState(cachedMirPayPackage!!)
            Log.d(TAG, "Mir Pay ($cachedMirPayPackage) state: $mirPayState")
        }

        if (cachedGooglePayPackage != null) {
            val googlePayState = getAppState(cachedGooglePayPackage!!)
            Log.d(TAG, "Google Pay ($cachedGooglePayPackage) state: $googlePayState")
        }
    }

    private fun getAppState(packageName: String): String {
        return try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val runningProcesses = activityManager.runningAppProcesses

            for (processInfo in runningProcesses) {
                if (processInfo.processName == packageName) {
                    return when (processInfo.importance) {
                        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
                        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
                        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "BACKGROUND_SERVICE"
                        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED"
                        else -> "BACKGROUND (${processInfo.importance})"
                    }
                }
            }
            "NOT_RUNNING"
        } catch (e: Exception) {
            Log.e(TAG, "Error checking app state", e)
            "ERROR"
        }
    }

    private fun launchBothAppsSequentially() {
        Log.d(TAG, "=== Starting sequential app launch ===")

        // Сначала запускаем Mir Pay
        launchMirPay { mirPaySuccess ->
            Log.d(TAG, "Mir Pay launch result: $mirPaySuccess")

            if (mirPaySuccess && cachedMirPayPackage != null) {
                // Если Mir Pay успешно запущен, ждем его полной загрузки
                monitorMirPayLaunch(cachedMirPayPackage!!)
            } else {
                // Если Mir Pay не запустился, запускаем Google Pay сразу
                Log.w(TAG, "Mir Pay failed to launch, starting Google Pay immediately")
                launchGooglePay { googlePaySuccess ->
                    Log.d(TAG, "Google Pay launch result: $googlePaySuccess")
                    Log.d(TAG, "=== Sequential launch completed ===")
                }
            }
        }
    }

    private fun launchMirPay(callback: (Boolean) -> Unit) {
        if (mirPayLaunchAttempted) {
            Log.d(TAG, "Mir Pay launch already attempted")
            callback(true)
            return
        }

        mirPayLaunchAttempted = true

        try {
            Log.d(TAG, "Launching Mir Pay")

            var mirPayLaunched = false
            var launchedPackageName = ""

            // Сначала пробуем кешированный пакет
            if (cachedMirPayPackage != null && isAppInstalled(cachedMirPayPackage!!)) {
                Log.d(TAG, "Using cached Mir Pay package: $cachedMirPayPackage")

                if (launchAppWithOptimalFlags(cachedMirPayPackage!!)) {
                    mirPayLaunched = true
                    launchedPackageName = cachedMirPayPackage!!
                }
            }

            // Если кеш не сработал, ищем среди всех пакетов
            if (!mirPayLaunched) {
                for (packageName in MIR_PAY_PACKAGES) {
                    if (isAppInstalled(packageName)) {
                        Log.d(TAG, "Found Mir Pay package: $packageName")
                        cachedMirPayPackage = packageName

                        if (launchAppWithOptimalFlags(packageName)) {
                            mirPayLaunched = true
                            launchedPackageName = packageName
                            break
                        }
                    }
                }
            }

            if (!mirPayLaunched) {
                Log.e(TAG, "Failed to launch any Mir Pay package")
                cachedMirPayPackage = null
            } else {
                Log.d(TAG, "Mir Pay launched successfully: $launchedPackageName")
            }

            callback(mirPayLaunched)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Mir Pay", e)
            cachedMirPayPackage = null
            callback(false)
        }
    }

    private fun launchGooglePay(callback: (Boolean) -> Unit) {
        if (googlePayLaunchAttempted) {
            Log.d(TAG, "Google Pay launch already attempted")
            callback(true)
            return
        }

        googlePayLaunchAttempted = true

        try {
            Log.d(TAG, "Launching Google Pay")

            var googlePayLaunched = false

            // Сначала пробуем кешированный пакет
            if (cachedGooglePayPackage != null && isAppInstalled(cachedGooglePayPackage!!)) {
                Log.d(TAG, "Using cached Google Pay package: $cachedGooglePayPackage")

                if (launchAppWithOptimalFlags(cachedGooglePayPackage!!)) {
                    googlePayLaunched = true
                }
            }

            // Если кеш не сработал, ищем среди всех пакетов
            if (!googlePayLaunched) {
                for (packageName in GOOGLE_PAY_PACKAGES) {
                    if (isAppInstalled(packageName)) {
                        Log.d(TAG, "Found Google Pay package: $packageName")
                        cachedGooglePayPackage = packageName

                        if (launchAppWithOptimalFlags(packageName)) {
                            googlePayLaunched = true
                            break
                        }
                    }
                }
            }

            if (!googlePayLaunched) {
                Log.w(TAG, "Failed to launch any Google Pay package")
                cachedGooglePayPackage = null
            } else {
                Log.d(TAG, "Google Pay launched successfully")
            }

            callback(googlePayLaunched)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Google Pay", e)
            cachedGooglePayPackage = null
            callback(false)
        }
    }

    private fun monitorMirPayLaunch(packageName: String) {
        Log.d(TAG, "Starting Mir Pay launch monitoring for: $packageName")

        val checkInterval = 500L // Проверяем каждые 500мс
        val maxChecks = 10 // Максимум 5 секунд ожидания
        var checkCount = 0

        val checkRunnable = object : Runnable {
            override fun run() {
                checkCount++
                val appState = getAppState(packageName)
                Log.d(TAG, "Mir Pay state check #$checkCount: $appState")

                // Считаем приложение запущенным если оно в FOREGROUND или VISIBLE состоянии
                if (appState == "FOREGROUND" || appState == "VISIBLE") {
                    Log.d(TAG, "=== Mir Pay is now active, waiting 1 second before launching Google Pay ===")
                    // Небольшая дополнительная задержка для стабилизации
                    handler.postDelayed({
                        launchGooglePay { googlePaySuccess ->
                            Log.d(TAG, "Google Pay launch result: $googlePaySuccess")
                            Log.d(TAG, "=== Sequential launch completed ===")
                        }
                    }, 1000)
                    return
                }

                // Если приложение в CACHED состоянии (свернуто), тоже считаем успешным
                if (appState.startsWith("BACKGROUND") || appState == "CACHED") {
                    Log.d(TAG, "=== Mir Pay is running in background, launching Google Pay ===")
                    handler.postDelayed({
                        launchGooglePay { googlePaySuccess ->
                            Log.d(TAG, "Google Pay launch result: $googlePaySuccess")
                            Log.d(TAG, "=== Sequential launch completed ===")
                        }
                    }, 500)
                    return
                }

                if (checkCount < maxChecks) {
                    handler.postDelayed(this, checkInterval)
                } else {
                    Log.w(TAG, "Mir Pay launch timeout, launching Google Pay anyway")
                    launchGooglePay { googlePaySuccess ->
                        Log.d(TAG, "Google Pay launch result: $googlePaySuccess")
                        Log.d(TAG, "=== Sequential launch completed (timeout) ===")
                    }
                }
            }
        }

        // Начинаем проверку через 500мс после команды запуска
        handler.postDelayed(checkRunnable, 500)
    }

    private fun launchAppWithOptimalFlags(packageName: String): Boolean {
        try {
            // Определяем текущее состояние приложения
            val appState = getAppState(packageName)
            Log.d(TAG, "Launching $packageName (current state: $appState)")

            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                // Очищаем все флаги
                intent.flags = 0

                // Устанавливаем оптимальные флаги в зависимости от состояния
                when (appState) {
                    "FOREGROUND" -> {
                        // Приложение уже активно - просто выводим на передний план
                        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    "VISIBLE", "CACHED", "BACKGROUND_SERVICE" -> {
                        // Приложение свернуто - выводим на передний план
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        intent.addFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT)
                        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    }
                    "NOT_RUNNING" -> {
                        // Приложение не запущено - обычный запуск
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    else -> {
                        // Универсальные флаги для неопределенного состояния
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        intent.addFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT)
                    }
                }

                startActivity(intent)
                Log.d(TAG, "App launched with flags: ${intent.flags} ($packageName)")
                return true
            } else {
                // Пробуем альтернативный запуск
                return forceStartApp(packageName)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app: $packageName", e)
            return false
        }
    }

    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun startPaymentMonitoring() {
        val monitoringRunnable = object : Runnable {
            override fun run() {
                try {
                    val elapsed = System.currentTimeMillis() - paymentStartTime

                    if (elapsed > 60000) {
                        Log.d(TAG, "Payment session too long, finishing launcher")
                        finish()
                        return
                    }

                    handler.postDelayed(this, 5000)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in payment monitoring", e)
                }
            }
        }

        handler.postDelayed(monitoringRunnable, 10000)
    }

    override fun onDestroy() {
        Log.d(TAG, "=== onDestroy() called ===")

        try {
            if (nfcReceiver != null) {
                unregisterReceiver(nfcReceiver)
                Log.d(TAG, "NFC receiver unregistered")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister NFC receiver", e)
        }

        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Handler callbacks cleared")

        super.onDestroy()
        Log.d(TAG, "=== onDestroy() completed ===")
    }

    private fun forceStartApp(packageName: String): Boolean {
        try {
            Log.d(TAG, "Force starting app: $packageName")

            // Метод через ACTION_MAIN
            val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val resolveInfos = packageManager.queryIntentActivities(mainIntent, 0)
            if (resolveInfos.isNotEmpty()) {
                val activityInfo = resolveInfos[0].activityInfo
                mainIntent.component = ComponentName(activityInfo.packageName, activityInfo.name)
                startActivity(mainIntent)
                Log.d(TAG, "Force started via ACTION_MAIN: $packageName")
                return true
            }

        } catch (e: Exception) {
            Log.e(TAG, "Force start failed for $packageName", e)
        }

        return false
    }
}