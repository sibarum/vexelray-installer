#!/bin/sh
# The Linux installer end to end, on Linux: builds a sample application's installer with the CLI, installs it
# from file:// URLs into a throwaway HOME, updates it, refuses a tampered download, recovers from a killed
# install, uninstalls it, and runs a suite of two. Nothing outside the temporary folder is touched.
#
#   mvn -pl installer-core package     (once; the CLI jar is read from target-linux/ or target/)
#   sh tests/linux-smoke.sh
#   SH=dash sh tests/linux-smoke.sh    (run the generated scripts with another shell; busybox sh works too)
#
# Needs curl (file:// URLs), tar, sha256sum and Java. With desktop-file-validate or shellcheck on PATH it
# runs them on what it generated as well.
set -eu
repo=$(cd "$(dirname "$0")/.." && pwd)
cli=$(ls -t "$repo"/installer-core/target-linux/installer-core-*-cli.jar "$repo"/installer-core/target/installer-core-*-cli.jar 2>/dev/null | head -n 1 || true)
[ -n "$cli" ] || { echo "Build the CLI first: mvn -pl installer-core package"; exit 1; }
run=${SH:-sh}
work=$(mktemp -d "${TMPDIR:-/tmp}/vi-smoke.XXXXXX")
trap 'rm -rf -- "$work"' EXIT
case $(uname -s) in Linux) ;; *) export INSTALL_ANY_OS=1 ;; esac
url=$work
command -v cygpath >/dev/null 2>&1 && url=$(cygpath -m "$work")   # Git Bash, for the installer's own development
url="file://$( case $url in /*) ;; *) printf / ;; esac )$url"

failed=0
check() { if eval "$1"; then echo "  ok    $2"; else echo "  FAIL  $2"; failed=1; fi; }

cd "$work"
mkdir -p src/rt/bin home/Desktop
printf '#!/bin/sh\necho "demo v1 $*"\n' > src/demo
printf 'jar' > src/tool-uber.jar
printf '#!/bin/sh\necho rt\n' > src/rt/bin/rt
chmod +x src/rt/bin/rt
tar -czf src/rt.tar.gz -C src/rt bin
printf 'notices' > src/NOTICES.txt
printf 'iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAHUlEQVR4XmNQTX79nxLMgC5AKh41YNSAUQMGiwEAU3pyH+JgL9IAAAAASUVORK5CYII=' | base64 -d > src/icon.png
cat > demo.json <<'EOF'
{ "id": "demo", "name": "Demo", "publisher": "Example", "description": "A demo, it's quoted.", "repo": "owner/demo",
  "linux": {
    "system": { "memoryMb": 1, "diskMb": 1 },
    "binaries": [
      { "name": "demo", "source": "src/demo", "addToPath": true, "icon": "src/icon.png",
        "shortcuts": { "label": "Demo", "startMenu": true },
        "fileAssociations": [ { "extension": "vxr", "description": "Demo scene" } ] },
      { "name": "tool", "source": "src/tool-uber.jar", "file": "tool.jar", "javaMin": 11, "jvmArgs": ["-Dx=it's"] },
      { "source": "src/rt.tar.gz", "dir": "rt", "exposes": { "rtbin": "bin/rt" } },
      { "name": "notices", "source": "src/NOTICES.txt" } ] } }
