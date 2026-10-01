## Install (Windows)

Open **PowerShell** (a normal window - administrator rights are not needed) and run:

```powershell
{{INSTALL_COMMAND}}
```

That is the whole install. It downloads {{NAME}} {{VERSION}}, checks every file against the
SHA-256 in the [install manifest]({{MANIFEST_URL}}), and installs for your user only.

What it does:

{{WHAT_IT_DOES}}

### Options

Set these in the same PowerShell window *before* the install command:

| Variable | Effect |
| --- | --- |
| `$env:INSTALL_VERSION = '1.2.3'` | install a specific version from the [history](#versions) |
| `$env:INSTALL_DIR = 'D:\Apps\{{NAME}}'` | install somewhere other than `{{INSTALL_DIR}}` |
| `$env:INSTALL_NO_SHORTCUTS = '1'` | skip the desktop / Start menu shortcuts |
| `$env:INSTALL_NO_PATH = '1'` | do not add anything to your user PATH |
| `$env:INSTALL_FORCE = '1'` | reinstall even if this version is already installed |

### What gets installed

{{BINARY_TABLE}}
{{JAVA_NOTE}}
Everything installed is recorded in `{{INSTALL_DIR}}\install-manifest.json`, and a copy
in `{{REGISTRY_DIR}}\{{ID}}.json`.

### Update

```powershell
& "{{INSTALL_DIR}}\update.cmd"
```

Checks GitHub for newer versions of the binaries and installs them after asking. Use
`-Check` to only look, `-Yes` to skip the question.

### Uninstall

Use **Settings > Apps > {{NAME}} > Uninstall**, or:

```powershell
& "{{INSTALL_DIR}}\uninstall.cmd"
```

The uninstaller reads the install manifests in `{{REGISTRY_DIR}}`, so it removes exactly
what was installed - files, shortcuts, PATH entries and the Apps entry. Files you added to
the install folder are left alone. `uninstall.cmd -List` shows every install it knows about
and `-All` removes them all.

### Is it safe to run a script from the internet?

Read it first - it is one file: [`install.ps1`]({{INSTALL_URL}}). Downloads are only
accepted when their SHA-256 matches the manifest, and the manifest and script live in this
repository.
