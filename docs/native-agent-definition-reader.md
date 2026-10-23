# Native agent definition reader contract

## Interpreter compatibility

Inventory, full definition resolution and resource manifests all use the same fixed
installed `/usr/bin/python3 -I`. PATH, virtual environments and browser input cannot
select another interpreter. The reader uses **stdlib `tomllib` only**, with no `tomli`
or older-interpreter fallback.

Supported runtime lines are final-release **CPython 3.11.x, 3.12.x and 3.14.x**.
Other minor lines, other implementations and prereleases fail closed. Patch releases
within these characterized lines are accepted only after the bundled runtime check
passes. New minor lines require an explicit allowlist change and contract verification;
the policy is not `>=3.11`.

The execution grammar is the existing TOML 1.0 string-valued allowlist: `name`,
`description`, `developer_instructions`, `model`, `model_reasoning_effort`. The reader
does not normalize or rebuild instructions. It fingerprints the original file bytes.
Each helper invocation checks multiline strings, Unicode/escape decoding, literal
strings and exact values, and rejects duplicate keys/tables, invalid escapes (including
`\e` and `\x41`), malformed strings, control characters and invalid integers. A failed
check yields `RUNTIME_UNAVAILABLE` before filesystem traversal. The checks characterize
the grammar consumed here; they are not a claim to prove an entire parser implementation.

The same end-to-end helper tests were run on actual CPython **3.11.16**, **3.12.14**
(isolated local Docker images) and installed production **3.14.4**. They preserve the
same full instructions, model, effort and SHA-256 bytes, and test malformed/unsupported
TOML, resource edits, symlink replacement, parent traversal and incompatible runtimes.
The version-negative tests simulate interpreter identity; positive characterization
always runs the actual interpreter/parser. CI now runs this corpus on every supported
minor line and separately on `/usr/bin/python3` used by the Java integration.

Build #835 ran Ubuntu 24.04.5 and `setup-python` CPython 3.11.17. That setup changes
PATH, not the Java adapter's hardcoded `/usr/bin/python3`. Its log did not print the
system interpreter patch version. The three adapter errors came from requiring exactly
3.14.4 for resolution/manifests, while inventory lacked that gate. The Forge Agent CI
step now logs the exact system interpreter and tests it before Maven.

Deployment requires a supported, administrator-managed `/usr/bin/python3` with stdlib
`tomllib`, Linux no-follow/dir-FD filesystem operations and the existing runtime launcher
prerequisites. This change does not replace the installed interpreter or relax launcher
ownership, UID, mounts or sandbox policy. A different interpreter cannot be supplied in
a session/API request. Deployments on an unsupported line must install a characterized
system runtime or characterize an additional line before enabling native resolution.

## Boundary and ownership

`NativeAgentDefinitionPort` remains unchanged. `CodexNativeAgentDefinitionAdapter`
validates the private helper response and returns only `EffectiveAgentDefinition` and
the existing immutable resource map. Python JSON, interpreter details, paths in errors
and process handles do not escape through Domain or public API DTOs.

The internal `CodexAgentDefinitionReader` centralizes workspace validation, opaque
reference derivation, the single bundled helper and its process/protocol lifecycle.
Both the inventory adapter and definition adapter use it. No new application port,
execution engine or filesystem traversal implementation is introduced.

The process has a two-second budget with 250ms reserved for cleanup, bounded request
(128KiB), helper (16KiB) and response (1MiB), isolated environment and discarded stderr.
Timeout, cancellation, invalid/trailing JSON, oversized output and nonzero exit fail
closed. Owned processes are forcibly terminated and awaited; Java interruption is
preserved. Native runtime incompatibility maps explicitly to safe application conflict
`AGENT_DEFINITION_RUNTIME_UNSUPPORTED`, rather than being hidden as missing metadata.

Secure no-follow directory/file access, hardlink rejection, byte/entry/depth limits,
resource fingerprints and immutable snapshot comparison remain in force. Resource tree
entries are counted while enumerating, before sorting; malformed roots are rejected
before manifest expansion. Manifest values and resolved field types/identities are also
validated in Java. Existing snapshots are neither rewritten nor silently refreshed.

## Verification commands

```sh
/usr/bin/python3 -I -m unittest discover -s scripts/runtime/tests -p 'test_native_agent_reader.py' -v
python3 -m unittest discover -s scripts/runtime/tests -p 'test_*.py'
mvn -f services/forge-agent/pom.xml -Dapi.version=1.44 verify
git diff --check
```

`CodexAgentDefinitionReaderTest` exercises real helper processes for timeout,
interruption, cleanup, oversized output, invalid envelopes, safe diagnostic mapping
and traversal/input rejection. The original native definition tests verify snapshot
immutability, model/effort/instruction preservation and resource revocation.
