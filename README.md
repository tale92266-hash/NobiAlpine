# Nobi Alpine

Nobi Alpine is an Android terminal application based on the Termux open-source
Android frontend, with Alpine Linux as its default and only Linux environment.

- App name: Nobi Alpine
- Package: `com.nobi.alpine`
- Architecture: ARM64 initially
- Shell: Bash (default, inside Alpine)
- Package manager: `apk` (Alpine)
- Execution: PRoot (simulated root only; no systemd, no Docker daemon)

## Status: M1

M1 proves the fork identity and the setup flow:

- New application identity (`com.nobi.alpine`, "Nobi Alpine").
- Termux bootstrap installation removed: no bootstrap zip, no `$PREFIX`,
  no `pkg`/`apt` environment.
- First launch shows the Alpine setup screen (placeholder in M1). Tapping
  "Open Terminal" opens the terminal. The Alpine Linux installer arrives
  in M2; PRoot launching is proved in M3.

The launcher icon is unchanged from the Termux base in M1. Icon options
follow after M1.

## Build

Builds run on GitHub Actions (`.github/workflows/debug_build.yml`) and
produce complete debug APKs. The signing key is a fixed key held only in
GitHub Actions secrets; it is never committed to this repository.

## Credits and license

Nobi Alpine is a fork of [Termux](https://github.com/termux/termux-app)
(GPL-3.0, see `LICENSE.md`). All credit for the terminal frontend goes to
Termux and its developers. Alpine Linux is a separate project; see
https://www.alpinelinux.org/ for its own credits and licensing.
