#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$project_dir/app/keys"
if [[ -f "$project_dir/app/keys/neutralproxy-debug.keystore" ]]; then
  echo "project-local debug keystore already exists"
  exit 0
fi
keytool -genkeypair -noprompt \
  -keystore "$project_dir/app/keys/neutralproxy-debug.keystore" \
  -storepass neutralproxy-debug-only \
  -alias neutralproxy-debug \
  -keypass neutralproxy-debug-only \
  -keyalg RSA -keysize 2048 -validity 3650 \
  -dname "CN=NeutralProxy Local Debug, OU=Local, O=NeutralProxy, L=Local, ST=Local, C=TW"
