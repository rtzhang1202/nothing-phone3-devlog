#!/usr/bin/env python3
"""CLI build pipeline for Fullscreen AOD LSPosed module (aapt2 -> javac -> d8 -> zipalign -> apksigner)."""
import os, sys, shutil, subprocess, zipfile, hashlib

# Portable layout: everything is derived from this script's own location.
MOD_DIR = os.path.dirname(os.path.abspath(__file__))
APP_MAIN = f"{MOD_DIR}/app/src/main"
OUT_DIR = f"{MOD_DIR}/build_out"
RELEASE_DIR = f"{MOD_DIR}/release"

# Signing keystore: create your own (see README) or set FULLAOD_KEYSTORE.
KS = os.environ.get("FULLAOD_KEYSTORE", f"{MOD_DIR}/keystore/release.jks")
KS_ALIAS = os.environ.get("FULLAOD_KS_ALIAS", "nothingfullaod")
KS_PASS = os.environ.get("FULLAOD_KS_PASS", "nothingfullaod")

SDK = "C:/Users/25451/AppData/Local/Android/Sdk"
BT = f"{SDK}/build-tools/37.0.0"
ANDROID_JAR = f"{SDK}/platforms/android-37.0/android.jar"
XPOSED_JAR = f"{MOD_DIR}/vendor/XposedBridgeApi-82.jar"

AAPT2 = f"{BT}/aapt2.exe"
D8_JAR = f"{BT}/lib/d8.jar"
ZIPALIGN = f"{BT}/zipalign.exe"
APKSIGNER_JAR = f"{BT}/lib/apksigner.jar"
DEXDUMP = f"{BT}/dexdump.exe"

JAVA = shutil.which("java") or "C:/Program Files/Android/Android Studio/jbr/bin/java.exe"
JAVAC = shutil.which("javac") or "C:/Program Files/Android/Android Studio/jbr/bin/javac.exe"

def run(cmd):
    print("+", " ".join(cmd))
    subprocess.run(cmd, check=True)

def main():
    if os.path.exists(OUT_DIR):
        shutil.rmtree(OUT_DIR)
    os.makedirs(OUT_DIR, exist_ok=True)
    os.makedirs(RELEASE_DIR, exist_ok=True)

    gen_dir = f"{OUT_DIR}/gen"
    cls_dir = f"{OUT_DIR}/classes"
    dex_dir = f"{OUT_DIR}/dex"
    os.makedirs(gen_dir, exist_ok=True)
    os.makedirs(cls_dir, exist_ok=True)
    os.makedirs(dex_dir, exist_ok=True)

    res_zip = f"{OUT_DIR}/res.zip"
    res_apk = f"{OUT_DIR}/res.apk"
    unaligned_apk = f"{OUT_DIR}/unaligned.apk"
    aligned_apk = f"{OUT_DIR}/aligned.apk"
    signed_apk = f"{RELEASE_DIR}/FullscreenAOD.apk"

    # 1. aapt2 compile
    run([AAPT2, "compile", "--dir", f"{APP_MAIN}/res", "-o", res_zip])

    # 2. aapt2 link
    run([
        AAPT2, "link",
        "-o", res_apk,
        "--manifest", f"{APP_MAIN}/AndroidManifest.xml",
        "-I", ANDROID_JAR,
        "-A", f"{APP_MAIN}/assets",
        "--java", gen_dir,
        "--min-sdk-version", "35",
        "--target-sdk-version", "37",
        "--version-code", "2",
        "--version-name", "1.1",
        res_zip
    ])

    # 3. Collect all Java sources
    java_files = []
    for root_dir in [f"{APP_MAIN}/java", gen_dir]:
        for dp, _, fns in os.walk(root_dir):
            for fn in fns:
                if fn.endswith(".java"):
                    java_files.append(os.path.join(dp, fn))
    assert java_files, "No Java source files found!"

    # 4. javac (XposedBridgeApi-82.jar is compileOnly!)
    cp = f"{ANDROID_JAR};{XPOSED_JAR}"
    run([
        JAVAC,
        "--release", "17",
        "-encoding", "UTF-8",
        "-classpath", cp,
        "-d", cls_dir,
    ] + java_files)

    # 5. Collect compiled .class files ONLY from cls_dir (NEVER include XposedBridgeApi!)
    class_files = []
    for dp, _, fns in os.walk(cls_dir):
        for fn in fns:
            if fn.endswith(".class"):
                class_files.append(os.path.join(dp, fn))
    assert class_files, "No .class files produced!"

    # 6. d8 -> classes.dex
    run([
        JAVA, "-cp", D8_JAR, "com.android.tools.r8.D8",
        "--min-api", "35",
        "--lib", ANDROID_JAR,
        "--classpath", XPOSED_JAR,
        "--output", dex_dir,
    ] + class_files)

    dex_path = f"{dex_dir}/classes.dex"
    assert os.path.exists(dex_path), "classes.dex not produced!"

    # 7. Assert ZERO Xposed stub classes in classes.dex!
    dump = subprocess.check_output([DEXDUMP, "-f", dex_path], text=True, errors="replace")
    dump_all = subprocess.check_output([DEXDUMP, dex_path], text=True, errors="replace")
    xposed_defs = [
        line.strip() for line in dump_all.splitlines()
        if "Class descriptor" in line and "Lde/robv/android/xposed/" in line
    ]
    if xposed_defs:
        raise RuntimeError(f"FATAL: Xposed stub classes leaked into classes.dex: {xposed_defs}")
    print("OK: Verified 0 Lde/robv/android/xposed/ class definitions in classes.dex")

    # 8. Package classes.dex into APK (preserving compression of res.apk entries)
    shutil.copyfile(res_apk, unaligned_apk)
    with zipfile.ZipFile(unaligned_apk, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(dex_path, "classes.dex")

    # 9. zipalign
    run([ZIPALIGN, "-f", "4", unaligned_apk, aligned_apk])

    # 10. apksigner sign & verify
    run([
        JAVA, "-jar", APKSIGNER_JAR, "sign",
        "--ks", KS,
        "--ks-key-alias", KS_ALIAS,
        "--ks-pass", f"pass:{KS_PASS}",
        "--key-pass", f"pass:{KS_PASS}",
        "--out", signed_apk,
        aligned_apk
    ])
    run([JAVA, "-jar", APKSIGNER_JAR, "verify", "--verbose", signed_apk])

    sha = hashlib.sha256(open(signed_apk, "rb").read()).hexdigest()
    open(signed_apk + ".sha256", "w").write(f"{sha}  FullscreenAOD.apk\n")
    print(f"BUILT: {signed_apk} ({os.path.getsize(signed_apk)} bytes) sha256={sha}")

if __name__ == "__main__":
    main()
