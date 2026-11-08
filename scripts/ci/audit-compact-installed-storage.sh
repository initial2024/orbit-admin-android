#!/usr/bin/env bash
set -euo pipefail

# ONLY Android 35 CI emulator. Never target a user's phone or production data.
PKG="de.kango99w.orbitadmin"
mkdir -p compact-storage-audit
for flavor in debug compact; do
  apk="app/build/outputs/apk/$flavor/app-$flavor.apk"
  test -s "$apk"

  adb uninstall "$PKG" >/dev/null 2>&1 || true
  adb install "$apk" >/dev/null
  adb shell am start -n "$PKG/.MainActivity" >/dev/null
  sleep 7

  adb shell dumpsys meminfo "$PKG" > "compact-storage-audit/$flavor-meminfo.txt"
  adb shell pm path "$PKG" > "compact-storage-audit/$flavor-path.txt"
  apk_bytes="$(wc -c < "$apk" | tr -d ' ')"
  path_bytes=0
  while IFS= read -r path; do
    path="${path#package:}"
    path="$(printf '%s' "$path" | tr -d '\r')"
    [ -n "$path" ] || continue
    stat_bytes="$(adb shell stat -c '%s' "$path" 2>/dev/null | tr -dc '0-9' || true)"
    if [[ "$stat_bytes" =~ ^[0-9]+$ ]]; then
      path_bytes=$((path_bytes + stat_bytes))
    fi
  done < "compact-storage-audit/$flavor-path.txt"

  # Debug-variant run-as is limited to this disposable emulator package.
  data_kib="$(adb shell run-as "$PKG" du -sk . 2>/dev/null | head -1 | tr -dc '0-9' || true)"
  if ! [[ "$data_kib" =~ ^[0-9]+$ ]]; then data_kib=0; fi
  {
    echo "variant=$flavor"
    echo "apk_bytes=$apk_bytes"
    echo "installed_code_path_bytes=$path_bytes"
    echo "fresh_private_data_kib=$data_kib"
    echo "conditions=Android_35_emulator_first_launch_no_login"
  } > "compact-storage-audit/$flavor-stats.txt"
  adb shell am force-stop "$PKG"
done

python3 - <<'PY'
from pathlib import Path
import re
folder=Path('compact-storage-audit')
lines=['## Android 35 cold-start size + memory snapshot','',
       'These measurements are from a clean, isolated emulator, not a real-phone user session.','',
       '| Metric | Debug | Compact |',
       '|---|---:|---:|']
stats={}
for variant in ('debug','compact'):
    stats[variant]=dict(line.split('=',1) for line in
        (folder/f'{variant}-stats.txt').read_text().splitlines() if '=' in line)
for key,label,divider in [('apk_bytes','Downloaded APK size (MB)',1_000_000),
                           ('installed_code_path_bytes','Installed code paths (MB)',1_000_000),
                           ('fresh_private_data_kib','Fresh app data (KiB)',1)]:
    nums=[]
    for v in ('debug','compact'):
        raw=int(stats[v].get(key,0))
        nums.append(f'{raw/divider:.2f}' if raw else 'unavailable')
    lines.append(f'| {label} | {nums[0]} | {nums[1]} |')
def total_pss(text):
    for pattern in [r'^\s*TOTAL\s+([0-9,]+)\s',r'TOTAL PSS:\s*([0-9,]+)']:
        m=re.search(pattern,text,re.MULTILINE)
        if m: return int(m.group(1).replace(',',''))
    return None
pss=[]
for v in ('debug','compact'):
    kb=total_pss((folder/f'{v}-meminfo.txt').read_text())
    pss.append('unavailable' if kb is None else f'{kb/1024:.1f}')
lines.append(f'| Cold-start total PSS (MiB) | {pss[0]} | {pss[1]} |')
lines.extend(['','PSS fluctuates with garbage collection, SDK scheduling and emulator state.',
              'Installing/uninstalling on CI never touches the user phone or GitHub release.'])
summary='\n'.join(lines)+'\n'
print(summary)
with open(__import__('os').environ['GITHUB_STEP_SUMMARY'],'a') as f:f.write(summary)
(folder/'summary.md').write_text(summary)
PY
