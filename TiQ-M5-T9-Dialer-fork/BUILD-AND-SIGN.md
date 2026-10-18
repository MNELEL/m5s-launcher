# M5S T9 dialer fork — build & install

What's changed from the developer's public source (master, identical to the running 1.2.18):
1. `ContactsActivity.kt` + `res/layout/activity_contacts.xml` — digit keys now filter the contacts
   list with T9 (letters of the name or digits of the number), matching how the dialer screen
   already searches contacts while dialing. `*`/DEL = backspace, Back clears the search.
2. App name set to "טלפון M5S T9" (Hebrew) / "M5S T9 Phone" (other languages), only so it's easy
   to tell apart from the vendor's own copy while both may be installed.
3. `versionCode 282`, `versionName "1.2.18-m5s-t9"`.

This "master" source has no anti-tamper/signature check (unlike the 1.2.14 zip from the Play/vendor
build, which kills itself if re-signed with a different key) — so a plain rebuild works, nothing to
patch around.

## Build
Open the folder in Android Studio (needs internet for Gradle/Google Maven the first time) and
Build > Generate Signed Bundle/APK, or from a shell with the Android SDK + internet:
    ./gradlew assembleRelease
The unsigned/debug-signed APK lands in app/build/outputs/apk/release/.

## Sign
A keystore is included: `puretech-m5s.jks` (alias `m5s`, password `m5sdialer` for both the store and
the key). Sign and zipalign the release APK:
    zipalign -p 4 app-release-unsigned.apk app-release-aligned.apk
    apksigner sign --ks puretech-m5s.jks --ks-pass pass:m5sdialer --out M5S-T9-Dialer.apk app-release-aligned.apk
(Use your own keystore instead if you'd rather — nothing in this build pins a specific key.)

## Install
The package name is unchanged (`com.puretech.dialer`) so the M5S launcher keeps working with it
as-is. Because the signature differs from whatever is already installed, uninstall the existing
com.puretech.dialer first (its settings/call log are local to it and will be lost); then install
this APK.

## Note
The app can self-update from GitHub (`Updater.kt`). Turn that off in its settings, or it may offer
the original unpatched build over this one.

## Building without Android Studio: GitHub Actions (free, no local setup)
A ready-made workflow is included at `.github/workflows/build.yml`. To use it:
1. Create a **new GitHub repository** (github.com → "+" → "New repository"; Private is fine).
2. Upload this whole folder into it — either `git push`, or on the repo page use
   "Add file → Upload files" and drag the folder's contents in, then commit.
3. Open the repo's **Actions** tab. The workflow runs automatically on that push; if it
   doesn't, select "Build signed APK" on the left and click **Run workflow**.
4. Wait for it to finish (a few minutes), open the finished run, and download the
   **M5S-T9-Dialer** artifact at the bottom of the page — that's the signed APK, ready
   to copy to the phone and install.
No Android Studio, Gradle, or SDK installation needed on your side; GitHub's own servers
do the build, using the `puretech-m5s.jks` keystore already included in this folder.
