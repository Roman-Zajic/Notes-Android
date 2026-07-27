name: Build APK

on:
  push:
    branches: [ main ]
  workflow_dispatch: {}

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3

      - name: Write runtime config asset
        # Populated from this repo's own encrypted Actions secrets
        # (Settings -> Secrets and variables -> Actions) -- never
        # committed, never visible in logs, never leaves GitHub's build
        # runner. This is a plain asset file read at RUNTIME by the app
        # (see MainActivity.kt's loadConfig()), not a Gradle-time
        # BuildConfig field -- no codegen involved, just a file copied
        # verbatim into the APK by the standard build pipeline.
        run: |
          mkdir -p app/src/main/assets
          cat > app/src/main/assets/config.properties << EOF
          GH_TOKEN=${{ secrets.NOTES_GH_TOKEN }}
          GH_REPO=${{ secrets.NOTES_GH_REPO }}
          GH_BRANCH=${{ secrets.NOTES_GH_BRANCH }}
          GH_SUBDIR=${{ secrets.NOTES_GH_SUBDIR }}
          EOF

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          # AGP 8.5.2 supports Gradle 8.7-8.9 -- pinned to avoid whatever
          # "latest" happens to resolve to.
          gradle-version: '8.9'

      - name: Build debug APK
        run: gradle assembleDebug --no-daemon

      - name: Upload APK artifact
        uses: actions/upload-artifact@v4
        with:
          name: notes-pusher-debug-apk
          path: app/build/outputs/apk/debug/app-debug.apk
