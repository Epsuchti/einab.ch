# Store listing: EinAb (ch.einab.app)

Texts for Google Play Console and App Store Connect, German (main language) and English. Limits are noted per field.

> Name: "EinAb für Foodsharing" (Eric, 2026-11-15). The app is not run by foodsharing e.V.; "für" marks it as a tool
> for foodsharing, and the description says it is independent. If a store objects to the brand, fall back to "EinAb".

## Common

| Field | Value |
| --- | --- |
| Package / Bundle ID | `ch.einab.app` |
| Category | Play: *Lifestyle* · Apple: *Lifestyle* (secondary: *Productivity*) |
| Price | Free, no in-app purchases, no ads |
| Privacy policy | https://einab.ch/privacy |
| Delete account (Play "Data deletion" URL) | https://einab.ch/konto-loeschen |
| Support URL | https://einab.ch/help |
| Contact e-mail | info@it4user.ch |
| Age rating | Play questionnaire: no violence, no user chat, no gambling → *All ages / PEGI 3* · Apple: *4+* |

## German (de-CH / de-DE)

**App name** (Play 30, Apple 30): `EinAb für Foodsharing`

**Apple subtitle** (30): `Einführungsabholungen planen`

**Play short description** (80):
`Einführungsabholungen bei foodsharing finden, buchen und organisieren.`

**Apple promotional text** (170):
`Neu bei foodsharing? Finde freie Einführungsabholungen in deinem Bezirk, buche mit einem Tipp und erhalte Erinnerungen als Push-Mitteilung.`

**Apple keywords** (100, comma-separated):
`foodsharing,foodsaver,einab,einführungsabholung,lebensmittelrettung,zürich,bezirk,abholung`

**Full description** (Play 4000, Apple 4000):

```
Neu bei foodsharing? Bevor du selbst Lebensmittel retten darfst, begleitest du erfahrene Foodsaver:innen bei Einführungsabholungen (EinAbs). Mit EinAb findest und buchst du diese Termine ganz einfach.

FÜR NEULINGE
• Freie EinAbs in deinem Bezirk sehen, auch ohne Anmeldung
• Mit einem Tipp verbindlich buchen und wieder stornieren
• Treffpunkt, Uhrzeit und Kontakt der EinAb-Geber:in nach der Buchung
• Deinen Fortschritt bis zur Verifizierung im Blick
• Push-Mitteilungen bei Bestätigungen, Änderungen und neuen Terminen

FÜR EINAB-GEBER:INNEN
• EinAbs in wenigen Schritten veröffentlichen
• Sehen, wer gebucht hat, und Notizen zur Einarbeitung festhalten
• Kalender-Abo für deine Termine
• Buchungen und Absagen direkt als Mitteilung

ANMELDUNG OHNE NEUES PASSWORT
Du meldest dich mit deiner Foodsharing-ID an und erhältst einen Anmelde-Link als Nachricht auf foodsharing.network. Dein foodsharing-Passwort sehen wir nie.

DATENSCHUTZ
Kein Tracking, keine Werbung, keine Analysedienste. Gespeichert wird nur, was es für die EinAbs braucht. Dein Konto kannst du jederzeit in der App löschen.

EinAb ist ein ehrenamtliches Hilfsmittel von Foodsaver:innen für foodsharing-Bezirke in der Schweiz. Es wird nicht von foodsharing e.V. oder foodsharing.network betrieben.
```

**What's new / Release notes** (Play 500, Apple 4000):
`Erste Version der EinAb-App: EinAbs finden und buchen, Push-Mitteilungen, Links aus Foodsharing-Nachrichten öffnen direkt in der App.`

## English (en-US / en-GB, optional)

**App name:** `EinAb für Foodsharing` (same in every language)

**Apple subtitle:** `Plan introductory pickups`

**Play short description:**
`Find, book and organise foodsharing introductory pickups in your district.`

**Apple promotional text:**
`New to foodsharing? Find free introductory pickups in your district, book with one tap and get reminders as push notifications.`

**Full description:**

