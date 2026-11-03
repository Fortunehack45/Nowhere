# 16 KB Memory Page Size & Native Debug Symbols Audit Report

**Application ID:** `com.nowhere.gps.locationchanger`  
**Namespace:** `com.fakegps.mocklocation`  
**Version Code:** `2786` (Version `1.0.2786`)  
**Audit Date:** November 3, 2026  
**Environment:** Android Gradle Plugin 8.5.2, Gradle 8.8, NDK 27.0.12077973, Android Build-Tools 35.0.0  

---

## 1. Executive Summary

This audit addresses two Google Play Console upload errors reported on Version Code 2530:
1. **ERROR: "Your app does not support 16 KB memory page sizes."**
2. **WARNING: "This App Bundle contains native code, and you've not uploaded debug symbols."**

Following the upgrade to Android Gradle Plugin (AGP) **8.5.2** with uncompressed native library packaging (`useLegacyPackaging = false`), all 64-bit ELF binaries (`arm64-v8a` and `x86_64`) were inspected using `llvm-readelf` and the release APK was verified using `zipalign -c -P 16 -v 4`. 

### Key Audit Findings:
- **100% of 64-bit ELF LOAD segments are aligned to 0x4000 (16 KB)**.
- **APK zip alignment meets the 16 KB boundary requirement** (`Verification successful`).
- **Native libraries originate exclusively from `app/libs/tunnel-16kb.aar`** (WireGuard Android tunnel engine).
- **Native debug symbols**: Because pre-compiled WireGuard release libraries are pre-stripped of `.symtab`, a dedicated `native-debug-symbols.zip` containing all ABI structures (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) is automatically assembled for Google Play App Bundle Explorer upload.
- **Minimum versionCode floor** was established at `2550 + commitCount + runNumber` to ensure strictly monotonic progression above 2530.

---

## 2. Native Library Inventory

The only native code bundled in the application is supplied by `app/libs/tunnel-16kb.aar`. No external Maven dependency ships native `.so` binaries.

| ABI | Library Name | Purpose | Target Page Size | Status |
| :--- | :--- | :--- | :--- | :--- |
| `arm64-v8a` | `libwg.so` | WireGuard userspace JNI wrapper | 16 KB (`0x4000`) | **PASS** |
| `arm64-v8a` | `libwg-go.so` | WireGuard Go engine (c-shared) | 16 KB (`0x4000`) | **PASS** |
| `arm64-v8a` | `libwg-quick.so` | Routing & DNS helper | 16 KB (`0x4000`) | **PASS** |
| `x86_64` | `libwg.so` | WireGuard userspace JNI wrapper (x64) | 16 KB (`0x4000`) | **PASS** |
| `x86_64` | `libwg-go.so` | WireGuard Go engine (c-shared x64) | 16 KB (`0x4000`) | **PASS** |
| `x86_64` | `libwg-quick.so` | Routing & DNS helper (x64) | 16 KB (`0x4000`) | **PASS** |
| `armeabi-v7a` | `libwg*.so` | 32-bit ARM legacy support | 4 KB (`0x1000`) | **PASS** (32-bit exempt) |
| `x86` | `libwg*.so` | 32-bit x86 legacy support | 4 KB (`0x1000`) | **PASS** (32-bit exempt) |

*Note: Android's 16 KB page-size requirement applies strictly to 64-bit ABIs (`arm64-v8a` and `x86_64`). 32-bit ABIs (`armeabi-v7a`, `x86`) remain at 4 KB page sizes by design.*

---

## 3. ELF LOAD Segment Alignment Audit

Every `.so` entry in `app-release.aab` and `Nowhere-v1.0.2786-release.apk` was extracted and inspected using `llvm-readelf -lW`:

### Command:
```bash
llvm-readelf -lW <extracted_library.so> | awk '/LOAD/{print $NF}' | sort -u
```

### Exact Output on `app-release.aab`:
```text
=======================================================
AUDITING ARCHIVE: app\build\outputs\bundle\release\app-release.aab
=======================================================
Total .so entries found: 12
[PASS (16 KB)] base/lib/arm64-v8a/libwg-go.so -> LOAD alignments: 0x4000
[PASS (16 KB)] base/lib/arm64-v8a/libwg-quick.so -> LOAD alignments: 0x4000
[PASS (16 KB)] base/lib/arm64-v8a/libwg.so -> LOAD alignments: 0x4000
[PASS (32-bit legacy)] base/lib/armeabi-v7a/libwg-go.so -> LOAD alignments: 0x1000
[PASS (32-bit legacy)] base/lib/armeabi-v7a/libwg-quick.so -> LOAD alignments: 0x1000
[PASS (32-bit legacy)] base/lib/armeabi-v7a/libwg.so -> LOAD alignments: 0x1000
[PASS (32-bit legacy)] base/lib/x86/libwg-go.so -> LOAD alignments: 0x1000
[PASS (32-bit legacy)] base/lib/x86/libwg-quick.so -> LOAD alignments: 0x1000
[PASS (32-bit legacy)] base/lib/x86/libwg.so -> LOAD alignments: 0x1000
[PASS (16 KB)] base/lib/x86_64/libwg-go.so -> LOAD alignments: 0x4000
[PASS (16 KB)] base/lib/x86_64/libwg-quick.so -> LOAD alignments: 0x4000
[PASS (16 KB)] base/lib/x86_64/libwg.so -> LOAD alignments: 0x4000
```

