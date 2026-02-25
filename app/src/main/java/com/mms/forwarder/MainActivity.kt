package com.mms.forwarder

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"

        private val REQUIRED_PERMISSIONS = buildList {
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.RECEIVE_MMS)
            add(Manifest.permission.READ_SMS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    // UI Views
    private lateinit var etWebhookUrl: EditText
    private lateinit var etWebhookSecret: EditText
    private lateinit var switchSendImages: Switch
    private lateinit var btnSaveSettings: Button
    private lateinit var btnTestWebhook: Button
    private lateinit var btnStartService: Button
    private lateinit var btnStopService: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollViewLog: ScrollView
    private lateinit var tvPermissionStatus: TextView

    private lateinit var n8nSender: N8nSender
    private lateinit var mmsHelper: MmsHelper

    // MMS 전달 결과 수신기
    private val mmsForwardedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra("success", false)
            val sender = intent.getStringExtra("sender") ?: ""
            val text = intent.getStringExtra("text") ?: ""
            val timestamp = intent.getStringExtra("timestamp") ?: ""
            val message = intent.getStringExtra("message") ?: ""

            val icon = if (success) "✅" else "❌"
            val logEntry = "$icon [$timestamp]\n   발신: $sender\n   내용: ${text.take(50)}${if (text.length > 50) "..." else ""}\n   상태: $message"

            runOnUiThread {
                addLog(logEntry)
                tvStatus.text = if (success) "마지막 전송: 성공" else "마지막 전송: 실패"
                tvStatus.setTextColor(
                    if (success) getColor(android.R.color.holo_green_dark)
                    else getColor(android.R.color.holo_red_dark)
                )
            }
        }
    }

    // 권한 요청 결과 처리
    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            addLog("✅ 모든 권한이 허용되었습니다.")
            updatePermissionStatus()
            startMonitoringService()
        } else {
            val denied = permissions.filter { !it.value }.keys.joinToString(", ")
            addLog("❌ 권한 거부됨: $denied")
            updatePermissionStatus()
            Toast.makeText(this, "SMS/MMS 권한이 필요합니다.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        n8nSender = N8nSender(this)
        mmsHelper = MmsHelper(this)

        initViews()
        loadSettings()
        updatePermissionStatus()
        checkAndRequestPermissions()
        registerMmsForwardedReceiver()
    }

    private fun initViews() {
        etWebhookUrl = findViewById(R.id.etWebhookUrl)
        etWebhookSecret = findViewById(R.id.etWebhookSecret)
        switchSendImages = findViewById(R.id.switchSendImages)
        btnSaveSettings = findViewById(R.id.btnSaveSettings)
        btnTestWebhook = findViewById(R.id.btnTestWebhook)
        btnStartService = findViewById(R.id.btnStartService)
        btnStopService = findViewById(R.id.btnStopService)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        scrollViewLog = findViewById(R.id.scrollViewLog)
        tvPermissionStatus = findViewById(R.id.tvPermissionStatus)

        btnSaveSettings.setOnClickListener { saveSettings() }
        btnTestWebhook.setOnClickListener { testWebhook() }
        btnStartService.setOnClickListener { startMonitoringService() }
        btnStopService.setOnClickListener { stopMonitoringService() }
    }

    private fun loadSettings() {
        etWebhookUrl.setText(n8nSender.webhookUrl)
        etWebhookSecret.setText(n8nSender.webhookSecret)
        switchSendImages.isChecked = n8nSender.sendImages
    }

    private fun saveSettings() {
        val url = etWebhookUrl.text.toString().trim()
        val secret = etWebhookSecret.text.toString().trim()

        if (url.isBlank()) {
            Toast.makeText(this, "웹훅 URL을 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, "올바른 URL 형식을 입력해주세요. (http:// 또는 https://)", Toast.LENGTH_LONG).show()
            return
        }

        n8nSender.webhookUrl = url
        n8nSender.webhookSecret = secret
        n8nSender.sendImages = switchSendImages.isChecked

        Toast.makeText(this, "설정이 저장되었습니다.", Toast.LENGTH_SHORT).show()
        addLog("⚙️ 설정 저장: URL=${url.take(40)}...")
    }

    private fun testWebhook() {
        val url = etWebhookUrl.text.toString().trim()
        if (url.isBlank()) {
            Toast.makeText(this, "먼저 웹훅 URL을 저장해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        // 임시 저장
        n8nSender.webhookUrl = url
        n8nSender.webhookSecret = etWebhookSecret.text.toString().trim()

        btnTestWebhook.isEnabled = false
        btnTestWebhook.text = "테스트 중..."
        addLog("🔄 웹훅 연결 테스트 중...")

        n8nSender.testWebhook { success, message ->
            runOnUiThread {
                btnTestWebhook.isEnabled = true
                btnTestWebhook.text = "연결 테스트"
                if (success) {
                    addLog("✅ 테스트 성공: $message")
                    Toast.makeText(this, "연결 성공!", Toast.LENGTH_SHORT).show()
                } else {
                    addLog("❌ 테스트 실패: $message")
                    Toast.makeText(this, "연결 실패: $message", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startMonitoringService() {
        val intent = Intent(this, MmsMonitorService::class.java).apply {
            putExtra(MmsMonitorService.EXTRA_ACTION, MmsMonitorService.ACTION_START_MONITORING)
        }
        try {
            startForegroundService(intent)
            addLog("🟢 모니터링 서비스 시작됨")
            tvStatus.text = "서비스 실행 중"
            tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        } catch (e: Exception) {
            addLog("❌ 서비스 시작 실패: ${e.message}")
        }
    }

    private fun stopMonitoringService() {
        val intent = Intent(this, MmsMonitorService::class.java)
        stopService(intent)
        addLog("🔴 모니터링 서비스 중지됨")
        tvStatus.text = "서비스 중지됨"
        tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
    }

    private fun checkAndRequestPermissions() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            addLog("✅ 모든 권한 확인됨")
            startMonitoringService()
        } else {
            addLog("⚠️ 권한 요청 필요: ${missing.size}개")
            requestPermissions.launch(missing.toTypedArray())
        }
    }

    private fun updatePermissionStatus() {
        val grantedCount = REQUIRED_PERMISSIONS.count {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        val total = REQUIRED_PERMISSIONS.size
        tvPermissionStatus.text = "권한: $grantedCount/$total 허용됨"
        tvPermissionStatus.setTextColor(
            if (grantedCount == total) getColor(android.R.color.holo_green_dark)
            else getColor(android.R.color.holo_orange_dark)
        )
    }

    private fun registerMmsForwardedReceiver() {
        val filter = IntentFilter("com.mms.forwarder.MMS_FORWARDED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mmsForwardedReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(mmsForwardedReceiver, filter)
        }
    }

    private fun addLog(message: String) {
        val currentLog = tvLog.text.toString()
        val newLog = if (currentLog.isBlank()) message else "$currentLog\n$message"
        tvLog.text = newLog

        // 자동 스크롤
        scrollViewLog.post {
            scrollViewLog.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(mmsForwardedReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Receiver 해제 오류: ${e.message}")
        }
    }
}
