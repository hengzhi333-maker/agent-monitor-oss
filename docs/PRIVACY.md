# Privacy Notes

Agent Monitor handles local operational data from your workstation. Depending on
configuration, it may display or store:

- agent session titles and message excerpts,
- local file paths,
- service health and account-health summaries,
- workbench prompts and responses,
- uploaded images, text files, code files, PDFs, Word documents, and Excel files,
- daemon audit events.

The daemon automatically applies default redaction to conversation history and
audit details for common secrets, authorization headers, emails, and local user
paths. Redaction is defense-in-depth, not a guarantee that every private string
is removed.

Private runtime data is stored on the workstation and is ignored by git:

- `daemon/config.json`
- `daemon/*.log`
- `daemon/.agent-monitor-audit.jsonl`
- `daemon/.workbench-sessions.json`
- `daemon/.workbench-uploads/`
- Android build outputs and APKs
- Android signing config and keystore files

Before publishing a repository, scan for:

- real tokens, passwords, API keys, cookies, and auth headers,
- personal email addresses,
- local usernames and hostnames,
- exact Tailscale IPs tied to your devices,
- device serial numbers,
- local absolute paths,
- uploaded or generated user files.

Uploaded workbench files are retained for `remoteControl.attachmentTtlHours`
hours by default and can also be removed from the Android Files screen or with
`POST /workbench/attachments/cleanup`.

Snapshot privacy controls:

- `privacy.maskAccountEmails`: masks account email addresses in service health.
- `privacy.redactCwd`: replaces session working directories with folder names.
- `privacy.hermesStatusMaxLen`: trims Hermes status text; `0` suppresses it.

Use examples instead of real values:

- `100.64.0.10` for a sample Tailscale IP,
- `203.0.113.10` for a sample public IP,
- `C:\Users\you\Documents\AgentWorkspace` for a Windows workspace,
- `<token>` and `<password>` for credentials.
