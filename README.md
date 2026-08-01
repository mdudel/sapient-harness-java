# sapient-harness-java

A **lightweight, cross-platform, pure-Java** SAPIENT test harness for
**BSI Flex 335 v2**.

Configurable **Receivers** (TCP servers) and **Transmitters** (TCP clients)
that speak the SAPIENT length-prefixed protobuf wire format, plus a JavaFX
desktop UI for interactive testing. Designed to help develop and test
SAPIENT-compliant edge nodes and fusion nodes without needing the full
dstl reference harness, Windows, PostgreSQL, or .NET.

> This project is a clean-room reimplementation. It reuses dstl's
> [SAPIENT-Proto-Files](https://github.com/dstl/SAPIENT-Proto-Files)
> verbatim and mirrors the wire framing from dstl's C# reference
> [BSI-Flex-335-v2-Test-Harness](https://github.com/dstl/BSI-Flex-335-v2-Test-Harness).
> No dstl code is copied; only the standardised protobuf definitions
> and the on-the-wire message framing.

Licence: **Apache-2.0** (matches dstl's licence terms).

---

## What is SAPIENT?

**SAPIENT** — *Sensors for Asset Protection using Integrated Electronic
Networked Technology* — is an open interoperability standard for
autonomous sensor systems, published by the UK Defence Science and
Technology Laboratory (dstl) and standardised by BSI as **BSI Flex
335 v2**.

The standard defines a protobuf message set and TCP wire framing that
lets independently-developed sensor nodes ("ASMs" — Autonomous Sensor
Modules) and fusion nodes discover each other, negotiate capabilities,
report status and coverage, deliver detections and tracks, and accept
tasking, all through a common vocabulary. The core message types are
`Registration` / `RegistrationAck` (capability declaration),
`StatusReport` (heartbeats with sensor location, power, and current
field of view), `DetectionReport` (per-target detections and tracks),
`Task` / `TaskAck` (command and control), and `Alert` / `AlertAck` /
`Error` (event notification).

For most operators, the essential picture is that a SAPIENT deployment
looks like a mesh of sensors talking to one or more fusion nodes over
TCP, exchanging length-prefixed protobuf messages. This harness lets
you stand up either side of that conversation from a single JAR, so
you can develop and test a SAPIENT-compliant node without waiting for
real hardware or a full reference deployment.

## Modules

- **`sapient-core`** — generated Java protobuf classes for BSI Flex 335
  v2, wire codec constants, message factories, motion and sensor-
  geometry helpers, JSON template loader (dstl format), and a message
  validator.
- **`sapient-net`** — Netty-based `SapientReceiver` (TCP server) and
  `SapientTransmitter` (TCP client) implementing the 4-byte little-
  endian length-prefix framing.
- **`sapient-cli`** — headless command-line runner for wire-compat smoke
  tests and CI regressions.
- **`sapient-ui`** — JavaFX desktop UI. This is what most operators use.

## Requirements

- **Java 17** (any distribution — Temurin, Zulu, Oracle, Corretto)
- **Maven 3.9+**
- (Optional for UI development) any IDE with Maven support; VS Code
  with the *Extension Pack for Java* works well.

The source builds on Windows, Linux, and macOS. Note the
platform-portability caveat on the resulting UI JAR in
[Cross-platform notes](#cross-platform-notes) below.

## Building

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

Both scripts wrap Maven, check your Java and Maven versions, and
report where the runnable JARs land. If you prefer plain Maven:

```bash
mvn clean install
```

The first build downloads dependencies (~150 MB) and runs `protoc` to
generate Java classes from the dstl `.proto` files under `proto/`.
Expect 2-4 minutes on a warm cache, 10+ on a cold cache.

## Running the UI

This is the primary interaction path. From a full checkout:

```bash
mvn -pl sapient-ui javafx:run
```

Or, after `mvn install`, from the fat JAR:

```bash
java -jar sapient-ui/target/sapient-ui-0.1.0-SNAPSHOT.jar
```

The UI opens with three tabs.

### Receivers tab

Configure TCP servers that accept SAPIENT connections and watch live
incoming traffic.

1. Enter a **name** (any short label) and a **port**, then click the
   **+** button to add the receiver row. The new row is auto-selected
   so subsequent actions land on it.
2. Click **▷ Play** on the row to start the receiver. Status text
   flips from red ("stopped") to green ("running"). If the port is in
   use you will see a red `error: …` status.
3. Every message the receiver decodes appears in the bottom panel
   with a timestamp, the sender's socket address, and a one-line
   validation summary.
4. Click the **cog** to edit the row's name or port. Saving stops the
   receiver; press Play when you are ready to resume.
5. Click the **trash** to delete the row.

Configured receivers persist to `~/.sapient-harness/session.json` and
come up stopped on next launch — no ports are reopened automatically.

### Transmitters tab

Configure TCP clients that connect to a remote SAPIENT receiver and
send messages, either one-shot or via a scheduled generator.

1. Enter **name**, **host**, and **port** and click **+** to add the
   row.
2. Click **▷ Play** on the row to connect. Status text turns green
   ("connected") when the socket is up; amber ("reconnecting") during
   a TCP client backoff; red otherwise.
3. Pick a **message type** from the dropdown above the row and click
   **Send**. A dialog opens to collect the type-specific fields; on
   OK the message is sent over the current connection.
4. Click the **cog** to edit the row (name, host, port, node UUID
   with a Regenerate button). Saving disconnects the transmitter;
   press Play when you are ready to reconnect with the new
   configuration.
5. Click the **trash** to delete the row.

Configured transmitters persist to `~/.sapient-harness/session.json`
along with each row's SAPIENT node UUID, so the middleware sees the
same node identity across restarts.

The **Detection Report** and **Sensor status** dropdown entries open
scheduled generators rather than one-shot dialogs — see below.

### Log tab

Application log tail (v0.2).

## Message types

All BSI Flex 335 v2 top-level messages can be sent from the UI.

| Type | Notes |
|---|---|
| Registration | Node type and name; all mandatory arrays populated with one entry. |
| RegistrationAck | Accept or reject, with an optional reason. |
| StatusReport | System state, mode, lat/lon/alt, power source and status, battery percent. Optional cone or polygon field-of-view attachment for one-shot coverage snapshots. |
| **Detection Report (generator…)** | Scheduled: N tracks around a centre point at a configurable rate, with optional random-walk motion and altitude behaviour. See below. |
| **Sensor status (generator…)** | Scheduled: heartbeats a `StatusReport` with a live-updating field of view (cone or ground-projected polygon), stationary or moving platform. See below. |
| Task | Control (START / STOP / PAUSE), task name, description. |
| TaskAck | Task ID with accept or reject and optional reason. |
| Alert | Type, status, priority, description, lat/lon/alt. |
| AlertAck | Alert ID with accept or reject and optional reason. |
| Error | Free-text error message. |

Default lat/lon: Wiesbaden (`50.0782, 8.2398`) — all fields editable.

## Generators

Generators run inside a selected transmitter row and stream messages
until stopped. A row can run a detection generator and a sensor
generator concurrently. A single **stop-generators** button on the
transmitters tab halts every generator on the selected row; choosing
the same generator type twice on a row is refused until you stop the
first one.

### Detection Report generator

Simulates a fleet of tracked objects around a centre point.

- **N tracks** placed randomly inside a circle of R metres around a
  configurable centre.
- One `DetectionReport` per track per tick, at the configured
  interval (default 1000 ms). Each track has a stable `object_id`
  across ticks so downstream consumers see continuous tracks rather
  than one-shot detections.
- **Motion** (toggle): each tick, each track advances
  `speed_m_s × (tick_ms / 1000)` metres in its current heading.
  Heading wobbles by up to ±*turn-jitter*° per tick (default 15°).
  Tracks that drift beyond 3× the configured radius are gently
  steered back toward the centre.
- **Altitude** (optional): each track can be given an initial
  altitude (WGS84 metres) with a per-track jitter applied at seed
  time and, optionally, a per-tick vertical drift rate. A configurable
  floor keeps every track above the specified minimum altitude at all
  times; leaving all altitude fields at zero produces messages without
  a `Location.z` field for backwards compatibility with older
  receivers.

### Sensor status generator

Emits `StatusReport` heartbeats with a live field of view. Complements
the detection generator: the detection generator says "here are things
I have detected"; the sensor generator says "here I am, and here is
the volume I am currently observing". They pair naturally in a demo.

- **FOV mode** (dropdown): **CONE** or **POLYGON**.
  - CONE: sends a `RangeBearingCone` whose boresight azimuth sweeps
    at the configured degrees per second (positive = clockwise,
    negative = counter-clockwise, wrap-around supported), elevation
    ping-pongs between the configured minimum and maximum at the
    nod rate, and horizontal/vertical extents stay fixed.
  - POLYGON: projects the cone's ground footprint each tick as a
    triangular fan polygon with the sensor at the apex and an arc
    of vertices at the configured range along the swept boresight.
    Vertex count is configurable.
- **Platform** (toggle): stationary or moving. When moving, the
  sensor's reported location advances every tick using the same
  motion model as the detection generator, and the cone datum is
  reported as `PLATFORM` rather than `TRUE` so the receiver knows
  azimuth is platform-relative.
- **Tick rate**, **azimuth rotation rate**, **elevation nod rate**,
  **range**, **extents**, **battery drain**, and **system state**
  are all configurable.
- Info flag: first tick emits `INFO_NEW`, subsequent ticks emit
  `INFO_UNCHANGED` per SAPIENT semantics.

## Themes

Flat and material themes via
[AtlantaFX](https://github.com/mkpaz/atlantafx).
Use **View → Toggle Dark / Light** (`Ctrl+D` / `Cmd+D`) for a quick
flip, or **View → &lt;theme name&gt;** for the full picker:

- **Light:** Primer Light (GitHub-style, default), Nord Light,
  Cupertino Light
- **Dark:** Primer Dark, Nord Dark, Cupertino Dark, Dracula

Your choice is persisted at `~/.sapient-harness/session.json` so the
UI comes up the same way next launch.

Semantic colours (`success`, `danger`, `warning`) are pulled from the
active theme's palette, so status text and buttons stay harmonious in
every theme.

## Running the CLI

For headless smoke tests and CI regressions:

```bash
# Terminal 1 — start a receiver on port 12000
java -jar sapient-cli/target/sapient-cli-0.1.0-SNAPSHOT.jar receive --port 12000

# Terminal 2 — send a synthesised Registration to it
java -jar sapient-cli/target/sapient-cli-0.1.0-SNAPSHOT.jar send \
    --host 127.0.0.1 --port 12000

# Or send from a JSON template file (dstl-compatible format):
java -jar sapient-cli/target/sapient-cli-0.1.0-SNAPSHOT.jar send \
    --host 127.0.0.1 --port 12000 \
    --template my-registration.json
```

## Cross-platform notes

The Java bytecode in every module is portable, but the JavaFX runtime
depends on native rendering libraries (`.so`, `.dll`, `.dylib`) that
are OS-specific. Maven pulls whichever platform's JavaFX natives match
the **build host**, and those are the natives baked into the fat UI
JAR.

Practically: a UI JAR built on Linux runs on Linux; a UI JAR built on
Windows runs on Windows; a UI JAR built on macOS runs on macOS. The
non-UI modules (`sapient-core`, `sapient-net`, `sapient-cli`) have no
native dependencies and run anywhere.

If you need one UI JAR that runs on all three platforms, build it on
each target OS separately, or add all-platform JavaFX classifiers to
the `sapient-ui` POM so a single fat JAR contains every platform's
natives.

## Wire format (for integration work)

Every SAPIENT message on the TCP wire is:

```
┌──────────────────┬──────────────────────────────────┐
│  4-byte length   │  protobuf-encoded SapientMessage │
│  (little-endian) │  (that length in bytes)          │
└──────────────────┴──────────────────────────────────┘
```

- No sync marker, no delimiter, no null termination.
- Length is an unsigned int32 (little-endian), excludes the header
  itself.
- Payload is a fully-serialised `SapientMessage` (see
  `proto/sapient_msg/bsi_flex_335_v2_0/sapient_message.proto`), which
  wraps a `oneof` of Registration / RegistrationAck / StatusReport /
  DetectionReport / Task / TaskAck / Alert / AlertAck / Error.

This matches dstl's reference implementation exactly — see
`SAPIENTMessageProcessor/ByteDataMessageBuilder.cs` in the reference
C# harness (`AddHeader` and `ProcessReceivedData`). Framing is done in
Netty via
`LengthFieldBasedFrameDecoder(LITTLE_ENDIAN, MAX, 0, 4, 0, 4)`.

## Verifying wire compatibility with the dstl reference harness

The dstl reference C# harness is Windows-only. To do a wire-compat
smoke test:

1. Build and run dstl's reference `SapientDataAgent` on a Windows box.
2. On any machine, run
   `sapient-cli send --host <windows-ip> --port 14000 --template <a-registration>`.
3. The dstl DA should log receipt of a valid Registration message.
4. Reverse: run `sapient-cli receive --port 14000` on the Java host,
   point a dstl ASM simulator at it, watch messages flow.

If both directions work, the two implementations are wire-compatible.

## Project layout

```
sapient-harness-java/
├── pom.xml                      ← parent (dependency + plugin management)
├── proto/                       ← dstl SAPIENT-Proto-Files (verbatim)
│   └── sapient_msg/
│       ├── proto_options.proto
│       └── bsi_flex_335_v2_0/*.proto
├── sapient-core/                ← protobuf gen + wire codec + validation
│                                    + generators (Detection, Sensor)
├── sapient-net/                 ← Netty server + client
├── sapient-cli/                 ← headless CLI (picocli)
└── sapient-ui/                  ← JavaFX desktop UI
```

## Roadmap

- **v0.1 (current)** — core + net + cli + UI; per-row edit and
  persistence; detection generator with altitude; sensor generator
  with cone / polygon FOV, stationary and moving platform; one-shot
  StatusReport with optional cone or polygon FOV.
- **v0.2** — scenario save/load (JSON, alongside the existing
  per-session persistence), scheduled/repeated send in the UI,
  broadcast from receiver, full log tail in the UI, echo command in
  the CLI, running-generators tray with per-generator inspection.
- **v0.3** — full dstl validator suite ported (all 30 field-level
  validators); pcap capture and replay; native installers via
  `jpackage`.

## Contributing

Issues and pull requests welcome. Please target the `main` branch.
Keep commits signed off if you are able (DCO-style).

## Credits

- **dstl / UK MOD** — for the BSI Flex 335 specification, the
  protobuf definitions, and the reference C# implementation that this
  harness was reverse-engineered from.
- **BSI** — for the standard itself.

## Licence

Apache-2.0. See [LICENSE](LICENSE).

The dstl `.proto` files under `proto/` are Crown Copyright and are
distributed under their own Apache-2.0 licence — see the header of
each `.proto` file.
