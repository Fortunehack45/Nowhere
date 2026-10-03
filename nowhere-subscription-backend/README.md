# Nowhere Subscription Backend

A production-ready, stateless Go backend for verifying Google Play subscriptions for **Nowhere** (`com.nowhere.gps.locationchanger`).

Built strictly with Go, standard libraries, and the official Google Play Developer API v3 (`androidpublisher/v3` Subscriptions v2).

- **Stateless & Database-free**: No SQLite, Postgres, Redis, Supabase, or Firebase required.
- **Fail-Closed Security**: Evaluates Google Play authoritative subscription lifecycle states; non-active, expired, unverified, or unknown future states never grant entitlement.
- **Server-Side Security**: All Google service account credentials remain server-side. Zero private keys, service account JSON files, or licensing RSA public keys are embedded in the Android app.
- **Cloud Run Native**: Deploys seamlessly to Google Cloud Run listening on `0.0.0.0:$PORT` using Application Default Credentials (ADC).
- **Auto-Acknowledgment**: Automatically acknowledges pending active/entitled subscriptions so Google Play does not automatically refund the purchase after 3 days.
- **Resilient Upstream Handling**: Exponential retry/backoff for transient Google 5xx and network socket failures without retrying invalid 4xx client requests.
- **In-Memory Rate Limiting**: Per-instance token-bucket rate limiting without external dependencies.
- **Safe Structured Logging**: Zero logging of sensitive purchase tokens, authorization headers, or private keys.

---

## Table of Contents

