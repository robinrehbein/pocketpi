# Native production editor: local qualification

The current **local development-branch candidate** enables the paged editor and positive
outcome recovery on the regular Native Host path and wires the Android application
factory to its real journal. This records local qualification, not an external
deployment or release. RPC remains frozen as a compatibility fallback; no new RPC
feature or parity is claimed.

## Component checks and review

The scoped Host suites passed 32/32 tests; the subsequently extended Native
lease integration file passed 7/7, including a new second-writer attempt after
lease release while finalization remained held. Host extensions TypeScript
checking exited 0. Android Repository, Journal and Wire suites passed 645, 22
and 11 tests respectively (678 total), with no failures or skips. Independent
App, Host and cross-layer reviews reported no concrete defect. The factory audit
was strengthened to check response capabilities and installed APK hashes and
passed. These checks qualify the local candidate and their tested scopes. The
candidate is committed locally on App `8e7f4f408b11cdcf9894c813227fe1f156accb60`
and Host `ba7329bbec6fbed450fd72228390ec50cdb5e3dc`. Full Biome cleanliness is not claimed: `run.ts:80` has a
pre-existing `noControlCharactersInRegex` finding.

## Evidence

- **Direct repository, `native-production-03`:** an isolated Android emulator,
  actual Native Host worker and Pi SDK ran with Host fixture enablement off and
  Android test gates off. A wrong grant was refused before transfer; an explicit
  valid retry produced the exact 1 MiB SDK result. An applied outcome and matching
  release were observed, with a fresh journal read before release and an empty
  read after its ACK. This test constructed the repository directly; it did not
  exercise the application factory. The independent audit and run artifacts are
  `/private/tmp/pocketpi-native-fresh-tools/lab/native-production-03-independent-audit.json`
  and `/private/tmp/pocketpi-native-fresh-tools/lab/native-production-03-*`.
- **Application factory, `native-factory-01`:** a separate run exercised the
  `RemoteApplication`-created repository and stores through `MainActivity`, using
  debug instrumentation, transport and bootstrap. It observed negotiated Native
  support, the unchanged 1 MiB source and exact SDK result, a positive receipt and
  correlated release. It used a valid grant only; the wrong-grant result above is
  not a factory-run result. The audit and artifacts are
  `/private/tmp/pocketpi-native-fresh-tools/lab/native-factory-01-independent-audit.json`
  and `/private/tmp/pocketpi-native-fresh-tools/lab/native-factory-01-*`.

Both audits check captured artifact consistency; they are not independent runtime
observers or independently reproducible source-to-APK provenance. The direct-run
ledger and journal evidence is observer readback rather than independent raw disk
snapshots; its click timing and all raw transfer-ID mappings were not independently
established. The direct run's recorded source pins predate the later factory-test
bootstrap edit; assess that run against its recorded snapshots, not the current
workspace pins. Build manifests and logs are in `/private/tmp/pocketpi-native-fresh-tools/`
under `prod-build-01-*` and `factory-build-01-*` respectively.

These runs establish the stated local paths and payload, not Release transport,
physical Galaxy S25 or Tab S11, vendor IME behaviour, or an independent Linux host.
They do not complete the broader remote-development goal. Earlier tests retain
their own fixture, ordering and source-pin limitations; see the
[enablement checkpoint](paged-editor-enablement-checkpoint.md). Combined App, Host
and cross-layer review and the scoped regressions described above have completed;
broader workflow qualification remains separate work.