```
New to foodsharing? Before you can save food on your own, you join experienced foodsavers on introductory pickups (EinAbs). EinAb lets you find and book them easily.

FOR NEWCOMERS
• See free EinAbs in your district, even without signing in
• Book with one tap, cancel just as easily
• Meeting point, time and the guide's contact after booking
• Track your progress towards verification
• Push notifications for confirmations, changes and new dates

FOR EINAB GUIDES
• Publish EinAbs in a few steps
• See who booked and keep notes for the onboarding
• Calendar subscription for your dates
• Bookings and cancellations as notifications

NO NEW PASSWORD
Sign in with your Foodsharing ID and get a sign-in link as a message on foodsharing.network. We never see your foodsharing password.

PRIVACY
No tracking, no ads, no analytics. Only what is needed for EinAbs is stored. You can delete your account in the app at any time.

EinAb is a volunteer tool made by foodsavers for foodsharing districts in Switzerland. It is not run by foodsharing e.V. or foodsharing.network.
```

## Review notes / App access

Same text for App Store Connect → App Review Information → Notes, and Play Console → App content → App access
("All or some functionality is restricted" → add instructions).

```
EinAb organises foodsharing introductory pickups ("EinAbs") in Switzerland. Normal users sign in with their Foodsharing ID and get a one-time sign-in link as a message on foodsharing.network (no password in this app).

For review there is a test access that needs no foodsharing.network account:
1. Open the app, choose the district "Zürich" (or it opens there directly).
2. Tap "Login" and enter the Foodsharing ID 1308, then tap "Login-Link senden".
3. Choose "Testen als Neuling" (newcomer) or "Testen als EinAb-Geber:in" (guide). Each tap creates a fresh demo account.
4. Accept the privacy policy. As a newcomer: open "Freie EinAbs", tap an EinAb and book it. As a guide: "Meine EinAbs" shows your EinAbs and bookings; "Neue EinAb" publishes one.

Native features: push notifications (Firebase Cloud Messaging / APNs) for every message the platform sends to the user (booking confirmations, cancellations, new dates), and app links: einab.ch links in foodsharing messages open directly in the app. Push can be turned on and off in Profil. Account deletion: Profil → "Konto löschen".

Contact: Eric Zürcher, info@it4user.ch
```

Apple also asks for a demo account in separate fields: tick **"Sign-in required"**, user name `1308`, password: write
`no password – see notes (tap "Testen als Neuling")`.

## Google Play: Data safety

| Question | Answer |
| --- | --- |
| Does your app collect or share user data? | Yes, collects |
| Is all data encrypted in transit? | Yes |
| Can users request deletion? | Yes, in the app and at https://einab.ch/konto-loeschen |
| Shared with third parties? | No (Firebase and hosting are service providers, not "sharing") |

Collected data types (all: *collected*, *not shared*, *required* unless noted, purpose *App functionality* and
*Account management*, processed not ephemerally):

| Category → type | Why |
| --- | --- |
| Personal info → Name | shown to the EinAb-Geber:in of a booking |
| Personal info → User IDs | Foodsharing ID, sign-in |
| Personal info → Phone number | EinAb-Gebende can reach the person |
| Personal info → Email address | *optional*, only for e-mail notifications and EinAb-Gebende |
| App activity → Other actions | bookings and their status |
| App activity → Other user-generated content | *optional*, survey answers; notes the EinAb-Gebende write |
| Device or other IDs | push token (only when push is allowed), purpose *App functionality* |

Not collected: location, financial info, health, photos, contacts, browsing history, diagnostics, ads ID.

## Apple: App Privacy

Tracking: **No**. Data types, all **linked to the user**, **not used for tracking**, purpose **App Functionality**:

- Contact Info → Name, Phone Number, Email Address
- Identifiers → User ID (Foodsharing ID)
- User Content → Other User Content (bookings, survey answers, notes)

Not collected: location, health, financial, contacts, browsing/search history, usage data, diagnostics,
purchases, sensitive info. (Push tokens are not a separate Apple privacy category.)

## Screenshots

`e2e/store-screenshots.config.ts` makes them: `cd e2e && npx playwright test -c store-screenshots.config.ts`
(starts its own test server on port 8181 with the demo login). Output in `e2e/screenshots/store/`:

| Folder | Size | Store slot |
| --- | --- | --- |
| `play/` | 1080 × 1920 | Play Console → Main store listing → Phone screenshots (2–8) |
| `appstore/` | 1290 × 2796 | App Store Connect → iPhone 6.9" display (required; smaller sizes are scaled) |

Play also needs a **feature graphic** 1024 × 500 and the **app icon** 512 × 512 (from `client/assets`).
