## Install (Windows)

Open **PowerShell** (a normal window - administrator rights are not needed) and run:

```powershell
{{INSTALL_COMMAND}}
```

That is the whole install. It downloads {{NAME}} {{VERSION}}, checks every file against the
SHA-256 in the [install manifest]({{MANIFEST_URL}}), and installs for your user only.

What it does:

{{WHAT_IT_DOES}}
{{REQUIREMENTS}}
### Options

Set these in the same PowerShell window *before* the install command:

| Variable | Effect |
| --- | --- |
| `$env:INSTALL_VERSION = '1.2.3'` | install a specific version from the [history](#versions) |
| `$env:INSTALL_DIR = 'D:\Apps\{{NAME}}'` | install somewhere other than `{{INSTALL_DIR}}` |
| `$env:INSTALL_NO_SHORTCUTS = '1'` | skip the desktop / Start menu shortcuts |
| `$env:INSTALL_NO_PATH = '1'` | do not add anything to your user PATH |
| `$env:INSTALL_NO_ASSOCIATIONS = '1'` | do not register {{NAME}} to open any file types |
| `$env:INSTALL_FORCE = '1'` | reinstall even if this version is already installed |
| `$env:INSTALL_SKIP_CHECKS = '1'` | install even if this computer does not meet the system requirements |

Updates and reinstalls remember the `INSTALL_NO_*` choices of the last install. To get shortcuts,
the PATH entry or file types back, set that variable to `'0'` and run the install command again.

### What gets installed

{{BINARY_TABLE}}
{{JAVA_NOTE}}
Everything installed is recorded in `{{INSTALL_DIR}}\install-manifest.json`, and a copy
in `{{REGISTRY_DIR}}\{{ID}}.json`.

### Other programs finding {{NAME}}

Installs are independent, so {{NAME}} can sit next to other programs from the same
installer. To start it from one of them without depending on PATH, read
`{{REGISTRY_DIR}}\{{ID}}.json` and use `commands.<name>.path` - an absolute path to the
executable (the `.cmd` launcher for a jar). Missing file means not installed. Pass arguments
as usual; from Java use `new ProcessBuilder(path, args...)`, and for a `.cmd` launcher run it
as `cmd.exe /c path args...`.

### Update

```powershell
& "{{PS_INSTALL_DIR}}\update.cmd"
```

Checks GitHub for newer versions of the binaries and installs them after asking. Use
`-Check` to only look, `-Yes` to skip the question.

### Reporting a problem

```powershell
& "{{PS_INSTALL_DIR}}\diagnose.cmd"
```

Writes a report about this computer and prints where it is: Windows version, processor, memory, the displays
(resolution, refresh rate, scaling, HDR), each GPU as DXGI / Direct3D 12 and as Vulkan see it, whether a
flip-model swapchain can be created, the Vulkan present modes, implicit Vulkan layers, and what is installed.
Nothing is sent anywhere; your user name is replaced by `%USERPROFILE%`. Read it, then attach it {{ISSUES}}.
`-Copy` also puts it on the clipboard. Without installing, run `irm {{BASE_URL}}/diagnose.ps1 | iex`. A failed
install writes the same report by itself.

### Uninstall

Use **Settings > Apps > {{NAME}} > Uninstall**, or:

```powershell
& "{{PS_INSTALL_DIR}}\uninstall.cmd"
```

The uninstaller reads the install manifests in `{{REGISTRY_DIR}}`, so it removes exactly
what was installed - files, shortcuts, PATH entries, file types and the Apps entry. Files you added to
the install folder are left alone. `uninstall.cmd -List` shows every install it knows about
and `-All` removes them all.

### Is it safe to run a script from the internet?

Read it first - it is one file: [`install.ps1`]({{INSTALL_URL}}). Downloads are only
accepted when their SHA-256 matches the manifest, and the manifest and script live in this
repository.
