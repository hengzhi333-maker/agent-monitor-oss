# Known Limitations

- Agent Monitor is designed for trusted private networks. Do not expose the
  daemon directly to the public internet.
- The Android app is optimized for private operator workflows, not for a
  multi-tenant SaaS model.
- Workbench control can start real agent processes on the workstation. Treat it
  as remote code execution on your own machine.
- Docker workbench sessions run inside the container and can only access mounted
  workspaces and installed CLIs.
- USB access through `adb reverse` is useful for testing but stops working after
  unplugging or rebooting the phone. Use Tailscale or LAN for normal operation.
- Samsung Secure Folder is a separate Android profile. Test APKs installed
  there must be removed from inside Secure Folder.
- Legacy binary `.xls` files are not accepted; use `.xlsx` or CSV.
- The daemon is self-hosted and local-first. There is no hosted sync service,
  team administration UI, or browser-first console.
- Some diagnostics depend on platform tools such as Tailscale, Git, Codex,
  Claude Code, or service CLIs being installed on the workstation.
