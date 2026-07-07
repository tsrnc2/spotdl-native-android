#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
JNI = ROOT / "app" / "src" / "main" / "jniLibs-pruned" / "arm64-v8a"
PY_SRC = ROOT / "app" / "src" / "main" / "python"
FFMPEG = JNI / "libffmpeg.so"
APKSIGNER = Path("/root/android-sdk/build-tools/36.0.0/apksigner")
HOST_PYTHON = Path("/root/.local/share/pipx/venvs/spotdl/bin/python")
NEEDED_RE = re.compile(r"Shared library: \[(.*?)\]")
SYSTEM_LIBS = {
    "libc.so",
    "libm.so",
    "libdl.so",
    "liblog.so",
    "libjnigraphics.so",
    "libEGL.so",
    "libGLESv2.so",
    "libOpenSLES.so",
}


def run(cmd: list[str], *, env: dict[str, str] | None = None) -> str:
    proc = subprocess.run(cmd, cwd=ROOT, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
    if proc.returncode != 0:
        raise RuntimeError(f"command failed ({proc.returncode}): {' '.join(cmd)}\n{proc.stdout}")
    return proc.stdout


def read_needed(path: Path) -> list[str]:
    output = run(["readelf", "-d", str(path)])
    return NEEDED_RE.findall(output)


def verify_native_closure() -> None:
    seen: list[str] = []
    queue = ["libffmpeg.so"]
    missing: list[str] = []
    while queue:
        name = queue.pop(0)
        if name in seen:
            continue
        seen.append(name)
        path = JNI / name
        if not path.exists():
            if name not in SYSTEM_LIBS:
                missing.append(name)
            continue
        for dep in read_needed(path):
            if dep not in seen and dep not in queue and dep not in SYSTEM_LIBS:
                queue.append(dep)
    if missing:
        raise RuntimeError("missing native dependencies: " + ", ".join(sorted(set(missing))))
    print(f"native closure ok: {len(seen)} libraries")


def verify_apk() -> None:
    if not APK.is_file():
        raise RuntimeError(f"APK not found: {APK}")
    run([str(APKSIGNER), "verify", "--verbose", str(APK)])
    with zipfile.ZipFile(APK) as archive:
        names = archive.namelist()
    required = {
        "lib/arm64-v8a/libffmpeg.so",
        "lib/arm64-v8a/libandroid.so",
        "lib/arm64-v8a/libavcodec.so",
    }
    absent = sorted(required - set(names))
    if absent:
        raise RuntimeError("APK missing required native entries: " + ", ".join(absent))
    versioned = [name for name in names if name.startswith("lib/arm64-v8a/") and ".so." in name]
    if versioned:
        raise RuntimeError("APK contains versioned native library names: " + ", ".join(versioned[:8]))
    size_mib = APK.stat().st_size / 1024 / 1024
    print(f"apk ok: {size_mib:.1f} MiB, signed, native names packageable")


def verify_ffmpeg() -> None:
    env = os.environ.copy()
    env["LD_LIBRARY_PATH"] = str(JNI) + ((":" + env["LD_LIBRARY_PATH"]) if env.get("LD_LIBRARY_PATH") else "")
    output = run([str(FFMPEG), "-version"], env=env)
    first = output.splitlines()[0] if output else "ffmpeg version unknown"
    print("ffmpeg ok: " + first)


def verify_python_health() -> None:
    code = """
import json
import spotdl_runner
result = json.loads(spotdl_runner.health('/tmp/spotdl-native-release-health', r'%s'))
assert result['spotdl_available'], result
assert result['ffmpeg_available'], result
print(json.dumps(result, sort_keys=True))
""" % str(FFMPEG)
    env = os.environ.copy()
    env["PYTHONPATH"] = str(PY_SRC)
    output = run([str(HOST_PYTHON), "-c", code], env=env)
    result = json.loads(output.strip().splitlines()[-1])
    print(f"spotdl health ok: spotDL {result['spotdl_version']}, Python {result['python']}")


def main() -> int:
    verify_apk()
    verify_native_closure()
    verify_ffmpeg()
    verify_python_health()
    print("release smoke test passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
