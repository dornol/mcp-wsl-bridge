# Publishing MCP WSL Bridge

## Before the first Marketplace upload

1. Confirm the MIT license in `LICENSE` is appropriate for the release.
2. Create a JetBrains Marketplace Vendor profile for `dornol`, accept the Developer Agreement, and provide a public vendor contact email.
3. Create a Marketplace upload token and save it in the GitHub repository as the `PUBLISH_TOKEN` Actions secret.
4. Build and test the plugin on Windows with a real IntelliJ IDEA 2025.2+ installation and WSL.
5. Update `pluginVersion`, `CHANGELOG.md`, and the release notes before publishing a later version.

## Automated publishing

The `Publish Marketplace Plugin` workflow runs when a GitHub release is published. It invokes `publishPlugin` using the `PUBLISH_TOKEN` secret.

Use a release tag matching the plugin version, such as `v0.1.0`. The release should be created only after the corresponding commit has passed CI and the Windows/WSL integration test.

## Signing

Plugin signing is recommended for Marketplace distribution. If signing credentials are added later, configure `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, and `PRIVATE_KEY_PASSWORD` as GitHub Actions secrets and enable the Gradle signing configuration.
