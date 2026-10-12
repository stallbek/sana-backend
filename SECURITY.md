# Security

Please report suspected vulnerabilities privately through this repository's
**Security → Report a vulnerability** feature. Do not place credentials, customer data,
or working exploit details in a public issue. Private reporting will be enabled before
public release. If the feature is unavailable, do not post sensitive details publicly;
request a private reporting channel without including the vulnerability details.

The maintainer reviews reports for this repository's current main branch.
No response-time or production-support commitment is made.

Secrets must be supplied outside Git. If a credential is exposed, revoke or rotate it
at its provider; deleting it from a file does not revoke access. Use the README's
security checks and review new dependencies and configuration changes before release.