1. [Architecture & Lifecycle](#1-architecture--lifecycle)
2. [Local Setup](#2-local-setup)
3. [Environment Variables](#3-environment-variables)
4. [Google Cloud Setup](#4-google-cloud-setup)
5. [Google Play Console Setup](#5-google-play-console-setup)
6. [Required IAM Permissions](#6-required-iam-permissions)
7. [Cloud Run Deployment](#7-cloud-run-deployment)
8. [Testing with Play License Testers](#8-testing-with-play-license-testers)
9. [API Request & Response Specification](#9-api-request--response-specification)
10. [Kotlin Client Integration Example](#10-kotlin-client-integration-example)
11. [Security Considerations](#11-security-considerations)

---

## 1. Architecture & Lifecycle

### System Flow

```
+------------------+         +----------------------------+         +-------------------------------+
|  Android Device  |         |   Cloud Run Go Backend     |         | Google Play Developer API v3  |
|  (Nowhere App)   |         | (nowhere-sub-verifier SA)  |         |      (Subscriptions v2)       |
+------------------+         +----------------------------+         +-------------------------------+
         |                                  |                                       |
         | 1. Purchase via Play Billing     |                                       |
         |    (receives purchaseToken)      |                                       |
         |                                  |                                       |
         | 2. POST /api/v1/google-play/verify                                       |
         |    {packageName, productId,     |                                       |
         |     purchaseToken}               |                                       |
         |--------------------------------->|                                       |
         |                                  | 3. Subscriptionsv2.Get(pkg, token)    |
         |                                  |    (with transient retry/backoff)     |
         |                                  |-------------------------------------->|
         |                                  |                                       |
         |                                  | 4. SubscriptionPurchaseV2 response    |
         |                                  |<--------------------------------------|
         |                                  |                                       |
         |                                  | 5. If ACKNOWLEDGEMENT_STATE_PENDING:  |
         |                                  |    Purchases.Subscriptions.Acknowledge|
         |                                  |-------------------------------------->|
         |                                  |                                       |
         |                                  | 6. Evaluate Entitlement:              |
         |                                  |    - ExpiryTime vs UTC Now            |
         |                                  |    - State (ACTIVE/GRACE/CANCELED...) |
         |                                  |                                       |
         | 7. Return verified JSON payload  |                                       |
         |    {"verified":true,             |                                       |
         |     "entitlement":"pro",         |                                       |
         |     "active":true,...}           |                                       |
         |<---------------------------------|                                       |
```

### SubscriptionPurchaseV2 State Matrix

| State | Google Description | Entitlement Result | Pro Granted? |
|---|---|---|---|
| `SUBSCRIPTION_STATE_ACTIVE` | Active recurring or prepaid plan | `entitlement: "pro"` | **Yes** (if `ExpiryTime > Now`) |
| `SUBSCRIPTION_STATE_IN_GRACE_PERIOD` | Renewal payment failed; Google grace period active | `entitlement: "pro"` | **Yes** (if `ExpiryTime > Now`) |
| `SUBSCRIPTION_STATE_CANCELED` | User canceled renewal; paid cycle unexpired | `entitlement: "pro"` | **Yes** (until `ExpiryTime`) |
| `SUBSCRIPTION_STATE_CANCELED` | User canceled renewal; paid cycle passed | `entitlement: "none"` | **No** (`active: false`) |
| `SUBSCRIPTION_STATE_ON_HOLD` | Grace period ended without payment; suspended | `entitlement: "none"` | **No** (`active: false`) |
| `SUBSCRIPTION_STATE_PAUSED` | User temporarily paused subscription | `entitlement: "none"` | **No** (`active: false`) |
| `SUBSCRIPTION_STATE_PENDING` | Payment awaiting processing at checkout | `entitlement: "none"` | **No** (`active: false`) |
| `SUBSCRIPTION_STATE_EXPIRED` | Subscription period elapsed | `entitlement: "none"` | **No** (`active: false`) |
| `SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED` | Pending signup payment failed/canceled | `entitlement: "none"` | **No** (`active: false`) |
| `SUBSCRIPTION_STATE_UNSPECIFIED` / Unknown Future | Unmapped state (fail-closed) | `entitlement: "none"` | **No** (`active: false`) |

---

## 2. Local Setup

### Prerequisites
- Go 1.22+ installed (`go version`)
- Google Cloud CLI (`gcloud`) installed
- Access to a Google Cloud project with Google Play Developer API enabled

### Steps

1. **Clone or navigate to the backend directory**:
   ```bash
   cd nowhere-subscription-backend
   ```

2. **Copy the example environment configuration**:
   ```bash
   cp .env.example .env
   ```

3. **Authenticate for Local Development (Application Default Credentials)**:
   ```bash
   gcloud auth application-default login
   ```
   *Alternative:* If using a service account key file locally:
   ```bash
   export GOOGLE_APPLICATION_CREDENTIALS="/absolute/path/to/sa-key.json"
   ```

4. **Run the test suite**:
   ```bash
   go test -v -race ./...
   ```

5. **Start the server locally**:
   ```bash
   go run ./cmd/server
   ```
   The server will start listening on `0.0.0.0:8080`.

6. **Verify health endpoint**:
   ```bash
   curl -i http://localhost:8080/health
   ```
   Expected response:
   ```http
   HTTP/1.1 200 OK
   Content-Type: application/json
   Date: Sat, 03 Oct 2026 08:30:00 GMT

   {"status":"ok"}
   ```

---

## 3. Environment Variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `ANDROID_PACKAGE_NAME` | **Yes** | — | Must match the app package: `com.nowhere.gps.locationchanger`. |
| `GOOGLE_PLAY_PRODUCT_IDS` | **Yes** | — | Comma-separated list of allowed subscription product IDs: `nowhere_premium,nowhere_premium_yearly`. |
| `PORT` | No | `8080` | Port for the HTTP server. Automatically set by Cloud Run. |
| `APP_ENV` | No | `production` | `production`, `development`, or `test`. |
| `APP_LOG_LEVEL` | No | `INFO` | Structured log level (`INFO` or `DEBUG`). |
| `MAX_REQUEST_BODY_BYTES` | No | `16384` | Maximum incoming request body size (16 KB default). Prevents memory exhaustion attacks. |
| `RATE_LIMIT_RPS` | No | `10.0` | In-memory token bucket refill rate (requests/sec per IP). |
| `RATE_LIMIT_BURST` | No | `20` | In-memory token bucket burst capacity per IP. |
| `REQUEST_TIMEOUT_SECONDS` | No | `10` | Timeout for downstream Google Play Developer API calls. |
| `GOOGLE_APPLICATION_CREDENTIALS` | Local Only | — | Path to service account JSON key file for local testing only. **Never set on Cloud Run.** |

---

## 4. Google Cloud Setup

1. **Set your Google Cloud project**:
   ```bash
   gcloud config set project YOUR_PROJECT_ID
   ```

2. **Enable required Google APIs**:
   ```bash
   gcloud services enable \
     androidpublisher.googleapis.com \
     run.googleapis.com \
     artifactregistry.googleapis.com \
     cloudbuild.googleapis.com
   ```

3. **Create a dedicated Cloud Run Service Account**:
   ```bash
   gcloud iam service-accounts create nowhere-sub-verifier \
     --description="Service Account for Nowhere Subscription Verification Cloud Run service" \
     --display-name="Nowhere Subscription Verifier"
   ```
   The service account email will be:
   `nowhere-sub-verifier@YOUR_PROJECT_ID.iam.gserviceaccount.com`

---

## 5. Google Play Console Setup

To allow your Google Cloud service account to query the Google Play Developer API:

1. Open the [Google Play Console](https://play.google.com/console).
2. Go to **Developer account** (gear icon) -> **API access**.
3. Under **Google Cloud project**, link your Google Cloud project (`YOUR_PROJECT_ID`) if not already linked.
4. Under **Service accounts**, find `nowhere-sub-verifier@YOUR_PROJECT_ID.iam.gserviceaccount.com` (or click **Invite user** under **Users & permissions**).
5. In **App permissions**, select the app: **Nowhere** (`com.nowhere.gps.locationchanger`).
6. In **Account permissions** / **App permissions**:
   - Check **View financial data, orders, and cancellation survey responses** (or **Manage orders and subscriptions**).
   - Check **View app information and download bulk reports**.
7. Click **Apply** -> **Invite user** / **Save changes**.

> [!NOTE]
> It can take 5 to 15 minutes for permissions granted in the Play Console to propagate across Google's OAuth infrastructure.

---

## 6. Required IAM Permissions

### On Google Cloud Platform
- When deploying to Cloud Run, attach the service account `nowhere-sub-verifier@YOUR_PROJECT_ID.iam.gserviceaccount.com`.
- **No JSON keys need to be downloaded or stored.** Cloud Run automatically fetches short-lived Google OAuth2 tokens via instance metadata using Application Default Credentials (ADC).
- The Cloud Run service requires no extra Cloud IAM roles unless you restrict invocations.

### On Google Play Console
- **View financial data**: Grants read-only access to `Purchases.Subscriptionsv2.Get`.
- **Manage orders and subscriptions**: Grants write access to `Purchases.Subscriptions.Acknowledge`.

---

## 7. Cloud Run Deployment

Deploy directly from source using Google Cloud Build and Cloud Run:

```bash
PROJECT_ID="YOUR_PROJECT_ID"
REGION="us-central1"
SA_EMAIL="nowhere-sub-verifier@${PROJECT_ID}.iam.gserviceaccount.com"

gcloud run deploy nowhere-subscription-backend \
  --source . \
  --project "${PROJECT_ID}" \
  --region "${REGION}" \
  --service-account "${SA_EMAIL}" \
  --set-env-vars ANDROID_PACKAGE_NAME=com.nowhere.gps.locationchanger \
  --set-env-vars GOOGLE_PLAY_PRODUCT_IDS="nowhere_premium,nowhere_premium_yearly" \
  --set-env-vars APP_ENV=production \
  --set-env-vars RATE_LIMIT_RPS=15.0 \
  --set-env-vars RATE_LIMIT_BURST=30 \
  --allow-unauthenticated \
  --min-instances 0 \
  --max-instances 10 \
  --cpu 1 \
  --memory 256Mi \
  --timeout 15s
```

After deployment completes, Cloud Run will output your service URL (e.g. `https://nowhere-subscription-backend-xyz.a.run.app`).

---

## 8. Testing with Play License Testers

1. In **Google Play Console**, go to **Setup** -> **License testing**.
2. Add your Google test account emails under **License testers**.
3. Under **License test response**, choose `RESPOND_NORMALLY`.
4. Install the test track build on your test Android device logged into that tester account.
5. In Google Play Billing sandbox:
   - Monthly subscriptions renew every 5 minutes.
   - Subscriptions auto-renew up to 6 times before expiring.
   - Payment failures (grace period and on-hold) can be tested by selecting test payment methods (e.g., "Test card, always declines").
6. The backend automatically detects `testPurchase` flags inside `SubscriptionPurchaseV2` without altering entitlement rules.

---

## 9. API Request & Response Specification

### `GET /health`
Liveness/readiness probe for Cloud Run.

#### Request
```bash
curl -i https://your-service-url.a.run.app/health
```

#### Response (200 OK)
```json
{
  "status": "ok"
}
```

---

### `POST /api/v1/google-play/verify`
Server-side subscription verification and entitlement calculation.

#### Request Headers
```http
Content-Type: application/json
```

#### Request Body
```json
{
  "packageName": "com.nowhere.gps.locationchanger",
  "productId": "nowhere_premium",
  "purchaseToken": "inapp:com.nowhere.gps.locationchanger:mock-token-abc"
}
```

#### Response Cases

##### 1. Active Pro Subscription (`200 OK`)
```json
{
  "verified": true,
  "entitlement": "pro",
  "active": true,
  "productId": "nowhere_premium",
  "expiresAt": "2026-11-03T08:00:00Z"
}
```

##### 2. Inactive / Expired Subscription (`200 OK`)
Returned when the purchase was verified with Google Play, but is expired, canceled past expiry, on hold, paused, or pending.
```json
{
  "verified": true,
  "entitlement": "none",
  "active": false,
  "productId": "nowhere_premium",
  "expiresAt": "2026-10-01T12:00:00Z"
}
```

##### 3. Invalid Purchase / Token Not Found / Mismatch (`200 OK` or `400 Bad Request`)
Returned when Google returns 404 Not Found, 400 Invalid Token, or the purchase does not belong to the requested product/package.
```json
{
  "verified": false,
  "entitlement": "none",
  "active": false
}
```

##### 4. Rate Limit Exceeded (`429 Too Many Requests`)
```json
{
  "verified": false,
  "entitlement": "none",
  "active": false
}
```

##### 5. Upstream Google API Error (`502 Bad Gateway` / `504 Gateway Timeout`)
```json
{
  "verified": false,
  "entitlement": "none",
  "active": false
}
```

---

## 10. Kotlin Client Integration Example

Below is the complete, production-ready Kotlin client integration for the Android app.

### 1. Data Models (`SubscriptionVerificationModels.kt`)

```kotlin
package com.fakegps.mocklocation.billing

import com.google.gson.annotations.SerializedName

data class GooglePlayVerifyRequest(
    @SerializedName("packageName")
    val packageName: String,
    @SerializedName("productId")
    val productId: String,
    @SerializedName("purchaseToken")
    val purchaseToken: String
)

data class SubscriptionEntitlementResponse(
    @SerializedName("verified")
    val verified: Boolean,
    @SerializedName("entitlement")
    val entitlement: String,
    @SerializedName("active")
    val active: Boolean,
    @SerializedName("productId")
    val productId: String? = null,
    @SerializedName("expiresAt")
    val expiresAt: String? = null
) {
    val isProEntitled: Boolean
        get() = verified && active && entitlement == "pro"
}
```

### 2. Verification Client (`SubscriptionVerificationClient.kt`)

```kotlin
package com.fakegps.mocklocation.billing

import android.content.Context
import android.util.Log
import com.android.billingclient.api.Purchase
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class SubscriptionVerificationClient(
    private val backendBaseUrl: String = "https://nowhere-subscription-backend-xyz.a.run.app"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun verifySubscription(
        packageName: String,
        productId: String,
        purchaseToken: String
    ): SubscriptionEntitlementResponse = withContext(Dispatchers.IO) {
        val requestPayload = GooglePlayVerifyRequest(
            packageName = packageName,
            productId = productId,
            purchaseToken = purchaseToken
        )

        val requestBody = gson.toJson(requestPayload).toRequestBody(jsonMediaType)
        val httpRequest = Request.Builder()
            .url("$backendBaseUrl/api/v1/google-play/verify")
            .post(requestBody)
            .addHeader("Accept", "application/json")
            .build()

        try {
            client.newCall(httpRequest).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    gson.fromJson(bodyString, SubscriptionEntitlementResponse::class.java)
                } else {
                    Log.w("SubVerify", "Verification failed with HTTP ${response.code}")
                    SubscriptionEntitlementResponse(
                        verified = false,
                        entitlement = "none",
                        active = false
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("SubVerify", "Network error during subscription verification", e)
            SubscriptionEntitlementResponse(
                verified = false,
                entitlement = "none",
                active = false
            )
        }
    }
}
```

### 3. Usage in Billing Repository / Manager

```kotlin
// In your billing callback when a purchase is received:
suspend fun onPurchaseConfirmed(context: Context, purchase: Purchase) {
    val productId = purchase.products.firstOrNull() ?: return
    val token = purchase.purchaseToken

    val verificationClient = SubscriptionVerificationClient()
    val entitlement = verificationClient.verifySubscription(
        packageName = context.packageName,
        productId = productId,
        purchaseToken = token
    )

    if (entitlement.isProEntitled) {
        // Unlock Pro features in local state / Preferences
        NowhereBillingManager.setIsPro(true)
    } else {
        // Revoke Pro features
        NowhereBillingManager.setIsPro(false)
    }
}
```

---

## 11. Security Considerations

1. **No Sensitive Data Client-Side**:
   - Zero Google Cloud service account keys, private keys, or API secrets exist in the Android APK.
   - The licensing Base64 RSA public key has been completely removed from the client binary.
2. **Never Log Sensitive Data**:
   - Purchase tokens are strictly omitted from all application logs.
   - Upstream Google API credentials and stack traces are never exposed in logs or API responses.
3. **Fail-Closed Entitlement Logic**:
   - If Google Play Developer API returns an error, timeout, 404, or unexpected status, the backend defaults to `{"verified": false, "entitlement": "none", "active": false}`.
   - Pro entitlement is strictly granted when Google returns `SUBSCRIPTION_STATE_ACTIVE`, `SUBSCRIPTION_STATE_IN_GRACE_PERIOD`, or `SUBSCRIPTION_STATE_CANCELED` with an unexpired `ExpiryTime`.
4. **Denial-of-Service & Resource Exhaustion Protection**:
   - `http.MaxBytesReader` strictly limits incoming request bodies to 16 KB.
   - `json.NewDecoder.DisallowUnknownFields()` enforces strict schema adherence.
   - In-memory token-bucket rate limiter per client IP mitigates brute-force verification floods.
   - Note: The rate limiter is per-instance because Cloud Run scales horizontally across multiple container instances.
5. **Security Headers**:
   - `X-Content-Type-Options: nosniff`
   - `X-Frame-Options: DENY`
   - `Strict-Transport-Security: max-age=31536000; includeSubDomains`
   - `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`
   - `Cache-Control: no-store, no-cache, must-revalidate`
   - CORS is disabled by default.
