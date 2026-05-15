# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 1.1.x (latest) | ✅ Yes |
| < 1.1.0 | ❌ No — please update |

## Reporting a Vulnerability

**Please do not open a public GitHub issue for security vulnerabilities.**

If you discover a security issue in Fox Client or Fox Launcher, report it privately via **GitHub's Security Advisories**:

1. Go to the [Security tab](../../security/advisories/new) of this repository
2. Click **"Report a vulnerability"**
3. Fill in the details — what you found, how to reproduce it, and the potential impact

You'll receive a response within **72 hours**. If the issue is confirmed, a fix will be released as soon as possible and you'll be credited in the release notes (unless you prefer to stay anonymous).

## Scope

Reports are welcome for:
- **Fox Launcher** — authentication flow, auto-update mechanism, IPC surface, file handling
- **Fox Client mod** — anything that could be exploited to harm other players or servers beyond the documented feature set

Out of scope:
- "Fox Client gives an advantage over vanilla" — that's intentional and documented in [SAFETY.md](SAFETY.md)
- Issues in Minecraft itself, Fabric Loader, or third-party mods
- SmartScreen / antivirus false positives on the unsigned installer (see the release notes for SHA-256 verification)
