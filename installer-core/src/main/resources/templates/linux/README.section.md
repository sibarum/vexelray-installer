## Install (Linux)

Open a terminal and run (no `sudo`: it installs for your user only):

```sh
{{INSTALL_COMMAND}}
```

It downloads {{NAME}} {{VERSION}}, checks every file against the SHA-256 in the
[install manifest]({{MANIFEST_URL}}), and installs into `{{INSTALL_DIR}}`. It needs `curl` (or
`wget`), `tar` and `sha256sum`, which every desktop distribution has.

What it does:

{{WHAT_IT_DOES}}
{{REQUIREMENTS}}
### Options

Set these for the `sh` that runs the script, as in `curl -fsSL {{INSTALL_URL}} | INSTALL_NO_PATH=1 sh`:

| Variable | Effect |
| --- | --- |
| `INSTALL_VERSION=1.2.3` | install a specific version from the [history](#versions) |
| `INSTALL_DIR=/opt/{{ID}}` | install somewhere other than `{{INSTALL_DIR}}` |
| `INSTALL_NO_SHORTCUTS=1` | no menu entry or desktop launcher |
| `INSTALL_NO_PATH=1` | link nothing into `~/.local/bin` |
| `INSTALL_NO_ASSOCIATIONS=1` | do not register {{NAME}} to open any file types |
| `INSTALL_FORCE=1` | reinstall even if this version is already installed |
| `INSTALL_SKIP_CHECKS=1` | install even if this computer does not meet the system requirements |

Updates and reinstalls remember the `INSTALL_NO_*` choices of the last install. To get them back,
set that variable to `0` and run the install command again.

### What gets installed

{{BINARY_TABLE}}
{{JAVA_NOTE}}
Everything installed is recorded in `{{INSTALL_DIR}}/install-manifest.json`, and a copy in
`{{REGISTRY_DIR}}/{{ID}}.json`, the same record the Windows installer writes: other programs
find {{NAME}} through `commands.<name>.path` in it.

### Update

```sh
"{{SH_INSTALL_DIR}}/update.sh"
```

Checks for a newer version and installs it after asking. `--check` only looks, `--yes` does not ask.
An update that fails or is interrupted puts back the version you had, exactly as it was; if the
computer went off halfway, the next install or update first finishes putting it back.

### Uninstall

```sh
"{{SH_INSTALL_DIR}}/uninstall.sh"
```

Removes exactly what was installed: files, the menu entry and desktop launcher, the links in
`~/.local/bin` and the file types. Files you added to the install folder are left alone. `--list`
shows every install it knows about, `--all` removes them all, `--yes` does not ask.{{ISSUES}}

### Is it safe to pipe a script into sh?

Read it first - it is one file: [`install.sh`]({{INSTALL_URL}}). Downloads are only accepted when
their SHA-256 matches the manifest, and the manifest and the script live in this repository. It never
asks for root, and writes nowhere outside your home folder unless `INSTALL_DIR` says so.
