# Remote Workspace experiment

Branch: `remote-virtual-display`

Remote Workspace is deliberately separate from the physical-screen Remote Screen feature. It creates an app-owned secondary Android display, launches a selected launcher activity on that display, captures only that display, and sends gestures with the secondary display ID.

## Consent and security

- The owner must open Affiliate Settings and tap **Prepare Remote Workspace**.
- Android's MediaProjection confirmation must be accepted locally. Affiliate does not bypass or auto-accept it.
- The grant is process-local and is never written to disk or sent to the server.
- Dashboard access still requires the authenticated owner session and an actively paired device.
- The feature does not bypass the lock screen, `FLAG_SECURE`, DRM, passwords, biometrics, or app-specific secondary-display restrictions.
- A persistent Affiliate foreground service remains visible while the device connection is active.

## Moto G(60) probe result

The connected Moto G(60 runs Android 12/API 31 and advertises `android.software.activities_on_secondary_displays`.

An own-content virtual display was successfully created as display 10 at 720x1600. Android correctly rejected launching an activity onto the private display. Creating a public display without a MediaProjection grant was also correctly rejected with `CAPTURE_VIDEO_OUTPUT ... or an appropriate MediaProjection token` required. The implementation therefore uses the supported MediaProjection consent path rather than hidden APIs, root, or an ADB dependency.

## Remaining device verification

After installing version 2.6.0:

1. Enable **Affiliate remote workspace control** in Android Accessibility settings.
2. In Affiliate Settings, tap **Prepare Remote Workspace** and accept Android's prompt.
3. Deploy this branch's backend to a non-production test service.
4. Open the paired device and select **Remote Workspace**.
5. Confirm that a supported app opens on the secondary display and that tap/swipe do not affect the physical display.

Some apps may move their existing task, refuse a secondary display, show a blank protected window, or otherwise behave differently. That behavior is controlled by Android and the target app.
