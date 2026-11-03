import os
import subprocess
import zipfile

NDK_READELF = r"C:\Users\USER PC\AppData\Local\Android\Sdk\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe"
ZIPALIGN = r"C:\Users\USER PC\AppData\Local\Android\Sdk\build-tools\35.0.0\zipalign.exe"

def audit_archive(archive_path):
    print(f"\n=======================================================")
    print(f"AUDITING ARCHIVE: {archive_path}")
    print(f"=======================================================")
    
    with zipfile.ZipFile(archive_path, 'r') as z:
        so_entries = [e for e in z.infolist() if e.filename.endswith('.so')]
        print(f"Total .so entries found: {len(so_entries)}")
        
        extracted_temp = "temp_audit_extract"
        os.makedirs(extracted_temp, exist_ok=True)
        
        for entry in so_entries:
            target_path = os.path.join(extracted_temp, os.path.basename(entry.filename))
            with open(target_path, 'wb') as f:
                f.write(z.read(entry))
            
            # Run llvm-readelf -lW
            res = subprocess.run([NDK_READELF, "-lW", target_path], capture_output=True, text=True)
            load_alignments = set()
            for line in res.stdout.splitlines():
                if "LOAD" in line:
                    parts = line.strip().split()
                    if parts:
                        load_alignments.add(parts[-1])
            
            sorted_aligns = sorted(list(load_alignments))
            is_64bit = "arm64" in entry.filename or "x86_64" in entry.filename
            
            status = "PASS (16 KB)" if any(a in ("0x4000", "0x10000") for a in sorted_aligns) else ("PASS (32-bit legacy)" if not is_64bit else "FAIL")
            print(f"[{status}] {entry.filename} -> LOAD alignments: {', '.join(sorted_aligns)}")
            
            if os.path.exists(target_path):
                os.remove(target_path)
        
        if os.path.exists(extracted_temp):
            os.rmdir(extracted_temp)

def audit_zipalign(apk_path):
    print(f"\n=======================================================")
    print(f"AUDITING ZIPALIGN (-P 16): {apk_path}")
    print(f"=======================================================")
    cmd = [ZIPALIGN, "-c", "-P", "16", "-v", "4", apk_path]
    res = subprocess.run(cmd, capture_output=True, text=True)
    # Check verification status
    if "Verification succesful" in res.stdout or "Verification successful" in res.stdout or res.returncode == 0:
        print("RESULT: SUCCESS! APK is properly 16 KB zip-aligned.")
    else:
        print("RESULT: FAILED!")
        print(res.stdout[-500:])

if __name__ == "__main__":
    aab = r"app\build\outputs\bundle\release\app-release.aab"
    apk = r"app\build\outputs\apk\release\Nowhere-v1.0.2786-release.apk"
    if os.path.exists(aab):
        audit_archive(aab)
    if os.path.exists(apk):
        audit_archive(apk)
        audit_zipalign(apk)