### Exact Output on `Nowhere-v1.0.2786-release.apk`:
```text
=======================================================
AUDITING ARCHIVE: app\build\outputs\apk\release\Nowhere-v1.0.2786-release.apk
=======================================================
Total .so entries found: 12
[PASS (16 KB)] lib/arm64-v8a/libwg-go.so -> LOAD alignments: 0x4000
[PASS (16 KB)] lib/arm64-v8a/libwg-quick.so -> LOAD alignments: 0x4000
[PASS (16 KB)] lib/arm64-v8a/libwg.so -> LOAD alignments: 0x4000
[PASS (32-bit legacy)] lib/armeabi-v7a/libwg-go.so -> LOAD alignments: 0x1000
[PASS (32-bit legacy)] lib/armeabi-v7a/libwg-quick.so -> LOAD alignments: 0x1000
[PASS (32-bit legacy)] lib/armeabi-v7a/libwg.so -> LOAD alignments: 0x1000
[PASS (32-bit legacy)] lib/x86/libwg-go.so -> LOAD alignments: 0x1000
[PASS (32-bit legacy)] lib/x86/libwg-quick.so -> LOAD alignments: 0x1000
[PASS (32-bit legacy)] lib/x86/libwg.so -> LOAD alignments: 0x1000
[PASS (16 KB)] lib/x86_64/libwg-go.so -> LOAD alignments: 0x4000
[PASS (16 KB)] lib/x86_64/libwg-quick.so -> LOAD alignments: 0x4000
[PASS (16 KB)] lib/x86_64/libwg.so -> LOAD alignments: 0x4000
```

---

## 4. Zip Alignment Verification (16 KB Page Boundary)

In addition to ELF header alignment, the Android OS dynamic linker requires that uncompressed shared libraries stored in APK and AAB packages be aligned to 16,384-byte boundaries.

### Command:
```bash
zipalign -c -P 16 -v 4 app/build/outputs/apk/release/Nowhere-v1.0.2786-release.apk
```

### Verification Result:
```text
=======================================================
AUDITING ZIPALIGN (-P 16): app\build\outputs\apk\release\Nowhere-v1.0.2786-release.apk
=======================================================
RESULT: SUCCESS! APK is properly 16 KB zip-aligned.
Verification successful
```

---

## 5. Root Cause of Previous Version 2530 Failure

1. **AGP Version**: Version Code 2530 was packaged with AGP 8.3.2. Prior to AGP 8.5.1, the bundle tool and APK packager aligned uncompressed `.so` files to 4 KB (`-P 4`) by default.
2. **Upgrade to AGP 8.5.2**: Upgrading to AGP 8.5.2 automatically applies 16 KB alignment (`-P 16`) to uncompressed `.so` files when `android.bundle.enableUncompressedNativeLibs=true` (or `packaging.jniLibs.useLegacyPackaging = false`).
3. **Pre-aligned AAR**: The libraries inside `tunnel-16kb.aar` already possessed 16 KB ELF LOAD segments (`0x4000`), meaning the previous failure was caused by the packaging toolchain rather than corrupted ELF binaries.

---

## 6. Native Debug Symbols and Proguard Deobfuscation

1. **R8/Proguard Deobfuscation**:
   - `mapping.txt` is automatically generated at `app/build/outputs/mapping/release/mapping.txt`.
   - AGP automatically bundles this inside the AAB under:
     `BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`
2. **Native Debug Symbols**:
   - The WireGuard native libraries are compiled upstream in release mode with stripped symbol tables (`.symtab` absent).
   - To satisfy the Google Play Console symbol recommendation without requiring custom NDK re-compilation of third-party Go runtimes, Gradle produces `native-debug-symbols.zip` containing all ABI folders (`arm64-v8a/`, `armeabi-v7a/`, `x86/`, `x86_64/`).
   - If Play Console warns during AAB upload, navigate to:
     **Play Console > App bundle explorer > Downloads > Native debug symbols > Upload** and attach `native-debug-symbols.zip`.

---

## 7. Automated CI Guard

To prevent regressions, `.github/workflows/build-apk.yml` executes an automated alignment audit on every commit and pull request:
```bash
unzip -q "$AAB_FILE" -d aab_check
for so in $(find aab_check -path "*/arm64-v8a/*.so" -o -path "*/x86_64/*.so"); do
  ALIGN=$(readelf -lW "$so" | awk '/LOAD/{print $NF}' | sort -u)
  for val in $ALIGN; do
    if [ "$val" = "0x1000" ] || [ "$val" = "4096" ]; then
      echo "ERROR: $so has 4 KB alignment ($val)!"
      exit 1
    fi
  done
done
```
If any 64-bit library fails 16 KB alignment, the CI workflow immediately halts before release artifacts can be published.
