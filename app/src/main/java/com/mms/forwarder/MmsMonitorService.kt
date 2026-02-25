package com.mms.forwarder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * MMS 수신 모니터링 포그라운드 서비스
 * - WAP Push 수신 시 MMS 처리
 * - ContentObserver로 MMS Inbox 변화 감지
 */
class MmsMonitorService : Service() {

    companion object {
        private const val TAG = "MmsMonitorService"
        const val EXTRA_ACTION = "action"
        const val EXTRA_DELAY_MS = "delay_ms"
        const val ACTION_PROCESS_NEW_MMS = "process_new_mms"
        const val ACTION_START_MONITORING = "start_monitoring"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "mms_forwarder_channel"
        private const val CHANNEL_NAME = "MMS Forwarder"

        // ContentObserver 감지 지연 (중복 호출 방지)
        private const val OBSERVER_DEBOUNCE_MS = 3000L
    }

    private lateinit var mmsHelper: MmsHelper
    private lateinit var n8nSender: N8nSender
    private var mmsContentObserver: ContentObserver? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isObserverRegistered = false

    // 디바운스용 Runnable
    private val processMmsRunnable = Runnable {
        processLatestMms(delay = 0)
    }

    override fun onCreate() {
        super.onCreate()
        mmsHelper = MmsHelper(this)
        n8nSender = N8nSender(this)
        createNotificationChannel()
        Log.i(TAG, "MmsMonitorService 생성됨")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 포그라운드 서비스로 즉시 승격
        startForeground(NOTIFICATION_ID, createNotification("MMS 감지 대기중..."))

        val action = intent?.getStringExtra(EXTRA_ACTION) ?: ACTION_START_MONITORING
        val delayMs = intent?.getLongExtra(EXTRA_DELAY_MS, 2000L) ?: 2000L

        Log.d(TAG, "onStartCommand: action=$action, delay=$delayMs ms")

        when (action) {
            ACTION_PROCESS_NEW_MMS -> {
                // WAP Push 수신 시: 지연 후 최신 MMS 처리
                processLatestMms(delay = delayMs)
            }
            ACTION_START_MONITORING -> {
                // ContentObserver 등록
                if (!isObserverRegistered) {
                    registerMmsObserver()
                }
            }
        }

        return START_STICKY
    }

    /**
     * 최신 MMS 처리 (백그라운드 스레드)
     */
    private fun processLatestMms(delay: Long) {
        Thread {
            try {
                if (delay > 0) {
                    Log.d(TAG, "${delay}ms 대기 후 MMS 처리...")
                    Thread.sleep(delay)
                }

                updateNotification("새 MMS 처리중...")
                val mms = mmsHelper.getLatestMms()

                if (mms == null) {
                    Log.w(TAG, "처리할 MMS 없음")
                    updateNotification("MMS 없음 (대기중)")
                    return@Thread
                }

                Log.i(TAG, "MMS 처리: ID=${mms.id}, 발신자=${mms.sender}, 텍스트=${mms.textParts.size}개")
                updateNotification("n8n으로 전송중... (발신: ${mms.sender})")

                val result = n8nSender.sendToN8nSync(mms)

                if (result.first) {
                    Log.i(TAG, "n8n 전송 성공: ${result.second}")
                    updateNotification("전송 완료 (${mms.timestampFormatted})")
                    broadcastResult(true, mms, result.second)
                } else {
                    Log.e(TAG, "n8n 전송 실패: ${result.second}")
                    updateNotification("전송 실패: ${result.second}")
                    broadcastResult(false, mms, result.second)
                }
            } catch (e: Exception) {
                Log.e(TAG, "MMS 처리 오류: ${e.message}")
                updateNotification("처리 오류: ${e.message}")
            }
        }.start()
    }

    /**
     * MMS Inbox ContentObserver 등록
     */
    private fun registerMmsObserver() {
        val mmsInboxUri = Uri.parse("content://mms/inbox")

        mmsContentObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                onChange(selfChange, null)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                Log.d(TAG, "MMS Inbox 변화 감지: uri=$uri, selfChange=$selfChange")

                // 디바운스: 짧은 시간 내 중복 호출 방지
                handler.removeCallbacks(processMmsRunnable)
                handler.postDelayed(processMmsRunnable, OBSERVER_DEBOUNCE_MS)
            }
        }

        contentResolver.registerContentObserver(
            mmsInboxUri,
            true,
            mmsContentObserver!!
        )
        isObserverRegistered = true
        Log.i(TAG, "MMS ContentObserver 등록 완료")
    }

    /**
     * 결과를 MainActivity로 브로드캐스트
     */
    private fun broadcastResult(success: Boolean, mms: MmsMessage, message: String) {
        val intent = Intent("com.mms.forwarder.MMS_FORWARDED").apply {
            putExtra("success", success)
            putExtra("mms_id", mms.id)
            putExtra("sender", mms.sender)
            putExtra("text", mms.textParts.joinToString("\n"))
            putExtra("timestamp", mms.timestampFormatted)
            putExtra("message", message)
        }
        sendBroadcast(intent)
    }

    /**
     * 알림 채널 생성 (Android 8.0+)
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "MMS 수신 및 n8n 전송 알림"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    /**
     * 포그라운드 알림 생성
     */
    private fun createNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MMS Forwarder")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * 알림 텍스트 업데이트
     */
    private fun updateNotification(contentText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, createNotification(contentText))
    }

    override fun onDestroy() {
        super.onDestroy()
        mmsContentObserver?.let {
            contentResolver.unregisterContentObserver(it)
        }
        handler.removeCallbacksAndMessages(null)
        isObserverRegistered = false
        Log.i(TAG, "MmsMonitorService 종료됨")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
