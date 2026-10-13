# Running2

Android 달리기 기록 앱입니다. 경로 지도는 Google Maps SDK for Android를 사용합니다. 지도 표시와 위치 수집·원본 저장·거리 계산은 서로 독립적이므로, 지도 타일을 불러오지 못해도 기록은 계속됩니다.

코드와 Git 저장소는 이 `code` 폴더에 있습니다. 작업 계획과 검증 보고서는 Git 저장소 밖의 `..\memo`에 보관합니다.

## Google 지도 설정

1. [Google Cloud Console](https://console.cloud.google.com/)에서 프로젝트를 만들거나 선택하고 결제 계정을 연결합니다.
2. 해당 프로젝트에서 **Maps SDK for Android**를 활성화합니다.
3. API 키를 만들고 API 제한을 **Maps SDK for Android**로 설정합니다.
4. 애플리케이션 제한을 **Android 앱**으로 설정합니다. 이 앱의 패키지 이름은 `com.running2.app`입니다. 개발 APK에는 로컬 디버그 서명 인증서의 SHA-1 지문을 등록하고, 출시 APK에는 출시 서명 인증서의 SHA-1 지문을 별도로 등록합니다. `gradlew.bat signingReport`에서 현재 디버그 SHA-1을 확인할 수 있습니다.
5. Git에 포함되지 않는 루트 `local.properties`에 `MAPS_API_KEY=발급받은_키`를 추가하고 다시 빌드·설치합니다. `local.properties.example`은 형식 예시입니다.

기본 지도에는 **지도 ID가 필요하지 않습니다**. API 키가 없으면 지도 영역에 설정 안내가 나오며 달리기 기록은 계속 사용할 수 있습니다. API 키는 빌드된 앱에 포함되므로 Android 패키지·서명 지문과 API 제한을 반드시 적용하세요.

## 빌드 및 테스트

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

생성된 APK: `app/build/outputs/apk/debug/app-debug.apk`
