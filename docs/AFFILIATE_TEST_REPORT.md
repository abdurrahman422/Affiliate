# Affiliate verification report

Executed locally on 2026-09-14 before the Affiliate branch was pushed.

- Backend task engine, dashboard, and Call Log regressions: 46 passed, 0 failed
- Android JVM tests: 28 passed, 0 failed
- Android connected tests: 8 passed, 0 failed on a Moto G(60), Android 12
- Android release lint: 0 errors (31 non-blocking warnings)
- Debug APK, signed release APK, and signed AAB: built successfully
- Debug APK installation: successful with `adb install -r`
- Real-device launch: successful; main activity remained resumed with no fatal exception
- WebSocket smoke test: one `socket connecting`, one `socket open`, and one `UNPAIRED_HELLO sent`; no connect/close loop in the observation window
- Call Log permission smoke test: `READ_CALL_LOG` remained denied; the permission-denied UI/domain path passed without reading private data
- Local backend smoke test: `/health` 200, branded login 200, unauthenticated dashboard 303 to `/login`
- Stable production deployment: intentionally unchanged; Affiliate has a separate non-auto-deploy Render blueprint
- Not executed: granting Call Log access and viewing real phone records, unlocked manual visual/performance walkthrough, production Affiliate deployment, FCM delivery, multi-device task submission, and live dashboard review of a physical proof file
