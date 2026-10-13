# Rolodex for LightOS

This repo is a fork of [lightphone/light-sdk](https://github.com/lightphone/light-sdk). The tool lives in `tool/`. Everything else is Light's SDK.

The plain-Android version (the one in daily use) is https://github.com/jmsbrain/Rolodex. That one isn't bound by these rules.

## Before any work

1. Sync with Light's SDK first. Submissions must use the latest SDK.
   ```
   git fetch upstream && git merge upstream/main
   ```
   Conflicts in `tool/` resolve to ours. Anything outside `tool/` resolves to theirs.
2. Re-read `TOOL_GUIDELINES.md`, `CONTRIBUTING.md` (AI/LLM policy) and `docs/tool_metadata/README.md` if they changed in the merge.

## Rules

- Only change `tool/`, plus the Rolodex docs at the root (`CLAUDE.md`, `ROLODEX.md`, `ENABLE_PHONE_CONTACTS.md`, `pending/`). Never edit Light's SDK, plugin, lint rules, docs or examples. That would break syncing, and Light builds from their SDK anyway.
- Screens extend `LightScreen` / `SimpleLightScreen`, with logic in a `LightViewModel`. Navigate with `navigateTo` / `goBack`.
- Use the SDK UI (`com.thelightphone.sdk.ui`: `LightTopBar`, `LightBottomBar`, `LightText`, `LightTextField`, `LightTextInputEditor`, `LightScrollView`, `LightFullscreenModal`, `lightClickable`, `gridUnitsAsDp`). No Material components, no custom styling where an SDK piece exists.
- Never use `Context`, `Activity`, `Intent`, `LocalContext` or `LocalView`. Use `lightContext` (filesDir, fileShare, dataStore) and `callRemoteServiceMethod`. The build blocks these anyway: see `BLOCKED_IMPORTS` in `plugin/.../LightSdkPlugin.kt`.
- Permissions and capabilities go only in `tool/lighttool.toml`, and only allowlisted ones (`LightToolPolicy` in `plugin/.../LightToolMetadata.kt`). Libraries only from `plugin/src/main/resources/.../allowed-dependencies.txt`.
- Bump `versionCode` (+1) and `versionName` (MAJOR.MINOR.PATCH) in `lighttool.toml` for every release. Never change `id`.
- Tag releases `rolodex-vX.Y.Z`, never plain `vX.Y.Z`: those tags are Light's SDK versions, which this fork inherits.
- Keep code comments short, in the owner's voice. No long explanations (Light's AI/LLM policy).
- Never post to Light's GitHub (issues, discussions, PRs) for the owner. Drafts are notes for them to rewrite.
- Contacts all go through `ContactsSource` (`tool/.../data/`). Don't touch Android contacts directly anywhere else. Sync (`tool/.../sync/`) uses `LocalContactsSource`'s sync methods.
- Keep sync behavior in step with plain-Android Rolodex (`CardSync.kt`, `SyncCard.kt`). `data/VCard.kt` and `sync/SyncMerge.kt` are ports of its `SyncCard.kt`; the tests in `tool/src/test/` mirror its `SyncCardTest.kt`.

## Check before calling something done

```
./gradlew :tool:assembleDebug :tool:lintDebug :tool:testDebugUnitTest
```

All must pass. The first runs Light's config and source checks; the second runs Light's lint rules; the third runs Rolodex's tests.

## Testing

- Emulator: 1080×1240, API 34, no Google Play. For LightOS features (dialer, Tool Manager), see `docs/system_app/README.md`.
- `tool/scripts/emulator-start.sh` boots the `Light_Phone` emulator. `tool/scripts/emulator-install.sh` builds for the LightOS emulator, installs on `emulator-5554` only and opens Rolodex. Add `--testdata` to copy `tool/testdata/vcf` into the import folder. It puts `serverPackage` back to `com.lightos` when done.
- Light Phone III: `adb install -r tool/build/outputs/apk/debug/tool-debug.apk`. Needs "Any tools" on.
- Use made-up contacts for screenshots. Never the owner's real ones.
