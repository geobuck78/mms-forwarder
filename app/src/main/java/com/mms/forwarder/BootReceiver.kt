package com.mms.forwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 기기 부팅 시 모니터링 서비스 자동 시작
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            Log.i(TAG, "부팅 완료 / 앱 업데이트 감지 → 서비스 시작")

            val serviceIntent = Intent(context, MmsMonitorService::class.java).apply {
                putExtra(MmsMonitorService.EXTRA_ACTION, MmsMonitorService.ACTION_START_MONITORING)
            }

            try {
                context.startForegroundService(serviceIntent)
            } catch (e: Exception) {
                Log.e(TAG, "서비스 자동 시작 실패: ${e.message}")
            }
        }
    }
}
