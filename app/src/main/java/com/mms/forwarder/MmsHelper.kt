package com.mms.forwarder

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * MMS 메시지 데이터 모델
 */
data class MmsMessage(
    val id: Long,
    val threadId: Long,
    val sender: String,
    val recipients: List<String>,
    val subject: String?,
    val textParts: List<String>,
    val imageParts: List<MmsImagePart>,
    val timestamp: Long,
    val timestampFormatted: String,
    val rawPduSize: Int = 0
)

data class MmsImagePart(
    val mimeType: String,
    val name: String?,
    val base64Data: String
)

/**
 * Android ContentProvider에서 MMS 메시지를 읽어오는 헬퍼 클래스
 */
class MmsHelper(private val context: Context) {

    companion object {
        private const val TAG = "MmsHelper"

        // ContentProvider URIs
        private val MMS_INBOX_URI = Uri.parse("content://mms/inbox")
        private val MMS_URI = Uri.parse("content://mms")
        private val MMS_ADDR_URI = Uri.parse("content://mms/addr")

        // MMS 발신자 타입
        private const val ADDR_TYPE_FROM = 137    // PduHeaders.FROM
        private const val ADDR_TYPE_TO = 151       // PduHeaders.TO

        // 이미지 MIME 타입
        private val SUPPORTED_IMAGE_TYPES = setOf(
            "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp"
        )

        // 최근 MMS를 가져올 때 중복 방지용 마지막 처리 ID
        private var lastProcessedId: Long = -1L
    }

    /**
     * 가장 최근 수신된 MMS 메시지 가져오기
     */
    fun getLatestMms(): MmsMessage? {
        return try {
            val cursor = context.contentResolver.query(
                MMS_INBOX_URI,
                arrayOf("_id", "thread_id", "date", "sub", "m_size"),
                null,
                null,
                "date DESC LIMIT 1"
            ) ?: return null

            cursor.use {
                if (!it.moveToFirst()) return null

                val id = it.getLong(it.getColumnIndexOrThrow("_id"))

                // 중복 방지: 이미 처리한 ID면 스킵
                if (id == lastProcessedId) {
                    Log.d(TAG, "이미 처리된 MMS ID: $id, 스킵")
                    return null
                }

                val threadId = it.getLong(it.getColumnIndexOrThrow("thread_id"))
                val dateSeconds = it.getLong(it.getColumnIndexOrThrow("date"))
                val subject = it.getString(it.getColumnIndex("sub"))

                val timestamp = dateSeconds * 1000L
                val dateFormatted = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", Locale.getDefault()
                ).apply {
                    timeZone = TimeZone.getDefault()
                }.format(Date(timestamp))

                val sender = getMmsSender(id)
                val recipients = getMmsRecipients(id)
                val (textParts, imageParts) = getMmsParts(id)

                lastProcessedId = id

                Log.i(TAG, "MMS 파싱 완료 - ID: $id, 발신자: $sender, 텍스트: ${textParts.size}개, 이미지: ${imageParts.size}개")

                MmsMessage(
                    id = id,
                    threadId = threadId,
                    sender = sender,
                    recipients = recipients,
                    subject = subject,
                    textParts = textParts,
                    imageParts = imageParts,
                    timestamp = timestamp,
                    timestampFormatted = dateFormatted
                )
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "MMS 읽기 권한 없음: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "MMS 읽기 오류: ${e.message}")
            null
        }
    }

