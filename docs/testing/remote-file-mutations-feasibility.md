# Remote file mutation feasibility

Status: preliminary local probes, 2026-11-02. The create, rename and delete
features are not implemented or enabled by these probes.

On the local macOS ARM64 filesystem, Node 24.18.0 opened files using
`O_CREAT | O_EXCL | O_NOFOLLOW | O_WRONLY` with mode `0600` in a new temporary
directory. An existing regular file and a final dangling symlink both produced
`EEXIST`; the existing file's bytes were unchanged. Two concurrent attempts in
the same Node process produced one success and one `EEXIST`. A separate local
probe also rejected a final symlink to an existing target. Each probe removed
only its own temporary directory.

This does not establish intermediate-directory race protection, cross-process
stress behavior, filesystem durability, or behavior on other filesystems. The
Node runtime inspection did not expose a public exclusive-rename flag; this is a
runtime observation, not proof that the operating system lacks an appropriate
native API. Rename without overwriting remains a platform feasibility gate.

The existing editor outcome ledger provides useful persistence building blocks,
but its single-writer lock is held externally and its duplicate, lookup and
deletion semantics do not implement the proposed file-operation contract. Its
provisioning marker alone does not detect simultaneous loss or rollback of both
marker and ledger. The [design](../superpowers/specs/2026-11-02-remote-file-mutations-design.md)
and [plan](../superpowers/plans/2026-11-02-remote-file-mutations.md) require a
verified lock domain and continuity mechanism before enabling mutations.

A local Linux container probe was not completed: the Docker image inspection did
not return before it was interrupted. No Docker restart or container mutation was
performed. Independent remote Linux and physical Samsung device qualification
remain open.
