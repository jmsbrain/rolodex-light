# Switching to the phone's contacts

Right now the tool keeps its own contact list (imported from .vcf). It can't see the phone's real, synced contacts. The SDK doesn't allow it yet:

- `READ_CONTACTS` / `WRITE_CONTACTS` aren't in the permission allowlist.
- Tools can't get a `ContentResolver`. `android.content.ContentProvider*` imports are blocked, and `lightContext` doesn't expose one.

Request: https://github.com/orgs/lightphone/discussions/276

## When Light allows it

Light will probably do one of two things.

**A. Allow the permissions and expose a ContentResolver** (e.g. `lightContext.contentResolver`), and unblock `ContentProviderOperation`:

1. Move `pending/PhoneContactsSource.kt` to `tool/src/main/kotlin/com/jmsbrain/rolodex/data/`.
2. In `ContactListScreen.kt`, change
   `Contacts.init { LocalContactsSource(lightContext.filesDir) }`
   to
   `Contacts.init { PhoneContactsSource(lightContext.contentResolver) }`
   (using whatever name Light gives it).
3. In `tool/lighttool.toml`, add `"android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS"` to `permissions` (or the capability Light names).
4. Ask for the permission on first launch, the way Light's docs say to (`LightServiceMethod.GetPermission` / `RequestPermissionComponent`).
5. Bump `versionCode` and `versionName` (MINOR, e.g. 0.2.0).
6. Run `./gradlew :tool:assembleDebug :tool:lintDebug`, then test on the phone: view, edit, add and delete a contact, and check it reaches the server (DAVx5).

**B. Add a contacts API of their own** (e.g. `lightContext.contacts`):

Write a new `ContactsSource` on top of it, using `pending/PhoneContactsSource.kt` as the guide for field and type mapping. Then do steps 2–6 above.

Nothing else should need to change. Screens only talk to `ContactsSource`.

## Optional once on phone contacts

- Offer a one-time "move my imported contacts to the phone" (read `LocalContactsSource`, save each to `PhoneContactsSource`).
- IMPORT/EXPORT can stay. They work with either source.
