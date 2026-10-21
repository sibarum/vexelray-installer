## Windows

Open **PowerShell** (a normal window - administrator rights are not needed) and run:

```powershell
{{INSTALL_COMMAND}}
```

A window opens where you choose which of the applications below to install, and whether they get
shortcuts, PATH entries and file types; it then shows each one's progress. That installs them for your
user only. Each one is a separate install with its own
folder, its own entry in **Settings > Apps**, and its own updater and uninstaller, so you can remove one
and keep the others. {{RERUN}}

{{MEMBER_TABLE}}

Each application is installed by its own installer, the same one its own install command runs. This
script downloads that installer, checks it against the SHA-256 in the application's published manifest,
and runs it. The installer then checks every file it downloads in the same way. An application that has
not been published yet is skipped and reported.

### Only some of them

Untick the others in the window. Without the window (see below), set `INSTALL_ONLY` in the same PowerShell
window *before* the install command; with it, `INSTALL_ONLY` says which boxes start ticked:

```powershell
$env:INSTALL_ONLY = '{{EXAMPLE_IDS}}'
```{{OPTIONAL}}

### Everything, without being asked

Run the script as a script block, which takes arguments, as `irm | iex` cannot:

```powershell
& ([scriptblock]::Create((irm {{INSTALL_URL}}))) --install-everything --do-not-prompt-me
```

`--install-everything` installs every application, the optional ones too (`$env:INSTALL_EVERYTHING = '1'`
does the same), and `--do-not-prompt-me` opens no window and asks nothing (as `$env:INSTALL_QUIET = '1'`).
Either one works alone.

### Options

These are passed on to every application's installer:

| Variable | Effect |
| --- | --- |
| `$env:INSTALL_NO_SHORTCUTS = '1'` | skip the desktop / Start menu shortcuts |
| `$env:INSTALL_NO_PATH = '1'` | do not add anything to your user PATH |
| `$env:INSTALL_NO_ASSOCIATIONS = '1'` | do not register any application to open file types |
| `$env:INSTALL_FORCE = '1'` | reinstall even where the latest version is already installed |
| `$env:INSTALL_SKIP_CHECKS = '1'` | install even where this computer does not meet an application's system requirements |
| `$env:INSTALL_QUIET = '1'` | no window: ask nothing and report in this PowerShell window only |

With the window, the first three set how its boxes start.

A particular version or folder applies to one application, so this script ignores `INSTALL_VERSION` and
`INSTALL_DIR`. To use them, run that application's own installer.

### Update

Run the install command again, or update one application from its folder:

```powershell
& "{{PS_EXAMPLE_DIR}}\update.cmd"
```

### Uninstall

Use **Settings > Apps**, where each application has its own entry, or run the uninstaller in any of their
folders:

```powershell
& "{{PS_EXAMPLE_DIR}}\uninstall.cmd" -Id {{EXAMPLE_ID}}
```

`-List` shows every install it knows about. Each application's install record is in `{{REGISTRY_DIR}}`,
which is also how the applications find and start each other.

### Is it safe to run a script from the internet?

Read it first - it is one file: [`install.ps1`]({{INSTALL_URL}}). It runs only installers whose SHA-256
matches their application's manifest, and those installers accept a download only when its SHA-256
matches too.

