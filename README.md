# MMS Forwarder - Android → n8n

안드로이드 폰에서 MMS 메시지를 수신하면 자동으로 n8n 웹훅으로 전달하는 앱입니다.

## 프로젝트 구조

```
MMS/
├── app/src/main/
│   ├── AndroidManifest.xml          # 권한 및 컴포넌트 선언
│   └── java/com/mms/forwarder/
│       ├── MainActivity.kt          # 설정 UI
│       ├── MmsReceiver.kt           # MMS WAP Push BroadcastReceiver
│       ├── MmsHelper.kt             # ContentProvider MMS 파싱
│       ├── MmsMonitorService.kt     # 포그라운드 서비스
│       ├── N8nSender.kt             # n8n HTTP 전송
│       └── BootReceiver.kt          # 부팅 시 자동 시작
├── n8n-workflow-example.json        # n8n 워크플로우 예시
└── README.md
```

## 빌드 방법

### 사전 요구사항
- Android Studio (Hedgehog 이상)
- JDK 17
- Android SDK 34

### Android Studio에서 빌드
1. Android Studio 열기 → `Open` → `D:\MMS` 선택
2. Gradle sync 완료 대기
3. `Build` → `Build Bundle(s)/APK(s)` → `Build APK(s)`
4. APK 위치: `app/build/outputs/apk/debug/app-debug.apk`

### 커맨드라인 빌드
```bash
cd D:\MMS
gradlew assembleDebug
```

## 앱 설치 및 설정

### 1. 권한 허용
앱 실행 후 요청하는 권한을 모두 허용합니다:
- SMS 수신 (RECEIVE_SMS)
- MMS 수신 (RECEIVE_MMS)
- SMS 읽기 (READ_SMS)
- 알림 표시 (POST_NOTIFICATIONS, Android 13+)

### 2. n8n 웹훅 URL 설정
1. n8n에서 **Webhook** 노드 추가
2. HTTP Method: `POST`
3. Path: 원하는 경로 (예: `mms-receiver`)
4. 앱의 `웹훅 URL` 필드에 URL 입력:
   ```
   https://your-n8n.com/webhook/mms-receiver
   ```
5. `저장` 버튼 클릭

### 3. 연결 테스트
`연결 테스트` 버튼을 눌러 n8n과의 통신을 확인합니다.

### 4. 서비스 시작
`서비스 시작` 버튼을 눌러 MMS 모니터링을 시작합니다.

## n8n으로 전달되는 JSON 형식

```json
{
  "event": "mms_received",
  "timestamp": "2024-01-15 14:30:00",
  "timestamp_unix": 1705290600000,
  "mms_id": 42,
  "thread_id": 5,
  "sender": "01012345678",
  "recipients": ["01098765432"],
  "subject": null,
  "text": "MMS 메시지 텍스트 내용",
  "text_parts": ["파트1", "파트2"],
  "has_images": true,
  "image_count": 1,
  "images": [
    {
      "mime_type": "image/jpeg",
      "name": "photo.jpg",
      "data": "data:image/jpeg;base64,/9j/4AAQ..."
    }
  ]
}
```

## n8n 워크플로우 설정

`n8n-workflow-example.json` 파일을 n8n에 임포트합니다:
1. n8n 대시보드 → `워크플로우` → `임포트`
2. JSON 파일 선택
3. Webhook URL 확인 후 활성화

## 주의사항

### Android 권한 문제
- **Android 10+**: 백그라운드 앱의 SMS 읽기에 제한이 있을 수 있습니다.
- **일부 제조사 (삼성, 샤오미 등)**: 배터리 최적화 제외 설정이 필요합니다.
  - 설정 → 앱 → MMS Forwarder → 배터리 → 제한 없음

### 기본 SMS 앱 설정 (권장)
더 안정적인 MMS 수신을 위해 이 앱을 기본 SMS 앱으로 설정하면
`WAP_PUSH_DELIVER` 인텐트를 받아 더 빠르게 처리합니다.

### 배터리 최적화
장기 실행을 위해 배터리 최적화에서 제외시키세요:
- 설정 → 배터리 → 배터리 사용량 최적화 → MMS Forwarder → 최적화 안함

### 이미지 전송
이미지 포함 MMS는 Base64 인코딩 후 전송됩니다.
n8n에서 `Buffer.from(data.split(',')[1], 'base64')`로 디코딩 가능합니다.

## 트러블슈팅

| 문제 | 해결 방법 |
|------|-----------|
| MMS 수신 안 됨 | 권한 확인, 배터리 최적화 제외 |
| n8n 전송 실패 | 웹훅 URL 확인, 네트워크 연결 확인 |
| 앱 종료 후 미작동 | 서비스 재시작, 배터리 최적화 제외 |
| 이미지 없음 | `이미지 포함 전송` 스위치 확인 |
