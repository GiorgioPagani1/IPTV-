# Whisper Mobile — распознавание речи на Android

**Отдельный Android-проект в ветке `whisper-mobile`. Телевизионное приложение остаётся в ветке `main`.**

Приложение на Kotlin / Jetpack Compose с локальным движком whisper.cpp для Android ARM64.

## Скачать APK

1. Откройте [GitHub Actions → Whisper Mobile Android APK](https://github.com/GiorgioPagani1/IPTV-/actions/workflows/whisper-mobile-apk.yml?query=branch%3Awhisper-mobile).
2. Откройте **последний успешный запуск** с зелёной галочкой. Если запуск не появился, откройте вкладку Actions и выберите workflow по названию.
3. Внизу страницы, в блоке **Artifacts**, скачайте **WhisperMobile-Android-APK**.
4. Распакуйте архив с артефактом и установите `app-debug.apk` на телефон Android 8+ с процессором ARM64.

Если сборка красная, APK пока не создан — откройте job «Build APK for Android ARM64» для текста ошибки.

## Возможности MVP

- Импорт файлов MP3 / WAV / M4A / MP4 через системный проводник Android.
- Импорт по прямой HTTPS-ссылке на аудио или видеофайл; **страницы YouTube, VK Видео и RuTube пока не поддерживаются**.
- Локальная транскрипция на телефоне, русский / английский / авто.
- Модели Whisper Tiny или Base: первая загрузка требует интернет, дальше распознавание офлайн.
- Полный текст, сегменты с таймкодами, синхронное воспроизведение, история.
- Копирование и экспорт TXT/SRT.

## Код и сборка

Полный Android-проект хранится в сжатом виде в `source-archive/part01.b64`, `part02.b64`, `part03.b64`. Workflow восстанавливает `WhisperMobile/` из частей, устанавливает Java/Android SDK/NDK/CMake/Gradle, собирает debug APK, затем выкладывает его в **Artifacts**.

Проект можно восстановить локально (Linux/macOS):

```bash
cat source-archive/part*.b64 | tr -d '\n\r' | base64 -d > whisper-mobile.tar.xz
tar -xJf whisper-mobile.tar.xz
```

После распаковки открыть `WhisperMobile` в Android Studio. Детальная документация находится в `WhisperMobile/README.md`.

**Статус:** исходники — MVP; до подтверждения успешного workflow и проверки на телефоне сборка/работоспособность не гарантируются.
