# APK release signing

The release workflow uses these two repository Actions secrets:

| Secret | Local backup |
| --- | --- |
| `ANDROID_SIGNING_PRIVATE_KEY_PEM` | `.signing/private-key.pem` |
| `ANDROID_SIGNING_CERTIFICATE_PEM` | `.signing/certificate.pem` |

Store the complete PEM contents, including the BEGIN/END lines and newlines.
Both files are required to restore the signing identity. Keep them in a password
manager; never commit the private key. These are the same RSA-2048 key and
certificate used for v1.0.3, so existing installations can receive updates.

On each version tag push, Actions converts the PEM pair to a temporary PKCS#12
keystore protected by a randomly generated, masked password. It tests and builds
both Android variants, verifies the APK signatures, and publishes the APKs and
SHA-256 checksums to GitHub Releases. Temporary signing files are removed after
the build. No permanent keystore, alias secret, or password secret is required.

Push a tag such as `v1.0.4` or `v1.0.5-beta.1` to release. Prerelease suffixes
produce GitHub prereleases. The versionName comes from the tag without `v`, and
the versionCode is the workflow run number plus 100. Preserve workflow run
history to keep version codes increasing.
