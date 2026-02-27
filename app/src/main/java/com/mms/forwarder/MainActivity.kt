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

        // 전송 범위 옵션 (label → limit, 0=전체)
        private val LIMIT_OPTIONS = listOf(
            "최근 10개"   to 10,
            "최근 30개"   to 30,
            "최근 50개"   to 50,
            "최근 100개"  to 100,
            "최근 200개"  to 200,
            "전체 (제한 없음)" to 0
        )
    }

    // ── 기존 Views ──────────────────────────────────────
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

    // ── 배치 전송 Views ──────────────────────────────────
    private lateinit var spinnerMmsLimit: Spinner
    private lateinit var tvMmsCount: TextView
    private lateinit var progressBarBatch: ProgressBar
    private lateinit var tvBatchStatus: TextView
    private lateinit var checkBoxDeleteAfterSend: android.widget.CheckBox
    private lateinit var btnSendAllMms: Button
    private lateinit var btnCancelBatch: Button

    private lateinit var n8nSender: N8nSender
    private lateinit var mmsHelper: MmsHelper

    // 실시간 MMS 전달 결과 수신기
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
        // 기존 views
        etWebhookUrl      = findViewById(R.id.etWebhookUrl)
        etWebhookSecret   = findViewById(R.id.etWebhookSecret)
        switchSendImages  = findViewById(R.id.switchSendImages)
        btnSaveSettings   = findViewById(R.id.btnSaveSettings)
        btnTestWebhook    = findViewById(R.id.btnTestWebhook)
        btnStartService   = findViewById(R.id.btnStartService)
        btnStopService    = findViewById(R.id.btnStopService)
        tvStatus          = findViewById(R.id.tvStatus)
        tvLog             = findViewById(R.id.tvLog)
        scrollViewLog     = findViewById(R.id.scrollViewLog)
        tvPermissionStatus = findViewById(R.id.tvPermissionStatus)

        // 배치 전송 views
        spinnerMmsLimit        = findViewById(R.id.spinnerMmsLimit)
        tvMmsCount             = findViewById(R.id.tvMmsCount)
        progressBarBatch       = findViewById(R.id.progressBarBatch)
        tvBatchStatus          = findViewById(R.id.tvBatchStatus)
        checkBoxDeleteAfterSend = findViewById(R.id.checkBoxDeleteAfterSend)
        btnSendAllMms          = findViewById(R.id.btnSendAllMms)
        btnCancelBatch         = findViewById(R.id.btnCancelBatch)

        // 기존 버튼 이벤트
        btnSaveSettings.setOnClickListener { saveSettings() }
        btnTestWebhook.setOnClickListener  { testWebhook() }
        btnStartService.setOnClickListener { startMonitoringService() }
        btnStopService.setOnClickListener  { stopMonitoringService() }

        // 배치 전송 스피너 설정
        setupLimitSpinner()

        // 배치 전송 버튼 이벤트
        btnSendAllMms.setOnClickListener  { startBatchSend() }
        btnCancelBatch.setOnClickListener { n8nSender.cancelBatch(); addLog("⛔ 배치 전송 취소 요청됨") }
    }

    // ── 스피너 설정 ────────────────────────────────────────
    private fun setupLimitSpinner() {
        val labels = LIMIT_OPTIONS.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerMmsLimit.adapter = adapter
        spinnerMmsLimit.setSelection(2)   // 기본값: 최근 50개

        // 스피너 선택 시 MMS 개수 미리 표시
        spinnerMmsLimit.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                refreshMmsCount()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    // ── MMS 개수 미리 조회 ──────────────────────────────────
    private fun refreshMmsCount() {
        Thread {
            val total = mmsHelper.getMmsCount(onlyInbox = false)
            runOnUiThread {
                tvMmsCount.text = "기기에 저장된 MMS: 총 ${total}개"
            }
        }.start()
    }

    // ── 배치 전송 시작 ──────────────────────────────────────
    private fun startBatchSend() {
        val url = etWebhookUrl.text.toString().trim().ifBlank { n8nSender.webhookUrl }
        if (url.isBlank()) {
            Toast.makeText(this, "먼저 웹훅 URL을 저장해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedPos = spinnerMmsLimit.selectedItemPosition
        val limit = LIMIT_OPTIONS[selectedPos].second
        val limitLabel = LIMIT_OPTIONS[selectedPos].first

        // 삭제 옵션 UI 스레드에서 미리 읽기 (백그라운드에서 UI 접근 금지)
        val shouldDelete = checkBoxDeleteAfterSend.isChecked

        // 삭제 옵션 켜져 있으면 최종 확인 다이얼로그
        if (shouldDelete) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("⚠ MMS 삭제 확인")
                .setMessage("전송 완료된 MMS를 기기에서 삭제합니다.\n삭제된 메시지는 복구할 수 없습니다.\n\n계속하시겠습니까?")
                .setPositiveButton("삭제하며 전송") { _, _ ->
                    executeBatchSend(url, limit, limitLabel, shouldDelete = true)
                }
                .setNegativeButton("취소", null)
                .show()
        } else {
            executeBatchSend(url, limit, limitLabel, shouldDelete = false)
        }
    }

    private fun executeBatchSend(url: String, limit: Int, limitLabel: String, shouldDelete: Boolean) {
        // 임시 설정 반영
        n8nSender.webhookUrl = url
        n8nSender.webhookSecret = etWebhookSecret.text.toString().trim()

        // UI → 전송 중 상태
        setBatchUiRunning(true)
        progressBarBatch.progress = 0
        val deleteLabel = if (shouldDelete) " + 삭제" else ""
        addLog("🔍 MMS 검색 시작 ($limitLabel$deleteLabel)...")
        tvBatchStatus.text = "MMS 목록 불러오는 중..."

        // 백그라운드에서 MMS 목록 조회 후 전송
        Thread {
            val messages = mmsHelper.getAllMmsMessages(limit = limit, onlyInbox = false)
            val foundCount = messages.size

            if (foundCount == 0) {
                runOnUiThread {
                    setBatchUiRunning(false)
                    tvBatchStatus.text = "전송할 MMS가 없습니다."
                    addLog("⚠️ 검색된 MMS가 없습니다.")
                }
                return@Thread
            }

            runOnUiThread {
                addLog("📨 MMS ${foundCount}개 발견 → n8n 전송 시작...")
                tvBatchStatus.text = "0 / ${foundCount} 전송 중..."
                progressBarBatch.max = foundCount
            }

            var deletedCount = 0
            var deleteFailedCount = 0

            // 배치 전송
            n8nSender.sendBatchToN8n(
                messages = messages,
                delayBetweenMs = 400L,
                onProgress = { current, total, success, statusMsg ->
                    val icon = if (success) "✅" else "❌"
                    val msg = messages[current - 1]
                    val preview = msg.textParts.firstOrNull()?.take(30) ?: "(텍스트 없음)"

                    // 전송 성공 + 삭제 옵션 켜져 있으면 삭제 (백그라운드 스레드에서 실행)
                    var deleteResult = ""
                    if (success && shouldDelete) {
                        val deleted = mmsHelper.deleteMms(msg.id)
                        if (deleted) {
                            deletedCount++
                            deleteResult = " 🗑삭제됨"
                        } else {
                            deleteFailedCount++
                            deleteResult = " ⚠삭제실패"
                        }
                    }

                    runOnUiThread {
                        progressBarBatch.progress = current
                        tvBatchStatus.text = "$icon $current / $total  |  ${msg.sender}  \"$preview\"$deleteResult"
                        if (!success) {
                            addLog("❌ [$current/$total] ${msg.sender} 전송 실패: $statusMsg")
                        }
                    }
                },
                onComplete = { sent, failed ->
                    runOnUiThread {
                        setBatchUiRunning(false)
                        val deleteInfo = if (shouldDelete)
                            " | 삭제 성공 ${deletedCount}개${if (deleteFailedCount > 0) ", 실패 ${deleteFailedCount}개" else ""}"
                        else ""
                        val summary = "✅ 배치 완료: 전송 ${sent}개 성공, ${failed}개 실패$deleteInfo"
                        tvBatchStatus.text = summary
                        addLog("📦 $summary")

                        // 삭제 실패 시 안내
                        if (shouldDelete && deleteFailedCount > 0) {
                            addLog("⚠️ MMS 삭제 실패 ${deleteFailedCount}개: 기본 SMS 앱으로 설정하면 삭제 가능합니다.")
                        }

                        Toast.makeText(
                            this,
                            "전송 완료: 성공 ${sent}개, 실패 ${failed}개",
                            Toast.LENGTH_LONG
                        ).show()

                        // 삭제 후 MMS 개수 갱신
                        if (shouldDelete && deletedCount > 0) refreshMmsCount()
                    }
                }
            )
        }.start()
    }

    // ── 배치 UI 상태 토글 ───────────────────────────────────
    private fun setBatchUiRunning(running: Boolean) {
        btnSendAllMms.isEnabled          = !running
        btnCancelBatch.isEnabled         = running
        spinnerMmsLimit.isEnabled        = !running
        checkBoxDeleteAfterSend.isEnabled = !running
        progressBarBatch.visibility      = if (running) View.VISIBLE else View.GONE
    }

    // ── 기존 기능들 ────────────────────────────────────────
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

        n8nSender.webhookUrl    = url
        n8nSender.webhookSecret = secret
        n8nSender.sendImages    = switchSendImages.isChecked

        Toast.makeText(this, "설정이 저장되었습니다.", Toast.LENGTH_SHORT).show()
        addLog("⚙️ 설정 저장: URL=${url.take(40)}...")

        // 설정 저장 후 MMS 개수 갱신
        refreshMmsCount()
    }

    private fun testWebhook() {
        val url = etWebhookUrl.text.toString().trim()
        if (url.isBlank()) {
            Toast.makeText(this, "먼저 웹훅 URL을 저장해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        n8nSender.webhookUrl    = url
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
            refreshMmsCount()
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
