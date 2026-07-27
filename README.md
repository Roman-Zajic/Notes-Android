# Notes Pusher (Android)

A single-screen Android app: type a note, tap one button, it's pushed to a
GitHub repo's root directory as a new file named with the current date and
time (e.g. `2026-07-27_14-32-05.md`). No reading, listing, or editing of
existing notes — write-only, on purpose. After a successful push the screen
clears for the next note.

This app cannot be compiled locally in this chat environment (no access to
the Android SDK), so it builds automatically on GitHub's own servers via the
included GitHub Actions workflow. You never need Android Studio.

Configuration (token/repo/branch/subdir) is read at **runtime** from a plain
`assets/config.properties` file bundled into the APK -- not baked in at
compile time. This is deliberately the simplest possible mechanism: no
Gradle codegen involved, just a plain file copied into the APK.

## One-time setup

1. **Create a new GitHub repo** for this app's source code (separate from
   your notes repo) — e.g. `notes-pusher-android`. Push everything in this
   folder to it.

2. **Create a fine-grained GitHub Personal Access Token**, scoped narrowly:
   - Only the one notes repo (e.g. `Roman-Zajic/Notes`)
   - Repository permission: **Contents: Read and write** — nothing else
   - Use a *separate* token from any you use for the desktop app, so losing
     the phone/APK never means revoking the desktop sync too.

3. In the **new app repo** (not the notes repo), go to
   **Settings -> Secrets and variables -> Actions -> New repository secret**
   and add:

   | Name | Value |
   |---|---|
   | `NOTES_GH_TOKEN` | the token from step 2 |
   | `NOTES_GH_REPO` | `owner/repo-name` of your notes repo |
   | `NOTES_GH_BRANCH` | `main` (or whichever branch) |
   | `NOTES_GH_SUBDIR` | leave blank to push to the repo root |

   These secrets are encrypted by GitHub, never appear in logs, and are
   only ever decrypted transiently inside GitHub's own build runner — they
   never pass through me or get written into this repo's history.

4. Push to `main` (or open the **Actions** tab and run the "Build APK"
   workflow manually via **Run workflow**).

5. Once the run finishes (green checkmark), open it, scroll to
   **Artifacts**, and download **notes-pusher-debug-apk** — it's a zip
   containing `app-debug.apk`.

## Installing on your phone

1. Get `app-debug.apk` onto your phone (email it to yourself, upload to a
   cloud drive, or `adb install app-debug.apk` over USB).
2. Tap the file to install. Android will prompt to allow installs from
   whichever app opened it ("install unknown apps") — this is expected for
   a sideloaded APK; allow it just for that one app if asked.
3. Open **Notes**, type something, tap **Push to GitHub**.

## If you ever need to change the target repo/branch

Update the repo secrets (step 3 above) and re-run the workflow — no code
changes needed.

## Security notes

- The token is baked into the compiled APK (`BuildConfig` fields) so the
  app can authenticate with no login screen. An APK can be decompiled and
  the token recovered — that's exactly why step 2 uses a narrowly-scoped,
  dedicated token rather than a broad one. Don't upload the APK anywhere
  public.
- If your phone is ever lost, revoke `NOTES_GH_TOKEN` on GitHub
  immediately (Settings -> Developer settings -> Fine-grained tokens),
  generate a new one, update the repo secret, and rebuild.
