# EinAb phone app (Play Store / App Store)

The store app is a thin native shell (Capacitor) around the live website. It loads `https://einab.ch` on every
start (until both stores have approved the app it loads the test server `https://test-foodsharing.it4user.ch`
instead; see `DEFAULT_APP_URL` in `client/capacitor.config.ts`), so **every normal web release reaches app users immediately, without a store update**. A new store build is
only needed when something native changes: Capacitor or plugin versions, icons, permissions, `capacitor.config.ts`,
or files under `client/android` / `client/ios`.

What the app adds on top of the website:

- **Push notifications.** Every message the platform sends to a user (Foodsharing message or email) is also sent as a
  push notification to the phones where that user turned it on. The app asks once after the first sign-in; the
  switch is on the profile page ("Push-Mitteilungen auf diesem Telefon"). Sign-in links are never pushed (they would
  show on the lock screen). Signing out removes the phone. Tapping a notification opens the linked page.
- **App links.** einab.ch links under `/bezirke/…` (e.g. the sign-in link in the Foodsharing message) open in the
  app instead of the browser.
- **Offline page.** `client/native-shell/index.html` is shown when einab.ch cannot be reached.

The website itself now has an Angular service worker (`client/ngsw-config.json`): it caches the app, and when a new
release is deployed, open tabs switch to it on the next page change. Inside the store app the service worker is off
(the page is loaded fresh on every start), and a running app compares `/actuator/info` with the release it started
with instead, so bump `server/deploy/version.txt` with every release as before.

## Pieces

| Where | What |
| --- | --- |
| `client/capacitor.config.ts` | App id `ch.einab.app`, name, live URL, plugins |
| `client/android`, `client/ios` | Native projects (open in Android Studio / Xcode) |
| `client/assets` | Source images for icons and splash (`npm run app:assets` regenerates them) |
| `client/src/app/core/push-notification.service.ts` | Asks for permission, registers the phone, opens tapped notifications and app links |
| `client/src/app/core/app-update.service.ts` | Picks up new releases (service worker in the browser, release number in the app) |
| `server/…/service/PushNotificationService.java` | Stores phones, pushes a copy of every message, forgets uninstalled apps |
| `server/…/service/FirebaseMessagingClient.java` | Sends through Firebase Cloud Messaging (Android and iOS) |
| `server/…/config/AppLinksController.java` | Serves `/.well-known/assetlinks.json` and `/.well-known/apple-app-site-association` |

## Server configuration (environment variables)

| Variable | Value |
| --- | --- |
| `FIREBASE_CREDENTIALS_JSON_BASE64` | Firebase service account JSON key, base64-encoded (`base64 -i key.json`), from the Kubernetes secret. `FIREBASE_CREDENTIALS_JSON` with the plain JSON works too. No key files on the server. |
| `APP_ANDROID_SHA256_FINGERPRINTS` | Comma-separated SHA-256 fingerprints of the Play app signing key and the upload key |
| `APP_IOS_APP_IDS` | `TEAMID.ch.einab.app` (Apple Developer Team ID + bundle id) |

Set the two app-link variables on both servers (production and test): the app claims links of both domains.

Without Firebase credentials push is simply off; without fingerprints / app ids the two `.well-known` files answer 404.

## Building

```bash
cd client
npm install
npm run app:sync        # after every change to capacitor.config.ts or plugins
npm run app:android     # opens Android Studio
npm run app:ios         # opens Xcode
```

Android needs `client/android/app/google-services.json`, iOS needs `GoogleService-Info.plist` added to the `App` target
in Xcode (drag it into `ios/App/App`, tick "Copy items if needed" and the App target). Both come from the Firebase
console and are not secret, but they are project specific.

Store versions: Android `versionCode` / `versionName` in `client/android/app/build.gradle`; iOS "Version" and "Build"
in Xcode (target App → General). Raise them for every store upload.

## First-time setup (needs the account owner)

1. **Accounts.** Google Play Console developer account (one-time 25 USD) and Apple Developer Program (99 USD/year).
   For an association, Apple asks for a D-U-N-S number; as a private person it is not needed, but your name shows as
   the seller.
2. **Firebase project** (console.firebase.google.com, free): create a project, then
   - add an Android app with package `ch.einab.app` → download `google-services.json` → `client/android/app/`;
   - add an iOS app with bundle id `ch.einab.app` → download `GoogleService-Info.plist` → add it in Xcode (see above);
   - Project settings → Service accounts → "Generate new private key" → base64-encode the JSON into
     `FIREBASE_CREDENTIALS_JSON_BASE64` in the app's Kubernetes secret, then delete the file (never commit it).
3. **Apple push key.** developer.apple.com → Certificates, IDs & Profiles → Keys → new key with "Apple Push
   Notifications service (APNs)" → download the `.p8` → Firebase → Project settings → Cloud Messaging → Apple app
   configuration → upload it with the Key ID and your Team ID.
4. **Apple app id.** Identifiers → `ch.einab.app` with the capabilities "Push Notifications" and "Associated Domains".
   In Xcode: target App → Signing & Capabilities → select your team (both capabilities are already in
   `App.entitlements`). Set `APP_IOS_APP_IDS=<TeamID>.ch.einab.app` on the server.
5. **Android signing.** In Android Studio: Build → Generate Signed App Bundle → create an upload keystore (keep it and
   its passwords safe, outside the repository). Enrol in Play App Signing when you upload the first bundle. Then copy
   both SHA-256 fingerprints (Play Console → Test and release → App integrity → App signing) to
   `APP_ANDROID_SHA256_FINGERPRINTS` on the server.
6. **Store listings.** Name, short and full description, screenshots (phone), a privacy policy URL
   (`https://einab.ch/privacy`), the data-safety form (Play) and the App Privacy questions (Apple): the app
   stores Foodsharing ID, name, phone number and a push token. Apple review needs a way to sign in: give them a
   Foodsharing test account in the review notes.
7. **Test before release.** Install on a real phone (push does not work in the iOS simulator), sign in, allow
   notifications, then trigger a message (e.g. book an EinAb) and check that the push arrives and opens the page.
