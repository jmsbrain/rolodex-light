# Rolodex (LightOS tool)

A full contacts tool for the Light Phone III, built on the [Light SDK](https://github.com/lightphone/light-sdk). It's the official-tool version of [Rolodex](https://github.com/jmsbrain/Rolodex).

**Status:** early (0.1.1). It works with its own contact list for now. Using the phone's synced contacts is waiting on Light ([discussion](https://github.com/orgs/lightphone/discussions/276)). See [ENABLE_PHONE_CONTACTS.md](ENABLE_PHONE_CONTACTS.md).

## What it does

- Searchable contact list. Each card shows every field: phones, emails, addresses, work, nicknames, related people, messaging, SIP, websites, dates, notes.
- Tap a phone number to open the LightOS phone screen.
- Add, edit and delete contacts. Types (MOBILE, HOME…) are picked from a list.
- **Import:** upload .vcf files through Tool Manager (*Rolodex → Import contacts*), then tap IMPORT.
- **Export:** saves every contact to one .vcf, downloadable from Tool Manager (*Rolodex → Export contacts*).

No permissions and no internet. Contacts stay in the tool's private storage on the phone.

Tested on a Light Phone III (2026-10-10): add, edit (incl. type picker), delete, dialing through LightOS, .vcf import (3.0 and 2.1 with encoded names) and export.

Known limit: phone numbers open Light's keyboard on letters. Tap 123 first. The SDK's text editor has no option to start on numbers.

## Where things are

```
tool/lighttool.toml                    id, version, permissions, capabilities
tool/src/main/kotlin/com/jmsbrain/rolodex/
  ToolEntryPoint.kt                    Tool Manager folders
  data/Contact.kt                      contact model and field kinds
  data/ContactsSource.kt               the one interface screens use
  data/LocalContactsSource.kt          today's storage (JSON in the tool)
  data/VCard.kt                        .vcf read/write
  data/Formats.kt                      dates, phone numbers, names
  ui/                                  list, card, editor, import, pickers
pending/PhoneContactsSource.kt         phone contacts, ready for when Light allows it
```

## Build

```
./gradlew :tool:assembleDebug :tool:lintDebug
```

Then follow Light's README to submit through the dashboard.

## License

MIT. The Rolodex code in `tool/` is © 2026 jmsbrain. Light's SDK is © 2026 The Light Phone (see `LICENSE`).
