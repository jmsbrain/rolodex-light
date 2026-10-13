# Rolodex (LightOS tool)

A full contacts tool for the Light Phone III, built on the [Light SDK](https://github.com/lightphone/light-sdk). It's the official-tool version of [Rolodex](https://github.com/jmsbrain/Rolodex).

**Status:** early (0.2.0). It keeps its own contact list, filled by .vcf import or by syncing with a CardDAV server. Using the phone's own contacts is waiting on Light ([discussion](https://github.com/orgs/lightphone/discussions/276)). See [ENABLE_PHONE_CONTACTS.md](ENABLE_PHONE_CONTACTS.md).

## What it does

### Browse
- Searchable contact list. **CLEAR** (or back) empties the search.
- Each card shows, in this order: full name breakdown (when there's a prefix, suffix or phonetic name), phones, emails, addresses, work (company, title, department, role), nicknames, related people, messaging, SIP, websites, dates, notes. Copies of the same number or email show once.
- **Tap a phone number** to open the LightOS phone screen.
- **Tap an email, address or website** to copy it. **Long-press anything** (the name too) to copy it.

### Edit
- **EDIT** on a card, **ADD** on the list. Every field above can be edited, except phonetic names and role. Types (MOBILE, HOME…) are picked from a list. A type that isn't in the list (WORK FAX, a custom label) stays as it is.
- Dates are typed as `MM/DD/YYYY`, or `MM/DD` without a year. A date the server sent in another form is left alone.
- Nothing is saved until **SAVE**. CANCEL or back asks before throwing away changes. **DELETE CONTACT** asks first.

### Share
- **SHARE** on a card:
  - **Copy as text:** the name, then "Mobile: …", "Email: …" and so on. Notes are left out. Paste it into a message.
  - **Save as contact file (.vcf):** every field, notes included. Download it from Tool Manager (*Rolodex → Export contacts*).

### Import and export
- **IMPORT:** upload .vcf files through Tool Manager (*Rolodex → Import contacts*), then tap a file. One contact opens in the editor to check first. Several show as a list: **IMPORT ALL**, or tap one to check it. Once imported, you're back on the list, which says how many were added.
- **EXPORT** (on the import screen): saves every contact to one .vcf, downloadable from Tool Manager (*Rolodex → Export contacts*).

### Sync
Rolodex syncs with a **CardDAV** server (mailbox.org, Fastmail, Nextcloud, iCloud…), the same way plain-Android Rolodex does.

1. Tap the sync line under the search box (it says **SYNC: OFF** until you sign in).
2. Enter your email, password and server (`https://dav.mailbox.org/` is filled in), then **SIGN IN**. With two-factor sign-in, use an app password.
3. Rolodex finds your address books and syncs the main one. Tap others to sync them too. Turning a book off removes its contacts from Rolodex, not from the server.
4. With more than one book, **NEW CONTACTS GO TO** picks where new contacts are saved.
5. Contacts that were already in Rolodex before you signed in stay on the phone only. **ADD TO …** on the sync screen uploads them (if they came from the same server, you'd have them twice).

How it syncs:
- **When:** right after you save, delete or import. Also when you open Rolodex (if the last sync was over 10 minutes ago), every hour in the background if Android allows it, and on **SYNC NOW**.
- **Both ways**, merged kind by kind (phones, emails, addresses…). For each kind, the phone's version wins if you changed it there, and the server's is kept otherwise.
- **Nothing extra is lost:** Rolodex only rewrites the parts of a contact it shows. Photos, groups and fields it doesn't know stay on the server untouched.
- **Numbers the server can't hold stay on the phone.** mailbox.org has room for two mobile, two work and two home numbers, and a few more. After every upload Rolodex reads the contact back. Anything the server didn't keep is listed under **ONLY ON THIS PHONE** on the sync screen, and the sync line says **SOME DETAILS ONLY ON THIS PHONE**. Change its type (say, a third mobile to OTHER) to move it onto the server.
- The sync line shows how it's going (**SYNCED 5 MINUTES AGO**, **SYNC PROBLEM: TAP FOR DETAILS**). Tap it for the sync screen.
- **SIGN OUT** removes the synced contacts from Rolodex. They stay on the server. Phone-only contacts stay too.

Synced contacts live inside Rolodex, not in the phone's contacts, so LightOS's own phone and messages screens don't see them (see ENABLE_PHONE_CONTACTS.md).

## What plain-Android Rolodex does that this can't

A Light SDK tool can't open other apps, send texts, or read the phone's contacts (`Intent`, `startActivity`, `contentResolver` and `getSystemService` are blocked, and LightOS only offers `OpenDialer`). So these stay in [plain-Android Rolodex](https://github.com/jmsbrain/Rolodex) only:

- **Group text.** No way to start a text from a tool.
- **Answering other apps** (add to contacts, pick a contact, quick contact, opening a .vcf) and being the default contacts app.
- **Opening maps or a website.** Tapping copies instead, as the Android app does when nothing can open it.
- **Sharing straight into SMS, Signal, WhatsApp.** SHARE copies the text or saves a .vcf instead.
- **Editing the phone's own contacts** (and with them, DAVx5, merged contacts, WhatsApp/Signal copies). Waiting on Light.

## Privacy

The only permission is **internet**, for sync. Rolodex connects only to the server you sign in to, and only after you sign in. The password is stored inside the tool, encrypted with a key kept in Android's Keystore. Otherwise contacts stay in the tool's private storage, and only leave the phone through sync or files you export.

Known limits: phone numbers open Light's keyboard on letters (tap 123 first). The password shows while you type it: the SDK's text editor has no password mode.

## Where things are

```
tool/lighttool.toml                    id, version, permissions, capabilities
tool/src/main/kotlin/com/jmsbrain/rolodex/
  ToolEntryPoint.kt                    Tool Manager folders
  Setup.kt                             opens storage (screens and background sync)
  data/Contact.kt                      contact model and field kinds
  data/ContactsSource.kt               the one interface screens use
  data/LocalContactsSource.kt          today's storage (JSON in the tool), with sync state
  data/Rows.kt                         contacts as Android-style rows, for vCards and sync
  data/VCard.kt                        .vcf read/write, line by line
  data/Formats.kt                      dates, phone numbers, names
  sync/CardDav.kt                      talks to the CardDAV server
  sync/CardSync.kt                     two-way sync
  sync/SyncMerge.kt                    merging, and what the server dropped
  sync/SyncSettings.kt                 sign-in (password encrypted), books, status
  sync/SyncJob.kt                      hourly background sync
  ui/                                  list, card, editor, import, sync, pickers
tool/src/test/                         vCard, merge and storage tests
pending/PhoneContactsSource.kt         phone contacts, ready for when Light allows it
```

## Build

```
./gradlew :tool:assembleDebug :tool:lintDebug :tool:testDebugUnitTest
```

Then follow Light's README to submit through the dashboard.

### Testing sync

`tools/MockCardDav.java` in [plain-Android Rolodex](https://github.com/jmsbrain/Rolodex) is a fake CardDAV server with made-up contacts (`java tools/MockCardDav.java`, or `... ox` to drop numbers the way Open-Xchange might). It's plain http, so for an emulator test only, add `"cleartext-http"` to `capabilities` in `tool/lighttool.toml` and sign in to `http://10.0.2.2:5233/`. Take it out again before a release.

## License

MIT. The Rolodex code in `tool/` is © 2026 jmsbrain. Light's SDK is © 2026 The Light Phone (see `LICENSE`).
