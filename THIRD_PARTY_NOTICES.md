# Сторонние библиотеки и лицензии

Код «Домофон 74» распространяется по [MIT License](LICENSE), Copyright © 2026 Anateon. Лицензии сторонних компонентов сохраняются и не заменяются лицензией приложения.

Полные тексты ниже включены и в репозиторий, и в APK — в каталог `assets/licenses/`.

| Компоненты | Авторы и исходный проект | Лицензия |
| --- | --- | --- |
| AndroidX: Compose, Activity, Core, Lifecycle, WorkManager, Room, SQLite, NavigationEvent и остальные AndroidX-зависимости | The Android Open Source Project и участники [AndroidX](https://github.com/androidx/androidx) | [Apache 2.0](app/src/main/assets/licenses/Apache-2.0.txt) |
| Media3 / ExoPlayer, HLS, UI | The Android Open Source Project и участники [AndroidX Media](https://github.com/androidx/media) | [Apache 2.0](app/src/main/assets/licenses/Apache-2.0.txt) |
| Kotlin standard library 2.4.10 | JetBrains s.r.o. и Kotlin Programming Language contributors; [Kotlin](https://github.com/JetBrains/kotlin/tree/v2.4.10) | [Apache 2.0](app/src/main/assets/licenses/Kotlin-LICENSE.txt), дополнительные уведомления ниже |
| kotlinx.coroutines 1.11.0 | JetBrains s.r.o. и Kotlin Programming Language contributors; [Coroutines](https://github.com/Kotlin/kotlinx.coroutines/tree/1.11.0) | [Apache 2.0](app/src/main/assets/licenses/Coroutines-LICENSE.txt) |
| kotlinx.serialization 1.7.3 | JetBrains s.r.o. и Kotlin Programming Language contributors; [Serialization](https://github.com/Kotlin/kotlinx.serialization) | [Apache 2.0](app/src/main/assets/licenses/Apache-2.0.txt) |
| JetBrains Annotations 23.0.0 | JetBrains s.r.o.; [Java Annotations](https://github.com/JetBrains/java-annotations) | [Apache 2.0](app/src/main/assets/licenses/Apache-2.0.txt) |
| Guava 33.3.1-android, failureaccess 1.0.2, listenablefuture | The Guava Authors; [Guava](https://github.com/google/guava/tree/v33.3.1) | [Apache 2.0](app/src/main/assets/licenses/Apache-2.0.txt) |
| JSpecify 1.0.0 | The JSpecify Authors; [JSpecify](https://github.com/jspecify/jspecify/tree/v1.0.0) | [Apache 2.0](app/src/main/assets/licenses/JSpecify-LICENSE.txt) |

## Дополнительные уведомления Kotlin

Стандартная библиотека содержит части, происходящие из других проектов. Сохранены соответствующие полные тексты из [лицензионного каталога Kotlin 2.4.10](https://github.com/JetBrains/kotlin/blob/v2.4.10/license/README.md):

- GWT, Google Inc. — [Apache 2.0](app/src/main/assets/licenses/Kotlin-GWT.txt).
- Guava, The Guava Authors — [Apache 2.0](app/src/main/assets/licenses/Kotlin-Guava.txt).
- ThreeTen Backport, Stephen Colebourne и Michael Nascimento Santos — [BSD 3-Clause с исходным уведомлением об авторских правах](app/src/main/assets/licenses/Kotlin-ThreeTenBP.txt).
- Boost math functions, Eric Ford и Hubert Holin — [Boost Software License 1.0](app/src/main/assets/licenses/Kotlin-Boost.txt).

## Уведомления из поставляемых архивов

[Embedded-NOTICES.txt](app/src/main/assets/licenses/Embedded-NOTICES.txt) сохраняет исходные файлы `LICENSE` / `NOTICE`, найденные в разрешённых runtime JAR/AAR, с указанием Maven-координат и имени записи. В частности, он включает полный `NOTICE` из `androidx.concurrent:concurrent-futures-ktx`.

[Полный список разрешённых зависимостей релиза](app/src/main/assets/licenses/RUNTIME_DEPENDENCIES.txt) включает транзитивные модули и платформенные метаданные. [Источники дополнительных полных текстов](app/src/main/assets/licenses/upstream-sources.json) указаны отдельно. RTSP, отладочные инструменты Compose и тестовые библиотеки в этот список не входят.

## Инструменты сборки

В репозитории также поставляется Gradle Wrapper, Copyright Gradle, Inc., по [Apache 2.0](app/src/main/assets/licenses/Apache-2.0.txt). Wrapper и тестовые зависимости не включаются в APK.

При обновлении зависимостей обновите список и проверьте изменения upstream-лицензий:

```sh
./gradlew :app:dependencies --configuration releaseRuntimeClasspath > /tmp/door74-dependencies.txt
python3 tools/collect_licenses.py /tmp/door74-dependencies.txt
```

Скрипт использует только локальный Gradle-кэш. Он сохраняет исходные встроенные уведомления и список разрешённых версий; проверка изменений отдельных upstream-лицензий остаётся частью подготовки релиза.
