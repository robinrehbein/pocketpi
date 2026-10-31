# Linux Host Packaging Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development. Workers own disjoint packaging helpers/tests; root owns Docker execution, dependency locks, runtime/image integration and final qualification.

**Goal:** Ship a repository-maintained Linux host/devcontainer environment that runs the current public Pi extension without depending on a mounted monorepo or Mac-native dependencies.

**Architecture:** Package current Atlas into its real distribution archive, install it into an isolated exact-version Pi consumer, and build Linux-native PTY. Promote that consumer into an owner-controlled nonroot OCI runtime with persistent private state and explicit project mounts. Preserve Chromium's internal sandbox and qualify any required outer-seccomp change separately; never fall back to disabling it.

**Tech Stack:** Node24, Pi1.4.0, package archive, npm lockfile, Docker/Dev Containers, Debian Chromium, existing HostDaemon and public Pi API.

**Spec:** ../../remote-development-goal.md; ../../testing/linux-host-qualification.md. Wear is a reduced messages/questions companion, not a full IDE. Full phone/tablet functionality remains required.

## Constraints and evidence

- Root works on App `d00dcc1` and Host `1db5fcb4` after successful ff-only pulls; preserve unrelated work and containers.
- Existing Linux image/overlays are historical component evidence, not a clean installation of current source.
- The standalone consumer must use its own dependencies, actual shipped archive and native Linux PTY; no checkout symlinks, `NODE_PATH` fallback or mounted user home.
- Pin the Node image digest and Pi cohort; capture package integrity and lock transitive dependencies. Pin OS repository/package inputs before claiming reproducible image builds.
- Separate state/project mount paths prevent accidental exposure of other host files; they do not isolate secrets from trusted project code running under the same UID.
- No Docker socket, host credentials, default ADB server, privileged mode, `seccomp=unconfined` or Chromium `--no-sandbox` in shipped configuration.
- Setup/token provisioning and project trust remain explicit owner actions. Do not put the provisioning token into persistent worker/service environment.
- Local Docker evidence is not an independent remote host or physical Samsung qualification.

## Deliverables and gates

- [x] Establish a real clean-consumer install from the current archive, exact SDK cohort and locked dependencies; prove Linux PTY, public Pi CLI and Host CLI resolve from that consumer.
- [x] Add safe build-context preparation and regression checks for artifact integrity, existing-output preservation and no checkout fallback.
- [x] Build a versioned OCI core runtime with private persistent state, explicit project mount, init/signal handling and documented owner setup/start/status/stop.
- [ ] Qualify Chromium with its own sandbox enabled and a reviewed, provenance-bound outer profile for each advertised architecture; reject unsupported configurations.
- [ ] Add a Dev Containers adapter and qualify real native worker, encrypted Android transport, browser/preview ownership, reconnect, persistence and controlled failure against the packaged environment.
- [ ] Independent bounded review, required checks and local commits; record exact remaining independent-host/architecture/device gates without shrinking the goal.
