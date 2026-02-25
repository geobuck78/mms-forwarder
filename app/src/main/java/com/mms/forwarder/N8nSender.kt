package com.mms.forwarder

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * n8n 웹훅으로 MMS 데이터를 전송하는 클래스
 */
class N8nSender(private val context: Context) {

    companion object {
        private const val TAG = "N8nSender"
        const val PREF_NAME = "mms_forwarder_prefs"
        const val PREF_WEBHOOK_URL = "webhook_url"
        const val PREF_WEBHOOK_SECRET = "webhook_secret"
        const val PREF_SEND_IMAGES = "send_images"
        const val PREF_RETRY_COUNT = "retry_count"
        const val PREF_TIMEOUT_MS = "timeout_ms"
        const val PREF_INCLUDE_RAW_TEXT = "include_raw_text"

        // 기본값
        const val DEFAULT_TIMEOUT_MS = 30_000
        const val DEFAULT_RETRY_COUNT = 3
        private const val RETRY_DELAY_MS = 2000L
    }

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private val executor = Executors.newSingleThreadExecutor()

    /**
     * n8n 웹훅 URL 설정
     */
    var webhookUrl: String
        get() = prefs.getString(PREF_WEBHOOK_URL, "") ?: ""
        set(value) = prefs.edit().putString(PREF_WEBHOOK_URL, value).apply()

    /**
     * 웹훅 시크릿 키 (선택사항)
     */
    var webhookSecret: String
        get() = prefs.getString(PREF_WEBHOOK_SECRET, "") ?: ""
        set(value) = prefs.edit().putString(PREF_WEBHOOK_SECRET, value).apply()

    /**
     * 이미지 전송 여부
     */
    var sendImages: Boolean
        get() = prefs.getBoolean(PREF_SEND_IMAGES, true)
        set(value) = prefs.edit().putBoolean(PREF_SEND_IMAGES, value).apply()

    /**
     * MMS 데이터를 n8n 웹훅으로 비동기 전송
     */
    fun sendToN8n(mms: MmsMessage, onResult: ((Boolean, String) -> Unit)? = null) {
        val url = webhookUrl
        if (url.isBlank()) {
            Log.w(TAG, "웹훅 URL이 설정되지 않았습니다.")
            onResult?.invoke(false, "웹훅 URL이 설정되지 않았습니다.")
            return
        }

        executor.submit {
            val retryCount = prefs.getInt(PREF_RETRY_COUNT, DEFAULT_RETRY_COUNT)
            var lastError = ""
            var success = false

            for (attempt in 1..retryCount) {
                try {
                    Log.d(TAG, "n8n 전송 시도 $attempt/$retryCount - MMS ID: ${mms.id}")
                    val payload = buildPayload(mms)
                    val result = postToWebhook(url, payload)

                    if (result.first) {
                        Log.i(TAG, "n8n 전송 성공! 응답 코드: ${result.second}")
                        success = true
                        onResult?.invoke(true, "전송 성공 (HTTP ${result.second})")
                        break
                    } else {
                        lastError = "HTTP 오류: ${result.second}"
                        Log.w(TAG, "$lastError (시도 $attempt/$retryCount)")
                    }
                } catch (e: Exception) {
                    lastError = e.message ?: "알 수 없는 오류"
                    Log.e(TAG, "전송 오류 (시도 $attempt/$retryCount): $lastError")
                }

                if (attempt < retryCount) {
                    Thread.sleep(RETRY_DELAY_MS * attempt)
                }
            }

            if (!success) {
                onResult?.invoke(false, "전송 실패: $lastError")
            }
        }
    }

    /**
     * 동기 방식으로 전송 (서비스 내부에서 사용)
     */
    fun sendToN8nSync(mms: MmsMessage): Pair<Boolean, String> {
        val url = webhookUrl
        if (url.isBlank()) {
            return Pair(false, "웹훅 URL이 설정되지 않았습니다.")
        }

        val retryCount = prefs.getInt(PREF_RETRY_COUNT, DEFAULT_RETRY_COUNT)
        var lastError = ""

        for (attempt in 1..retryCount) {
            try {
                Log.d(TAG, "n8n 동기 전송 시도 $attempt/$retryCount")
                val payload = buildPayload(mms)
                val result = postToWebhook(url, payload)

                if (result.first) {
                    return Pair(true, "전송 성공 (HTTP ${result.second})")
                } else {
                    lastError = "HTTP 오류: ${result.second}"
                }
            } catch (e: Exception) {
                lastError = e.message ?: "알 수 없는 오류"
                Log.e(TAG, "동기 전송 오류 (시도 $attempt): $lastError")
            }

            if (attempt < retryCount) {
                Thread.sleep(RETRY_DELAY_MS * attempt)
            }
        }

        return Pair(false, "전송 실패: $lastError")
    }

