#!/usr/bin/env python3
"""Download pinned scanner binaries into .tools after SHA-256 verification."""
import hashlib
import io
import json
import platform
import tarfile
import urllib.request
from pathlib import Path

ASSETS = json.loads(r''' {"gitleaks": {"linux/x86_64": {"url": "https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/gitleaks_8.30.1_linux_x64.tar.gz", "sha256": "551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb"}, "linux/aarch64": {"url": "https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/gitleaks_8.30.1_linux_arm64.tar.gz", "sha256": "e4a487ee7ccd7d3a7f7ec08657610aa3606637dab924210b3aee62570fb4b080"}, "darwin/arm64": {"url": "https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/gitleaks_8.30.1_darwin_arm64.tar.gz", "sha256": "b40ab0ae55c505963e365f271a8d3846efbc170aa17f2607f13df610a9aeb6a5"}}, "osv-scanner": {"linux/x86_64": {"url": "https://github.com/google/osv-scanner/releases/download/v2.7.0/osv-scanner_linux_amd64", "sha256": "160c6253e2922e9e0a6a9771c5c4c40a4b3c6090b34a3cdd5e80c96cc2e4218b"}, "linux/aarch64": {"url": "https://github.com/google/osv-scanner/releases/download/v2.7.0/osv-scanner_linux_arm64", "sha256": "4061ef4c021ed3c47f203b818f337e351336cc5cd67c0274fc36bd1dfc728dd7"}, "darwin/arm64": {"url": "https://github.com/google/osv-scanner/releases/download/v2.7.0/osv-scanner_darwin_arm64", "sha256": "d4926ef8da134332543be276dff157e4c170f99ad91fc48e89448a8816bdf70b"}}} ''')

def main():
    root = Path(__file__).resolve().parent.parent
    destination = root / ".tools"
    destination.mkdir(exist_ok=True)
    architecture = platform.machine().lower()
    if architecture == "arm64" and platform.system().lower() == "linux":
        architecture = "aarch64"
    system = platform.system().lower() + "/" + architecture
    for tool, platforms in ASSETS.items():
        if system not in platforms:
            raise SystemExit("Unsupported scanner platform: " + system)
        asset = platforms[system]
        with urllib.request.urlopen(asset["url"], timeout=60) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != asset["sha256"]:
            raise SystemExit("Checksum verification failed for " + tool)
        if tool == "gitleaks":
            with tarfile.open(fileobj=io.BytesIO(data)) as archive:
                data = archive.extractfile("gitleaks").read()
        binary = destination / tool
        binary.write_bytes(data)
        binary.chmod(0o755)
        print("Installed verified " + tool)

if __name__ == "__main__":
    main()
