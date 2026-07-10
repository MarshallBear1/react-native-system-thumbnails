# Security policy

## Reporting a vulnerability

Please report vulnerabilities through the repository's private GitHub security
advisory form. Do not open a public issue for an unpatched vulnerability.

## Security boundary

This package renders presentation thumbnails for files the host app can
already access. It does not determine whether a file is trustworthy, scan for
malware, or validate uploads. Treat source metadata as untrusted and perform
independent server-side validation for uploaded content.

The library writes only to its own app-cache subdirectory and cache cleanup is
restricted to that directory. Remote URLs are not fetched.
