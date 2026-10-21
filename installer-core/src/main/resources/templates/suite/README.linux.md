## Linux

Open a terminal and run (no `sudo`: everything is installed for your user only):

```sh
{{SH_COMMAND}}
```

That installs each of the applications below as an install of its own, with its own folder, updater and
uninstaller, so you can remove one and keep the others. {{RERUN}}{{WINDOWS_ONLY}}

{{MEMBER_TABLE}}

Each application is installed by its own installer, the same one its own install command runs. This
script downloads that installer, checks it against the SHA-256 in the application's published manifest,
and runs it. The installer then checks every file it downloads in the same way. An application that has
not been published yet is skipped and reported.

### Only some of them, or everything

```sh
curl -fsSL {{SH_URL}} | INSTALL_ONLY={{EXAMPLE_IDS}} sh
curl -fsSL {{SH_URL}} | sh -s -- --install-everything
```

`--install-everything` (or `INSTALL_EVERYTHING=1`) installs the optional applications too.{{OPTIONAL}}

### Options

These are passed on to every application's installer, set the same way as `INSTALL_ONLY`:

| Variable | Effect |
| --- | --- |
| `INSTALL_NO_SHORTCUTS=1` | no menu entries or desktop launchers |
| `INSTALL_NO_PATH=1` | link nothing into `~/.local/bin` |
| `INSTALL_NO_ASSOCIATIONS=1` | do not register any application to open file types |
| `INSTALL_FORCE=1` | reinstall even where the latest version is already installed |
| `INSTALL_SKIP_CHECKS=1` | install even where this computer does not meet an application's system requirements |

A particular version or folder applies to one application, so this script ignores `INSTALL_VERSION` and
`INSTALL_DIR`. To use them, run that application's own installer.

### Update and uninstall

Run the install command again to update everything, or update one application from its folder with its
`update.sh`. To remove one application, or all of them:

```sh
"{{SH_EXAMPLE_DIR}}/uninstall.sh"
"{{SH_EXAMPLE_DIR}}/uninstall.sh" --all
```

`--list` shows every install it knows about. Each application's install record is in `{{REGISTRY_DIR}}`,
which is also how the applications find and start each other.

### Is it safe to pipe a script into sh?

Read it first - it is one file: [`install.sh`]({{SH_URL}}). It runs only installers whose SHA-256
matches their application's manifest, never asks for root, and writes nowhere outside your home folder.

