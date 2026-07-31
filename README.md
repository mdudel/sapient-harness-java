# sapient-harness-java

A **lightweight, cross-platform, pure-Java** SAPIENT test harness for
**BSI Flex 335 v2**.

Configurable **Receivers** (TCP servers) and **Transmitters** (TCP clients)
that speak the SAPIENT length-prefixed protobuf wire format, plus a JavaFX
desktop UI for interactive testing. Designed to help develop and test
SAPIENT-compliant edge nodes and fusion nodes (e.g. AlmondMalt) without
needing the full dstl reference harness, Windows, PostgreSQL, or .NET.

> This project is a clean-room reimplementation. It reuses dstl's
> [SAPIENT-Proto-Files](https://github.com/dstl/SAPIENT-Proto-Files)
> verbatim and mirrors the wire framing from dstl's C# reference
> [BSI-Flex-335-v2-Test-Harness](https://github.com/dstl/BSI-Flex-335-v2-Test-Harness).
> No dstl code is copied; only the standardised protobuf definitions
> and the on-the-wire message framing.

Licence: **Apache-2.0** (matches dstl's licence terms).

---

## What ye get

- **`sapient-core`** — generated Java protobuf classes for BSI Flex 335 v2,
  wire codec constants, JSON template loader (dstl format), and a message
  validator.
- **`sapient-net`** — Netty-based `SapientReceiver` (TCP server) and
  `SapientTransmitter` (TCP client) implementing the 4-byte little-endian
  length-prefix framing.
- **`sapient-cli`** — headless command-line runner. Handy for wire-compat
  smoke tests and CI regressions.
- **`sapient-ui`** — JavaFX desktop UI. Configure receivers + transmitters,
  edit JSON templates, watch live traffic.

## Requirements

- **Java 17** (any distribution — Temurin, Zulu, Oracle, Corretto — all fine)
- **Maven 3.9+**
- (Optional for UI dev) any IDE with Maven support; VS Code with the
  *Extension Pack for Java* works fine.

Runs on **Windows, Linux, macOS** — same JAR everywhere.

## Build

```bash
git clone https://github.com/mdudel/sapient-harness-java.git
cd sapient-harness-java
```

Then use the platform build script:

**Windows:**
```powershell
build.bat           REM clean + install + test
build.bat fast      REM skip tests
build.bat run-ui    REM build then launch the UI
build.bat help      REM full options list
```

**Linux / macOS:**
```bash
./build.sh          # clean + install + test
./build.sh fast     # skip tests
./build.sh run-ui   # build then launch the UI
./build.sh help     # full options list
```

Both scripts wrap Maven, check yer Java + Maven versions,
and tell ye where the runnable JARs land. Prefer the raw
`mvn` incantation if ye like it:

```bash
mvn clean install
```

First build downloads dependencies (~150 MB) and runs `protoc` to
generate Java classes from the dstl `.proto` files under `proto/`.
Expect 2-4 minutes on a warm cache, 10+ on a cold cache.

## Run the CLI

```bash
# Terminal 1 — start a receiver on port 12000
java -jar sapient-cli/target/sapient-cli-*.jar receive --port 12000

# Terminal 2 — send a synthesised Registration to it
java -jar sapient-cli/target/sapient-cli-*.jar send --host 127.0.0.1 --port 12000

# Or send from a JSON template file (dstl-compatible format):
java -jar sapient-cli/target/sapient-cli-*.jar send \
    --host 127.0.0.1 --port 12000 \
    --template my-registration.json
```

## Run the UI

```bash
# From a full checkout:
mvn -pl sapient-ui javafx:run

# Or from the fat JAR after `mvn install`:
java -jar sapient-ui/target/sapient-ui-*.jar
```

Three tabs:

1. **Receivers** — add/start TCP servers, watch live incoming messages
   with per-message validation verdict.
2. **Transmitters** — add/connect TCP clients, edit a JSON template, send.
3. **Log** — global application log (v0.2).

## Wire format (for anyone integrating)

Every SAPIENT message on the TCP wire is:

```
┌──────────────────┬──────────────────────────────────┐
│  4-byte length   │  protobuf-encoded SapientMessage │
│  (little-endian) │  (that length in bytes)          │
└──────────────────┴──────────────────────────────────┘
```

- No sync marker, no delimiter, no null termination.
- Length is an unsigned int32 (little-endian), excludes the header itself.
- Payload is a fully-serialised `SapientMessage` (see
  `proto/sapient_msg/bsi_flex_335_v2_0/sapient_message.proto`), which
  wraps a `oneof` of Registration / RegistrationAck / StatusReport /
  DetectionReport / Task / TaskAck / Alert / AlertAck / Error.

This matches dstl's reference implementation exactly — see
`SAPIENTMessageProcessor/ByteDataMessageBuilder.cs` in the reference
C# harness (`AddHeader` and `ProcessReceivedData`). Framing is done in
Netty via `LengthFieldBasedFrameDecoder(LITTLE_ENDIAN, MAX, 0, 4, 0, 4)`.

## Verifying wire compatibility with the dstl reference harness

The dstl reference C# harness is Windows-only; a full step-by-step
build guide is preserved in a companion knowledge base. To do a
wire-compat smoke test:

1. Build + run dstl's reference `SapientDataAgent` on a Windows box.
2. On any machine, run `sapient-cli send --host <windows-ip> --port 14000
   --template <a-registration>`.
3. The dstl DA should log receipt of a valid Registration message.
4. Reverse: `sapient-cli receive --port 14000` on the Java host, point a
   dstl ASM Simulator at it, watch messages flow.

If both directions work, ye're wire-compatible.

## Project layout

```
sapient-harness-java/
├── pom.xml                      ← parent (dependency + plugin management)
├── proto/                       ← dstl SAPIENT-Proto-Files (verbatim)
│   └── sapient_msg/
│       ├── proto_options.proto
│       └── bsi_flex_335_v2_0/*.proto
├── sapient-core/                ← protobuf gen + wire codec + validation
├── sapient-net/                 ← Netty server + client
├── sapient-cli/                 ← headless CLI (picocli)
└── sapient-ui/                  ← JavaFX desktop UI
```

## Roadmap

- **v0.1 (this release)** — core + net + cli + minimal UI; single-message
  send/receive; basic validation; wire-compat spike.
- **v0.2** — scenario save/load (YAML), scheduled/repeated send in UI,
  broadcast from receiver, full log tail in UI, echo command in CLI.
- **v0.3** — full dstl validator suite ported (all 30 field-level
  validators); pcap capture/replay; native installers via jpackage.
- **v0.4** — BSI Flex 335 v1 protos (dstl proto repo has them, this
  project pins to v2 for now).

## Contributing

Issues, PRs welcome. Please target the `main` branch. Keep commits
signed off if ye're able (DCO-style).

## Credits

- **dstl / UK MOD** — for the BSI Flex 335 specification, the protobuf
  definitions, and the reference C# implementation that this harness
  was reverse-engineered from.
- **BSI** — for the standard itself.

## Licence

Apache-2.0. See [LICENSE](LICENSE).

The dstl `.proto` files under `proto/` are Crown Copyright and are
distributed under their own Apache-2.0 licence — see the header of each
`.proto` file.
