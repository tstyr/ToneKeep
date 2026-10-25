# Preview signing

`preview.keystore` is an intentionally public **test-only** signing identity, generated specifically for this project. Alias: `tonekeep-preview`; both passwords: `android`.

It lets preview APKs built locally and in GitHub Actions update one another. It is not a private production credential. Never use it for production distribution, payments, trusted integrations, or sensitive data. A production app must use a private release key supplied through protected CI secrets and a different application ID.

The preview application ID is `jp.tonekeep.app.debug`. This application stores download metadata only and does not handle account credentials.
