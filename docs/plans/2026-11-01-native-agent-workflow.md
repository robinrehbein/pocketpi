# Native agent turn within the development workflow

> **For agentic workers:** Use superpowers:subagent-driven-development; root executes runtime checks, Terra agents author/review disjoint Host and Android files.

**Goal:** Send a prompt from production Android navigation to the existing native Pi worker, execute real tool calls in its public agent loop and render its final response before the confirmed Git workflow.

**Architecture:** Extend only the private development-workflow fixture with a deterministic public Pi faux provider. The same native worker reads the UI-edited file and verifies it through a harmless tool command. Android sends the prompt through the composer; independent trace evidence binds the turn and results to that worker, session and unique run nonce. No external model or credentials are used.

**Tech stack:** Pi 1.4 public provider/extension APIs, existing TypeScript private fixture and Kotlin Compose instrumentation.

**Spec:** docs/remote-development-goal.md and the existing actual-development-workflow plan. This is a local agent-loop transport/tool qualification, not a language-model quality or external-provider test.

## Constraints and review focus

- One native worker/session/runtime/project must own file, agent, shell and confirmed Git actions.
- Trace actual prompt receipt, model requests, ordered tool execution/results and agent completion; a marker or aggregate flag alone is insufficient.
- Bind to an unpredictable per-run nonce and exact edited-file bytes; reject errors, extra turns/tools and replacement owners.
- Do not seed assistant messages, mutate fixture files instead of executing tools, or send through repository/test stdin instead of the UI.
- Keep explicit commit/push/PR confirmations and all existing no-premature-write guards.
- No personal Host, external API, real GitHub or default ADB mutation. Root alone runs processes/tests/builds.
- Public paged editor remains off. Physical Samsung, independent Linux, ticket start, real external models and broader lifecycle qualification remain open.

## Tasks

- [x] Host author: add private provider and trace validation using existing public faux-provider pattern; enable only the tools required in this workflow worker; expose concrete evidence plus derived agentVerified.
- [x] Android author: send plain agentPrompt after closing Files, observe actual assistant marker and tool evidence, verify stable owner and unchanged Git guards, then run the existing shell/Git/PR/CI sequence.
- [x] Root: type/format and instrumentation build, run private actual chain, diagnose observed failures without weakening gates, retain precise pre-run provenance and post-run trace/status/screenshots, clean owned fixture.
- [x] Independent reviewer: inspect changed code and runtime evidence; root records bounded qualification and locally commits reviewed changes.

## Result

The full extended chain passed on the phone viewport in 82.97 seconds and on the tablet-sized
viewport in 83.499 seconds. Root APK build, extension typecheck and changed-TypeScript format
checks passed. Independent code and runtime review found no blocking issue for these bounded
cases. Pre-launch source snapshots and installed APK hashes are retained with the private
fixture results. The goal remains broader than this increment; see the qualification document.
