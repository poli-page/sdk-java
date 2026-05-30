# Security Policy

## Reporting a Vulnerability

Please report security vulnerabilities to **security@poli.page**.

Do not file public GitHub issues for security concerns.
We aim to respond within 48 hours.

## Supported Versions

Only the latest minor version of `page.poli:sdk` on Maven Central
receives security updates. Maven Central artifacts are immutable —
old releases remain installable but are not patched in place. We use
the OWASP `dependency-check` plugin in CI to catch transitive issues;
consumers should run the same check in their own builds.
