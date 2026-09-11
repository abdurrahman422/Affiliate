# OwnerLink — V1 Acceptance Tests

The project is NOT complete until core tests below pass or a platform limitation is clearly documented.

## A. Web auth

1. Visit `/` without login -> redirected to `/login`.
2. Wrong username/password -> no dashboard.
3. Correct credentials -> dashboard.
4. Logout -> dashboard no longer accessible.

## B. Pairing

1. Fresh Android app shows QR.
2. Dashboard Add Device can scan or paste QR payload.
3. Successful pair adds device to dashboard.
4. Same QR cannot pair a second time.
5. Device reconnects after app/network reconnect without QR.
6. Remove Device revokes device.
7. Removed phone generates new QR for re-pairing.

## C. Two-device isolation

Pair:
- Home Phone
- Business Phone

Verify:
1. Both appear separately.
2. Opening Home Phone never returns Business Phone files.
3. Opening Business Phone never returns Home Phone files.
4. Removing one does not remove the other.

## D. Online/offline

1. Phone connected -> Online.
2. Disable phone network -> eventually Offline.
3. Restore network -> reconnects automatically -> Online.
4. No new QR required.

## E. Gallery

1. Gallery page lists current phone images.
2. Thumbnails load.
3. Select image -> preview if implemented.
4. Download -> original image downloaded.
5. Server does not retain gallery copy after request.

## F. Files

1. Browse allowed storage.
2. Open nested folder.
3. Download PDF/image/ZIP/text sample.
4. Filename preserved.
5. File bytes/hash match source.
6. Unauthorized/protected path returns safe error.

## G. Remote screen

Prerequisite:
Accessibility service enabled.

1. Screen page obtains frame.
2. Frames refresh at low FPS.
3. Clicking screen triggers correct approximate phone location.
4. Swipe works.
5. Back works.
6. Home works.
7. Recents works where platform permits.
8. After gesture, fresh frame arrives.
9. Protected screen returns blank/protected message without bypass attempt.

## H. Security

1. API device data inaccessible without owner login.
2. Production rejects/redirects insecure HTTP as configured by Caddy.
3. Password not stored plaintext.
4. QR token high entropy.
5. Session token invalid after device removal.
6. No file contents stored permanently server-side.
7. Logs do not print secrets.

## I. Large-file behavior

At least test a file hundreds of MB if environment permits.

Verify:
- backend does not load entire file into RAM;
- streaming works or failure is graceful;
- no abandoned temp file remains.

## J. Build quality

1. Backend starts from documented command.
2. Android debug APK builds.
3. No core `TODO()` / placeholder functions.
4. No mocked gallery/files for final build.
5. README setup steps match actual code.