EOF
gen() { java -jar "$cli" --config "$1" --version "$2" --out "$3" --assets "assets-$3" --base-url "$url/$3" --asset-base-url "$url/rel" > /dev/null \
        && mkdir -p "rel/v$2" && cp "assets-$3/$2"/* "rel/v$2/"; }
export HOME="$work/home" XDG_DATA_HOME="$work/home/.local/share" XDG_CONFIG_HOME="$work/home/.config"
opt="$HOME/.local/opt/demo"
reg="$XDG_DATA_HOME/vexelray-installer/installs"

echo "== generate"
gen demo.json 1.0.0 out
for f in out/install.sh out/uninstall.sh out/update.sh; do check "$run -n $f" "$f parses"; done
command -v shellcheck >/dev/null 2>&1 && check "shellcheck -s sh -S error out/*.sh" "shellcheck finds no errors"

echo "== install"
check "$run out/install.sh > install.log 2>&1" "installs"
check "[ \"\$($opt/demo hi)\" = 'demo v1 hi' ]" "the binary runs"
check "[ -L $HOME/.local/bin/demo ] && [ \"\$($HOME/.local/bin/demo)\" = 'demo v1 ' ]" "~/.local/bin/demo links to it"
launcher_ok() { [ -x "$opt/tool" ] && grep -qF "'-Dx=it'\\''s' -jar '$opt/tool.jar'" "$opt/tool"; }
check launcher_ok "the jar launcher quotes its arguments"
check "[ \"\$($opt/rt/bin/rt)\" = rt ]" "the archive unpacks, executable"
check "grep -q '^Exec=\"$opt/demo\" %f\$' $XDG_DATA_HOME/applications/vexelray-demo-demo.desktop" "a menu entry"
check "grep -q 'MimeType=application/x-demo-vxr;' $XDG_DATA_HOME/applications/vexelray-demo-demo.desktop" "it opens .vxr"
check "[ -f $HOME/Desktop/vexelray-demo-demo.desktop ]" "a desktop launcher"
check "grep -q 'glob pattern=\"\\*.vxr\"' $XDG_DATA_HOME/mime/packages/vexelray-demo.xml" "the .vxr type"
check "grep -q '\"platform\": \"linux\"' $reg/demo.json && grep -q '\"rtbin\": { \"path\": \"$opt/rt/bin/rt\"' $reg/demo.json" "the install record"
command -v desktop-file-validate >/dev/null 2>&1 && check "desktop-file-validate $XDG_DATA_HOME/applications/vexelray-demo-demo.desktop" "the menu entry validates"
check "$run out/install.sh | grep -q 'already installed'" "a second run changes nothing"

echo "== update"
printf '#!/bin/sh\necho "demo v2 $*"\n' > src/demo
# 1.1.0 no longer ships the notices
cat > demo2.json <<'EOF'
{ "id": "demo", "name": "Demo", "publisher": "Example", "description": "A demo, it's quoted.", "repo": "owner/demo",
  "linux": {
    "system": { "memoryMb": 1, "diskMb": 1 },
    "binaries": [
      { "name": "demo", "source": "src/demo", "addToPath": true, "icon": "src/icon.png",
        "shortcuts": { "label": "Demo", "startMenu": true },
        "fileAssociations": [ { "extension": "vxr", "description": "Demo scene" } ] },
      { "name": "tool", "source": "src/tool-uber.jar", "file": "tool.jar", "javaMin": 11, "jvmArgs": ["-Dx=it's"] },
      { "source": "src/rt.tar.gz", "dir": "rt", "exposes": { "rtbin": "bin/rt" } } ] } }
EOF
gen demo2.json 1.1.0 out
check "$run $opt/update.sh --check | grep -q '1.1.0 is available'" "update.sh --check sees 1.1.0"
check "$run $opt/update.sh --yes > update.log 2>&1" "update.sh --yes updates"
check "[ \"\$($opt/demo)\" = 'demo v2 ' ]" "the new binary is in place"
check "[ ! -e $opt/NOTICES.txt ]" "a file the new version dropped is gone"

echo "== a tampered download is refused, and what was installed stays"
printf 'evil' >> rel/v1.1.0/demo
rm -f "$XDG_DATA_HOME/vexelray-installer/cache/$(sha256sum < assets-out/1.1.0/demo | cut -d' ' -f1)"
# into another folder, so the binary has to be downloaded rather than found current
check "! INSTALL_DIR=$work/elsewhere $run out/install.sh > tamper.log 2>&1" "the install fails"
check "grep -q 'does not match the SHA-256' tamper.log" "it says why"
check "[ ! -e $work/elsewhere ] && [ \"\$($opt/demo)\" = 'demo v2 ' ]" "nothing was placed, and 1.1.0 still runs"
cp assets-out/1.1.0/demo rel/v1.1.0/demo

echo "== an install that was killed is rolled back by the next"
bk="$XDG_DATA_HOME/vexelray-installer/backup/demo"
mkdir -p "$bk"
cp "$opt/demo" "$bk/1"
printf 'backup\t%s\nput\t%s\t%s\nput\t%s\t-\n' "$bk" "$opt/demo" "$bk/1" "$opt/junk" > "$reg/demo.journal"
printf 'half-written' > "$opt/demo"
touch "$opt/junk"
check "$run out/install.sh | grep -q 'did not finish'" "the next run notices"
check "[ \"\$($opt/demo)\" = 'demo v2 ' ] && [ ! -e $opt/junk ] && [ ! -e $reg/demo.journal ]" "and puts it back"

echo "== options are remembered"
check "INSTALL_NO_PATH=1 INSTALL_FORCE=1 $run out/install.sh > /dev/null 2>&1" "reinstall without PATH"
check "[ ! -e $HOME/.local/bin/demo ]" "the link is gone"
check "INSTALL_FORCE=1 $run out/install.sh > /dev/null 2>&1 && [ ! -e $HOME/.local/bin/demo ]" "and stays gone"

echo "== a suite"
printf '#!/bin/sh\necho two\n' > src/two
printf '%s\n' '{ "id": "two", "name": "Two", "repo": "owner/two", "linux": { "binaries": [ { "source": "src/two", "addToPath": true } ] } }' > two.json
printf '%s\n' '{ "id": "win", "name": "Win", "repo": "owner/win", "windows": { "binaries": [ { "source": "src/two" } ] } }' > win.json
gen two.json 2.0.0 out2
cat > suite.json <<EOF
{ "id": "suite", "name": "Suite", "repo": "owner/site", "members": [
  { "config": "demo2.json", "manifestUrl": "$url/out/manifest.json" },
  { "config": "two.json", "manifestUrl": "$url/out2/manifest.json", "optional": true },
  { "config": "win.json" } ] }
EOF
java -jar "$cli" --suite suite.json --out suite --base-url "$url/suite" > /dev/null
check "$run -n suite/install.sh" "the suite's install.sh parses"
check "$run suite/install.sh > suite.log 2>&1 && grep -q 'Two is optional and not installed' suite.log" "an optional member is left out"
check "INSTALL_ONLY=two,win $run suite/install.sh > suite2.log 2>&1 && [ \"\$($HOME/.local/opt/two/two)\" = two ]" "INSTALL_ONLY installs it"
check "grep -q 'Win has no Linux version yet' suite2.log" "a Windows-only member is reported"
check "! INSTALL_ONLY=nope $run suite/install.sh > /dev/null 2>&1" "an unknown id is refused"

echo "== uninstall"
check "$run $opt/uninstall.sh --list | grep -q '^two '" "--list shows every install"
check "! $run $opt/uninstall.sh < /dev/null > /dev/null 2>&1" "without a terminal it needs --yes"
check "$run $opt/uninstall.sh --all --yes > /dev/null" "--all --yes removes everything"
check "[ ! -e $opt ] && [ ! -e $HOME/.local/opt/two ] && [ ! -e $XDG_DATA_HOME/applications/vexelray-demo-demo.desktop ]" "the folders and menu entries are gone"
check "[ ! -e $HOME/Desktop/vexelray-demo-demo.desktop ] && [ ! -e $XDG_DATA_HOME/mime/packages/vexelray-demo.xml ]" "and the launcher and the file type"
check "[ -z \"\$(ls $reg)\" ]" "no records are left"

echo
if [ "$failed" = 0 ]; then echo "linux smoke test passed"; else echo "linux smoke test FAILED (logs were in $work)"; exit 1; fi