    /**
     * MMS 데이터를 JSON 페이로드로 변환
     *
     * n8n 웹훅으로 전달되는 JSON 구조:
     * {
     *   "event": "mms_received",
     *   "timestamp": "2024-01-01 12:00:00",
     *   "timestamp_unix": 1704067200000,
     *   "mms_id": 1,
     *   "thread_id": 1,
     *   "sender": "01012345678",
     *   "recipients": ["01098765432"],
     *   "subject": "제목",
     *   "text": "메시지 내용 (전체 텍스트 파트 합치기)",
     *   "text_parts": ["파트1", "파트2"],
     *   "has_images": true,
     *   "images": [
     *     {
     *       "mime_type": "image/jpeg",
     *       "name": "photo.jpg",
     *       "data": "base64_encoded_data"
     *     }
     *   ]
     * }
     */
    private fun buildPayload(mms: MmsMessage): String {
        val json = JSONObject().apply {
            put("event", "mms_received")
            put("timestamp", mms.timestampFormatted)
            put("timestamp_unix", mms.timestamp)
            put("mms_id", mms.id)
            put("thread_id", mms.threadId)
            put("sender", mms.sender)

            // 수신자 목록
            put("recipients", JSONArray().also { arr ->
                mms.recipients.forEach { arr.put(it) }
            })

            // 제목 (없으면 null)
            put("subject", mms.subject ?: JSONObject.NULL)

            // 텍스트 내용
            val fullText = mms.textParts.joinToString("\n")
            put("text", fullText)
            put("text_parts", JSONArray().also { arr ->
                mms.textParts.forEach { arr.put(it) }
            })

            // 이미지
            put("has_images", mms.imageParts.isNotEmpty())

            if (sendImages && mms.imageParts.isNotEmpty()) {
                put("images", JSONArray().also { arr ->
                    mms.imageParts.forEach { img ->
                        arr.put(JSONObject().apply {
                            put("mime_type", img.mimeType)
                            put("name", img.name ?: JSONObject.NULL)
                            put("data", "data:${img.mimeType};base64,${img.base64Data}")
                        })
                    }
                })
            } else {
                put("images", JSONArray())
            }

            put("image_count", mms.imageParts.size)
        }

        return json.toString()
    }

    /**
     * HTTP POST 요청 실행
     * @return Pair(성공여부, HTTP 응답코드)
     */
    private fun postToWebhook(urlString: String, payload: String): Pair<Boolean, Int> {
        val timeoutMs = prefs.getInt(PREF_TIMEOUT_MS, DEFAULT_TIMEOUT_MS)
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "MmsForwarder-Android/1.0")

                // 시크릿 키가 있으면 헤더에 추가
                val secret = webhookSecret
                if (secret.isNotBlank()) {
                    setRequestProperty("X-Webhook-Secret", secret)
                }

                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doOutput = true
                doInput = true
            }

            // 요청 본문 전송
            val writer = OutputStreamWriter(connection.outputStream, Charsets.UTF_8)
            writer.write(payload)
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            Log.d(TAG, "웹훅 응답: HTTP $responseCode")

            // 2xx 코드는 성공
            Pair(responseCode in 200..299, responseCode)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 웹훅 연결 테스트
     */
    fun testWebhook(onResult: (Boolean, String) -> Unit) {
        val url = webhookUrl
        if (url.isBlank()) {
            onResult(false, "웹훅 URL을 먼저 입력해주세요.")
            return
        }

        executor.submit {
            try {
                val testPayload = JSONObject().apply {
                    put("event", "test")
                    put("message", "MMS Forwarder 연결 테스트")
                    put("timestamp", System.currentTimeMillis())
                    put("app_version", "1.0.0")
                }.toString()

                val result = postToWebhook(url, testPayload)
                if (result.first) {
                    onResult(true, "연결 성공! (HTTP ${result.second})")
                } else {
                    onResult(false, "연결 실패: HTTP ${result.second}")
                }
            } catch (e: Exception) {
                onResult(false, "연결 오류: ${e.message}")
            }
        }
    }
}
