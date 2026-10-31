# Biztalk4BeyoundIT
BeyondIT를 위한 비지니스 카카오톡 관리

선배님(삼성 안드로이드폰)이 개인 카톡으로 받는 비즈니스 메시지를 자동으로 수집해 구글 시트에 정리한다.
요구사항과 구현 계획은 Claude Docs 문서 "카톡 비즈니스 메시지 자동 정리 — 요구사항 및 구현 계획"에 있다.

## 구성 (예정)

| 경로 | 내용 | 상태 |
| --- | --- | --- |
| `diag-app/` | 0단계 진단 앱. 카톡 알림의 데이터 구조를 기록한다 | 완료 |
| `apps-script/` | 시트 기록, Gemini 분류, 생존 감시 | 1단계 |
| `android-app/` | 정식 수집 앱 | 2단계 |

## 진단 앱 빌드

JDK 17 이상과 Android SDK(platform 35)가 필요하다.

```bash
cd diag-app
./gradlew assembleRelease
```

결과물은 `diag-app/app/build/outputs/apk/release/app-release.apk`다.
진단 앱은 디버그 키로 서명한다. 정식 앱은 고정된 서명 키를 따로 관리한다.

## 주의

- 이 저장소는 공개 저장소다. API 키, 비밀 토큰, 서명 키, 진단 기록 파일(실제 카톡 메시지 포함)은 절대 커밋하지 않는다.
