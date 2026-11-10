# Google Drive = AI Brain — what changed

Root cause of the "demo drive account": CI built the APK with a random keystore (no debug.keystore.base64),
so Google refused the sign-in (SHA-1 mismatch) and the old code silently fell back to a FAKE connection
(hard-coded owner e-mail + built-in API key).

One-time setup (cannot be done from code):
1. Cloud Console -> APIs & Services -> Library -> enable "Google Drive API".
2. Credentials -> Android OAuth client (package com.aistudio.archerai.hudx9q) -> add the SHA-1 shown in the app (ℹ️ button).
3. OAuth consent screen in Testing mode -> add your Gmail as Test user.
4. GitHub -> Settings -> Secrets -> Actions -> DEBUG_KEYSTORE_BASE64 = base64 of your debug.keystore (keeps SHA-1 stable).
