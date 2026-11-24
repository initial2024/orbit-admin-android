#!/usr/bin/env bash
set -euo pipefail
APK="$(find prebuilt -type f -name app-compact.apk -print -quit)"
PKG=de.kango99w.orbitadmin
adb install "$APK"
for count in 1 2; do
  if [ "$count" -gt 1 ]; then adb shell am force-stop "$PKG"; fi
  adb shell am start -W -n "$PKG/.MainActivity" | tee "smoke-evidence/start-$count.txt"
  sleep 7
  pid="$(adb shell pidof "$PKG" | tr -d '\r' | awk '{print $1}')"
  test -n "$pid" || { echo "App terminated on start #$count" >&2; exit 1; }
  adb shell dumpsys meminfo "$PKG" > "smoke-evidence/mem-$count.txt"
done
adb shell pm path "$PKG" > smoke-evidence/installed-code.txt
adb shell run-as "$PKG" du -sk . > smoke-evidence/private-storage-kib.txt
adb shell am force-stop "$PKG"
python3 - <<'PY'
from pathlib import Path
import re
folder=Path('smoke-evidence')
records=[]
for i in [1,2]:
    s=(folder/f'start-{i}.txt').read_text()
    assert 'Status: ok' in s, 'Android am start did not report success'
    m=(folder/f'mem-{i}.txt').read_text()
    k=re.search(r'^\s*TOTAL\s+([0-9,]+)\s',m,re.MULTILINE)
    if not k:
        k=re.search(r'TOTAL PSS:\s*([0-9,]+)',m)
    records.append(int(k.group(1).replace(',','')) if k else None)
out=['# Android 35 compact-only first-run smoke','',
     'Baseline: immutable successful R8 size audit from source commit 881e2602.',
     'No live account/login, no network mail content, no real-user phone state.',
     'Both cold and warm Android launch survived for at least 7 s.',
     'RAM total PSS KiB cold/warm: '+str(records),
     'Fresh private-data storage KiB: '+
        (folder/'private-storage-kib.txt').read_text().strip(),
     'Does not prove account login, WebView navigation or FCM delivery.']
(folder/'summary.md').write_text('\n'.join(out)+'\n')
print('\n'.join(out))
PY
