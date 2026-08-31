# Code signing policy

Free code signing provided by [SignPath.io](https://signpath.io), certificate by [SignPath Foundation](https://signpath.org).

## Team roles

- Committer and reviewer: [Jerkify](https://github.com/Jerkify)
- Approver: [Jerkify](https://github.com/Jerkify)

## Release process

Windows releases are built from the tagged source revision on GitHub-hosted runners. After SignPath activates the project, the unsigned artifact will be submitted through the official GitHub integration. Each production signing request will require approval before signed files are attached to the GitHub release.

Bootstrap releases published before activation are unsigned and identified as such.

Release assets include SHA-256 checksums.

## Privacy

See [PRIVACY.md](PRIVACY.md).
