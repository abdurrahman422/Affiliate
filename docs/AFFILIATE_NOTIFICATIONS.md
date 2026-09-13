# Affiliate notification setup

Live task publication, review, revision, and coin events use the existing authenticated device WebSocket. Android WorkManager performs deferred sync/retry when a network is available, so the task list and submission queue do not depend solely on the foreground socket.

No Firebase project credentials were present in this repository. The Android notification adapter is ready for task-event display, but FCM wake-up delivery is intentionally not enabled or fabricated. To add it later, create a Firebase Android app for the existing application ID, keep service-account credentials only in the server environment, add `google-services.json` through the approved secret/configuration process, register device push tokens against authenticated task identity, and send only opaque event IDs. Never include task answers, dashboard tokens, or other secrets in push payloads.
