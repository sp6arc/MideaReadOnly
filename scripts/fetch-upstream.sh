#!/usr/bin/env bash
set -euo pipefail
UPSTREAM_REPOSITORY="https://github.com/wuwentao/midea-lan.git"
UPSTREAM_COMMIT="061df03f27a409bddaa5f5f50e371de5c00098b5"
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
git clone --quiet "$UPSTREAM_REPOSITORY" "$work_dir/midea-lan"
git -C "$work_dir/midea-lan" checkout --quiet --detach "$UPSTREAM_COMMIT"
actual="$(git -C "$work_dir/midea-lan" rev-parse HEAD)"
test "$actual" = "$UPSTREAM_COMMIT"
test -f "$work_dir/midea-lan/midealan/device.py"
test -f "$work_dir/midea-lan/midealan/security.py"
test -f "$work_dir/midea-lan/midealan/devices/c3/message.py"
mkdir -p app/src/main/python
rm -rf app/src/main/python/midealan
cp -a "$work_dir/midea-lan/midealan" app/src/main/python/midealan
test "$(find "$work_dir/midea-lan/midealan" -type f | wc -l)" -eq "$(find app/src/main/python/midealan -type f | wc -l)"
diff -r "$work_dir/midea-lan/midealan" app/src/main/python/midealan
printf '%s\n' "$UPSTREAM_COMMIT" > app/src/main/python/UPSTREAM_COMMIT.txt
echo "Exact original library packaged, commit $UPSTREAM_COMMIT"
