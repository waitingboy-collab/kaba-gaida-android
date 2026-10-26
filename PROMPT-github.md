# Задача: качи „Каба гайда“ за Android в GitHub с APK за сваляне

Работиш в VS Code на Windows, в папката `C:\AndroidProjects\KabaGaidaAndroid`. Използвай терминала (PowerShell). Проектът е на Kotlin + Jetpack Compose и вече се компилира в Android Studio.

**Цел:** публично хранилище `waitingboy-collab/kaba-gaida-android` с кода, плюс GitHub Release `v0.1.0` с подписан APK, който хората могат да свалят и инсталират на телефона.

## Задължителни правила

1. **Ключът за подписване не трябва да попада в GitHub.** Файлът `kabagaida` в корена на проекта е keystore. Същото важи за `*.jks`, `*.keystore`, `keystore.properties` и `local.properties`. Ако някой от тях се окаже в `git ls-files` или в `git status` като staged, спри веднага и ми кажи.
2. **Никога не чети, не показвай и не записвай пароли.** Не отваряй `keystore.properties`, след като аз го попълня, и не пускай `cat`/`type`/`Get-Content` върху него. Не слагай пароли в командния ред.
3. Не трий файлове и не прави `git push --force`. Не пипай кода в `app/src`.
4. Ако нещо не стане, спри и ми покажи грешката. Не заобикаляй правилата.
5. Пиши ми на български, кратко: какво направи и какво следва.

## Стъпки

### 1. Провери инструментите
- `git --version`. Ако го няма: `winget install --id Git.Git -e`, после рестартирай терминала.
- `gh --version`. Ако го няма: `winget install --id GitHub.cli -e`, после рестартирай терминала.
- `gh auth status`. Ако не съм влязъл, кажи ми да пусна `gh auth login` (GitHub.com → HTTPS → браузър) и изчакай да потвърдя.
- `git config --global user.name` и `git config --global user.email`. Ако са празни, попитай ме какво да сложиш.

### 2. Провери .gitignore
`.gitignore` трябва да съдържа поне тези редове. Добави липсващите, без да махаш нищо:
```
/local.properties
/app/build/
/app/release/
.gradle/
.kotlin/
/.idea/
/kabagaida
*.jks
*.keystore
keystore.properties
*.apk
*.aab
/dist/
```

### 3. Подписване на release чрез Gradle
В `app/build.gradle.kts` добави подписване, което чете данните от `keystore.properties` (той не влиза в git). Ако файлът липсва, проектът трябва да продължи да се компилира както досега.

Най-горе във файла:
```kotlin
import java.util.Properties

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
```

В блока `android { ... }`, преди `buildTypes`:
```kotlin
signingConfigs {
    create("release") {
        if (!keystoreProps.isEmpty()) {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }
}
```

В `buildTypes { release { ... } }` добави:
```kotlin
if (!keystoreProps.isEmpty()) signingConfig = signingConfigs.getByName("release")
```

После създай в корена **шаблон** `keystore.properties` със следното съдържание:
```
storeFile=kabagaida
storePassword=ПОПЪЛНИ
keyAlias=ПОПЪЛНИ
keyPassword=ПОПЪЛНИ
```
Кажи ми да го отворя и да попълня паролите и alias-а сам. Изчакай да потвърдя. Не чети файла след това.

### 4. Направи подписания APK
- Gradle трябва да ползва Java от Android Studio. Ако `JAVA_HOME` не е зададен:
  `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`
- Пусни `.\gradlew.bat assembleRelease`. Готовият файл е `app\build\outputs\apk\release\app-release.apk`.
- Провери подписа с apksigner от SDK-то. Използвай най-новата папка в `%LOCALAPPDATA%\Android\Sdk\build-tools\`:
  `& "$env:LOCALAPPDATA\Android\Sdk\build-tools\<версия>\apksigner.bat" verify --print-certs app\build\outputs\apk\release\app-release.apk`
  Подписът трябва да е валиден и сертификатът да **не е** `CN=Android Debug`.
- Вземи `versionName` от `app/build.gradle.kts` (в момента е `0.1.0`). Копирай APK-то като `dist\KabaGaida-<versionName>.apk`.
- Ако има свързан телефон (`adb devices`), предложи ми тест с `adb install -r dist\KabaGaida-<versionName>.apk`. Предупреди ме, че debug версия от Android Studio трябва първо да се деинсталира, защото подписът е различен.

### 5. Git хранилище и първи commit
- Ако още няма git хранилище: `git init -b main`.
- `git add .`, после `git status`.
- **Проверка за сигурност:** `git ls-files --cached` не трябва да съдържа `kabagaida`, `keystore.properties`, `local.properties`, `.apk`, `.aab`, `build/`, `.idea/` или `dist/`. Ако съдържа, оправи `.gitignore`, махни ги с `git rm --cached <файл>` и провери пак.
- Commit: `git commit -m "Каба гайда за Android 0.1.0"`.

### 6. Създай хранилището в GitHub и качи кода
```
gh repo create waitingboy-collab/kaba-gaida-android --public --source . --remote origin --push --description "Помощник за начинаещи гайдари: тунер спрямо ручилото и тренажор за равно надуване (Android, Kotlin + Compose)"
```
Ако хранилището вече съществува, не го презаписвай. Попитай ме дали да го вържа като `origin` и да пусна `git push -u origin main`.

### 7. Release с APK
```
gh release create v0.1.0 dist\KabaGaida-0.1.0.apk --repo waitingboy-collab/kaba-gaida-android --title "Каба гайда 0.1.0 за Android" --notes "Първа версия: калибриране на ручилото, тунер спрямо ручилото (вкл. неутрални интервали) и тренажор за равно надуване. Android 8.0+. Изтегли APK от Assets и разреши инсталиране от неизвестни източници."
```

### 8. Проверка
- `gh release view v0.1.0 --repo waitingboy-collab/kaba-gaida-android`. APK-то трябва да е в списъка с файлове.
- Отвори `https://github.com/waitingboy-collab/kaba-gaida-android/releases/latest` и провери, че линкът от README води там.
- Последна проверка в GitHub (`gh api repos/waitingboy-collab/kaba-gaida-android/contents`): `kabagaida` и `keystore.properties` не трябва да са там.

### 9. Отчет
Кажи ми:
- линка към хранилището;
- линка към Release-а;
- размера на APK-то;
- дали проверката на подписа е минала.

Напомни ми да пазя копие на `kabagaida` и паролата извън проекта (флашка или облак). Без тях не мога да издавам обновления.

## За следващи версии
За нова версия: вдигни `versionCode` с 1 и `versionName` (например `0.1.1`) в `app/build.gradle.kts`. После повтори стъпка 4, commit + `git push` и `gh release create v<версия> ...`.
