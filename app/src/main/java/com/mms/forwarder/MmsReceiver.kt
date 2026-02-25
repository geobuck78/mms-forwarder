package com.mms.forwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * MMS WAP Push 수신 BroadcastReceiver
 * - WAP_PUSH_DELIVER: 기본 SMS 앱일 때 수신
 * - WAP_PUSH_RECEIVED: 일반 앱일 때 수신
 */
class MmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MmsReceiver"
        private const val MMS_RECEIVED_ACTION = "android.provider.Telephony.WAP_PUSH_RECEIVED"
        private const val MMS_DELIVER_ACTION = "android.provider.Telephony.WAP_PUSH_DELIVER"
        private const val MMS_MIME_TYPE = "application/vnd.wap.mms-message"

        // MMS 처리 지연 시간 (ms) - ContentProvider에 저장될 때까지 대기
        private const val MMS_PROCESS_DELAY_MS = 2000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val mimeType = intent.type ?: ""

        Log.d(TAG, "onReceive: action=$action, mimeType=$mimeType")

        if ((action == MMS_RECEIVED_ACTION || action == MMS_DELIVER_ACTION)
            && mimeType == MMS_MIME_TYPE
        ) {
            Log.i(TAG, "MMS 수신 감지! 처리를 시작합니다.")

            // WakeLock 확보 후 서비스에서 처리
            val serviceIntent = Intent(context, MmsMonitorService::class.java).apply {
                putExtra(MmsMonitorService.EXTRA_ACTION, MmsMonitorService.ACTION_PROCESS_NEW_MMS)
                putExtra(MmsMonitorService.EXTRA_DELAY_MS, MMS_PROCESS_DELAY_MS)
            }

            try {
                context.startForegroundService(serviceIntent)
            } catch (e: Exception) {
                Log.e(TAG, "서비스 시작 실패: ${e.message}")
                // 폴백: 직접 처리
                processMmsDirectly(context)
            }
        }
    }

    /**
     * 서비스 시작 실패 시 직접 처리 (폴백)
     */
    private fun processMmsDirectly(context: Context) {
        Thread {
            try {
                Thread.sleep(MMS_PROCESS_DELAY_MS)
                val mmsHelper = MmsHelper(context)
                val n8nSender = N8nSender(context)
                val latestMms = mmsHelper.getLatestMms()
                if (latestMms != null) {
                    n8nSender.sendToN8n(latestMms)
                }
            } catch (e: Exception) {
                Log.e(TAG, "MMS 직접 처리 오류: ${e.message}")
            }
        }.start()
    }
}
