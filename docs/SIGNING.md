# Nowhere Android Signing & Keystore Security Guide

## 1. Overview
The release keystore is used to sign the APK and Android App Bundle (`.aab`) uploaded to Google Play. 

To maintain security best practices:
- Keystores (`*.jks`, `*.keystore`) and sensitive properties files (`keystore.properties`) are strictly excluded from git tracking via `.gitignore`.
- Release signing credentials are never hardcoded in `build.gradle.kts`. Instead, they are dynamically loaded from environment variables, Gradle properties, or local untracked `keystore.properties`.

## 2. Managing Keystore in CI (GitHub Actions)
For GitHub Actions workflows to sign release builds, store the following repository secrets under **GitHub Repository > Settings > Secrets and variables > Actions**:

| Secret Name | Description | Example / Format |
|---|---|---|
| `NOWHERE_KEYSTORE_BASE64` | Base64-encoded string of your `.jks` file | `base64 -w 0 app/nowhere_key.jks` |
| `NOWHERE_KEYSTORE_PASSWORD` | Store password for the keystore | Plain text secret |
| `NOWHERE_KEY_ALIAS` | Key alias in the keystore | `nowhere` |
| `NOWHERE_KEY_PASSWORD` | Key password | Plain text secret |

To generate `NOWHERE_KEYSTORE_BASE64` locally on Windows PowerShell:
```powershell
[Convert]::ToBase64String([System.IO.File]::ReadAllBytes("app/nowhere_key.jks")) | Set-Clipboard
```
Or on Linux/macOS:
```bash
base64 -w 0 app/nowhere_key.jks | pbcopy
```

## 3. Local Development Signing
For building release artifacts locally without setting environment variables:
Create an untracked file `keystore.properties` in the project root:
```properties
NOWHERE_KEYSTORE_PATH=nowhere_key.jks
NOWHERE_KEYSTORE_PASSWORD=your_store_password
NOWHERE_KEY_ALIAS=your_key_alias
NOWHERE_KEY_PASSWORD=your_key_password
```
`keystore.properties` is already listed in `.gitignore` and will never be committed.

## 4. Key Exposure & Play Console Upload-Key Reset (CRITICAL)
If the keystore file or passwords were ever committed to a public repository:
1. **Assume Compromise**: The upload key must be considered compromised.
2. **Play App Signing**: Because Google Play uses Play App Signing (where Google holds the actual app signing key and the uploaded `.jks` is solely an upload key), you can reset the upload key at any time without impacting existing installed users.
3. **Requesting an Upload-Key Reset**:
   - Go to [Google Play Console](https://play.google.com/console).
   - Select the **Nowhere** app.
   - Navigate to **Release > Setup > App integrity**.
   - Under the **App signing** tab, locate **Request upload key reset**.
   - Follow Google's instructions to generate a new key pair and submit the PEM certificate (`.pem`) to Google Play Support.
   - Once approved (typically within 24–48 hours), you can sign subsequent releases with the new key without any disruption to existing users.

## 5. Purging Exposed Keystores from Git History
Deleting a file in git only removes it from future commits. To purge the keystore from past git commits, use `git-filter-repo` or BFG Repo-Cleaner:
```bash
git filter-repo --path app/nowhere_key.jks --invert-paths --force
git push origin main --force
```
