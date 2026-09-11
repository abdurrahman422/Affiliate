# OwnerLink — Known Android Limitations

These are product constraints, not bugs to bypass.

## 1. Android background execution

Android may restrict/kill background work depending on version/vendor/battery settings.

Use documented foreground service/reconnect behavior.
Provide user setup guidance for battery optimization if needed.

## 2. Reboot / first unlock

After device reboot, some credential-encrypted user data may remain unavailable until the owner performs the device's first unlock.

OwnerLink must fail gracefully and reconnect when access becomes available.

## 3. Protected screens

Apps/windows may prevent screenshots.
OwnerLink must not bypass these protections.

## 4. Accessibility screenshot rate

Accessibility screenshot APIs can be rate-limited.
V1 targets low FPS, not smooth remote-desktop video.

## 5. Accessibility permission

The owner must manually enable the service.
Do not attempt to bypass this.

## 6. Files

Android does not grant unrestricted access to every private app directory.
OwnerLink should expose only storage Android permits.

## 7. Camera/microphone

Modern Android has while-in-use and foreground-service restrictions.
Remote starting camera/mic may be unavailable depending on device state/OS.
No bypass should be attempted.

## 8. OEM behavior

Samsung/Xiaomi/Oppo/etc. may have extra background/battery policies.
Document tested device-specific setup if encountered.