    /**
     * 최근 N개의 MMS 메시지 목록 가져오기
     */
    fun getRecentMmsMessages(limit: Int = 10): List<MmsMessage> {
        val messages = mutableListOf<MmsMessage>()
        return try {
            val cursor = context.contentResolver.query(
                MMS_INBOX_URI,
                arrayOf("_id", "thread_id", "date", "sub"),
                null,
                null,
                "date DESC LIMIT $limit"
            ) ?: return messages

            cursor.use {
                while (it.moveToNext()) {
                    val id = it.getLong(it.getColumnIndexOrThrow("_id"))
                    val threadId = it.getLong(it.getColumnIndexOrThrow("thread_id"))
                    val dateSeconds = it.getLong(it.getColumnIndexOrThrow("date"))
                    val subject = it.getString(it.getColumnIndex("sub"))

                    val timestamp = dateSeconds * 1000L
                    val dateFormatted = SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", Locale.getDefault()
                    ).apply {
                        timeZone = TimeZone.getDefault()
                    }.format(Date(timestamp))

                    val sender = getMmsSender(id)
                    val recipients = getMmsRecipients(id)
                    val (textParts, imageParts) = getMmsParts(id)

                    messages.add(
                        MmsMessage(
                            id = id,
                            threadId = threadId,
                            sender = sender,
                            recipients = recipients,
                            subject = subject,
                            textParts = textParts,
                            imageParts = imageParts,
                            timestamp = timestamp,
                            timestampFormatted = dateFormatted
                        )
                    )
                }
            }
            messages
        } catch (e: Exception) {
            Log.e(TAG, "MMS 목록 읽기 오류: ${e.message}")
            messages
        }
    }

    /**
     * MMS 발신자 번호 가져오기
     */
    private fun getMmsSender(mmsId: Long): String {
        return try {
            val uri = Uri.parse("content://mms/$mmsId/addr")
            val cursor = context.contentResolver.query(
                uri,
                arrayOf("address", "type"),
                "type = $ADDR_TYPE_FROM",
                null,
                null
            ) ?: return "알 수 없음"

            cursor.use {
                if (it.moveToFirst()) {
                    it.getString(it.getColumnIndexOrThrow("address")) ?: "알 수 없음"
                } else {
                    "알 수 없음"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "발신자 조회 오류: ${e.message}")
            "알 수 없음"
        }
    }

    /**
     * MMS 수신자 번호 목록 가져오기
     */
    private fun getMmsRecipients(mmsId: Long): List<String> {
        val recipients = mutableListOf<String>()
        return try {
            val uri = Uri.parse("content://mms/$mmsId/addr")
            val cursor = context.contentResolver.query(
                uri,
                arrayOf("address", "type"),
                "type = $ADDR_TYPE_TO",
                null,
                null
            ) ?: return recipients

            cursor.use {
                while (it.moveToNext()) {
                    val address = it.getString(it.getColumnIndexOrThrow("address"))
                    if (!address.isNullOrBlank() && address != "insert-address-token") {
                        recipients.add(address)
                    }
                }
            }
            recipients
        } catch (e: Exception) {
            Log.e(TAG, "수신자 조회 오류: ${e.message}")
            recipients
        }
    }

    /**
     * MMS 파트(텍스트, 이미지 등) 가져오기
     */
    private fun getMmsParts(mmsId: Long): Pair<List<String>, List<MmsImagePart>> {
        val textParts = mutableListOf<String>()
        val imageParts = mutableListOf<MmsImagePart>()

        return try {
            val uri = Uri.parse("content://mms/part")
            val cursor = context.contentResolver.query(
                uri,
                arrayOf("_id", "mid", "ct", "name", "_data", "text"),
                "mid = $mmsId",
                null,
                null
            ) ?: return Pair(textParts, imageParts)

            cursor.use {
                while (it.moveToNext()) {
                    val partId = it.getLong(it.getColumnIndexOrThrow("_id"))
                    val mimeType = it.getString(it.getColumnIndexOrThrow("ct")) ?: continue
                    val name = it.getString(it.getColumnIndex("name"))

                    when {
                        mimeType == "text/plain" -> {
                            // 텍스트 파트
                            val text = it.getString(it.getColumnIndex("text"))
                                ?: readPartData(partId)?.let { bytes ->
                                    String(bytes, Charsets.UTF_8)
                                } ?: ""
                            if (text.isNotBlank()) {
                                textParts.add(text)
                            }
                        }
                        mimeType in SUPPORTED_IMAGE_TYPES -> {
                            // 이미지 파트
                            val imageData = readPartData(partId)
                            if (imageData != null && imageData.isNotEmpty()) {
                                val base64 = Base64.encodeToString(imageData, Base64.NO_WRAP)
                                imageParts.add(
                                    MmsImagePart(
                                        mimeType = mimeType,
                                        name = name,
                                        base64Data = base64
                                    )
                                )
                                Log.d(TAG, "이미지 파트 처리: $mimeType, 크기: ${imageData.size} bytes")
                            }
                        }
                        else -> {
                            Log.d(TAG, "지원하지 않는 MIME 타입 스킵: $mimeType")
                        }
                    }
                }
            }
            Pair(textParts, imageParts)
        } catch (e: Exception) {
            Log.e(TAG, "MMS 파트 읽기 오류: ${e.message}")
            Pair(textParts, imageParts)
        }
    }

    /**
     * 파트 데이터를 바이트 배열로 읽기
     */
    private fun readPartData(partId: Long): ByteArray? {
        return try {
            val uri = Uri.parse("content://mms/part/$partId")
            val inputStream: InputStream = context.contentResolver.openInputStream(uri) ?: return null
            val outputStream = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            inputStream.close()
            outputStream.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "파트 데이터 읽기 오류 (ID: $partId): ${e.message}")
            null
        }
    }

    /**
     * 마지막 처리 ID 리셋 (테스트용)
     */
    fun resetLastProcessedId() {
        lastProcessedId = -1L
    }

    /**
     * 전체 MMS 메시지 검색 (수신함 + 발신함)
     * @param limit 가져올 최대 개수 (0 = 전체)
     * @param onlyInbox true면 수신함만, false면 전체 (수신+발신)
     * @param sinceTimestampMs 이 시간 이후의 MMS만 (0 = 제한 없음)
     */
    fun getAllMmsMessages(
        limit: Int = 0,
        onlyInbox: Boolean = false,
        sinceTimestampMs: Long = 0L
    ): List<MmsMessage> {
        val messages = mutableListOf<MmsMessage>()
        return try {
            val selectionParts = mutableListOf<String>()
            if (onlyInbox) selectionParts.add("msg_box = 1")
            if (sinceTimestampMs > 0) selectionParts.add("date >= ${sinceTimestampMs / 1000}")
            val selection = if (selectionParts.isEmpty()) null else selectionParts.joinToString(" AND ")

            val sortOrder = if (limit > 0) "date DESC LIMIT $limit" else "date DESC"

            val cursor = context.contentResolver.query(
                MMS_URI,
                arrayOf("_id", "thread_id", "date", "sub", "msg_box"),
                selection,
                null,
                sortOrder
            ) ?: return messages

            cursor.use {
                while (it.moveToNext()) {
                    val id = it.getLong(it.getColumnIndexOrThrow("_id"))
                    val threadId = it.getLong(it.getColumnIndexOrThrow("thread_id"))
                    val dateSeconds = it.getLong(it.getColumnIndexOrThrow("date"))
                    val subject = it.getString(it.getColumnIndex("sub"))

                    val timestamp = dateSeconds * 1000L
                    val dateFormatted = SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", Locale.getDefault()
                    ).apply {
                        timeZone = TimeZone.getDefault()
                    }.format(Date(timestamp))

                    val sender = getMmsSender(id)
                    val recipients = getMmsRecipients(id)
                    val (textParts, imageParts) = getMmsParts(id)

                    messages.add(
                        MmsMessage(
                            id = id,
                            threadId = threadId,
                            sender = sender,
                            recipients = recipients,
                            subject = subject,
                            textParts = textParts,
                            imageParts = imageParts,
                            timestamp = timestamp,
                            timestampFormatted = dateFormatted
                        )
                    )
                }
            }
            Log.i(TAG, "전체 MMS 검색 완료: ${messages.size}개")
            messages
        } catch (e: Exception) {
            Log.e(TAG, "전체 MMS 읽기 오류: ${e.message}")
            messages
        }
    }

    /**
     * MMS 메시지 삭제
     * ⚠ Android 4.4+ 에서는 기본 SMS 앱으로 설정되어 있어야 삭제 가능
     * @return true=삭제 성공, false=실패(권한 없음 또는 기본 SMS 앱 아님)
     */
    fun deleteMms(mmsId: Long): Boolean {
        return try {
            val uri = Uri.parse("content://mms/$mmsId")
            val deleted = context.contentResolver.delete(uri, null, null)
            if (deleted > 0) {
                Log.i(TAG, "MMS 삭제 성공: ID=$mmsId")
                true
            } else {
                Log.w(TAG, "MMS 삭제 실패 (0건 삭제): ID=$mmsId")
                false
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "MMS 삭제 권한 없음 (기본 SMS 앱 설정 필요): ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "MMS 삭제 오류: ID=$mmsId, ${e.message}")
            false
        }
    }

    /**
     * 저장된 MMS 총 개수 조회
     * @param onlyInbox true면 수신함만 카운트
     */
    fun getMmsCount(onlyInbox: Boolean = false): Int {
        return try {
            val selection = if (onlyInbox) "msg_box = 1" else null
            val cursor = context.contentResolver.query(
                MMS_URI,
                arrayOf("_id"),
                selection,
                null,
                null
            ) ?: return 0
            cursor.use { it.count }
        } catch (e: Exception) {
            Log.e(TAG, "MMS 개수 조회 오류: ${e.message}")
            0
        }
    }
}
