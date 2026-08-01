# Roadmap

## v0.1 — Foundation (this release)

- ✅ Multi-module Maven project (Java 17)
- ✅ `.proto` files from dstl SAPIENT-Proto-Files (v2)
- ✅ Wire codec: 4-byte little-endian length-prefix framing (Netty)
- ✅ `SapientReceiver` (TCP server) — accepts N clients per port
- ✅ `SapientTransmitter` (TCP client) — bidirectional (sends and receives replies)
- ✅ JSON template loader (dstl-compatible format via `JsonFormat`)
- ✅ Minimum-viable validator (mandatory fields + UUID format)
- ✅ CLI: `receive`, `send` sub-commands
- ✅ JavaFX UI: 3 tabs (Receivers, Transmitters, Log)
- ✅ Round-trip integration test (JUnit 5, real sockets, ephemeral port)

## v0.2 — Usability

- [ ] Scenario save/load (JSON) — persist a full config of receivers +
      transmitters + templates + schedules
- [ ] Broadcast/reply support in `SapientReceiver` (ChannelGroup)
- [ ] Scheduled repeated send in UI (send every N ms)
- [ ] Global log tail in Log tab (bind Log4j2 appender)
- [ ] `echo` CLI sub-command (server that echoes incoming messages back)
- [ ] Message inspector: tree view of any received message's fields
- [ ] Configurable message filtering (only display certain content types)

## v0.3 — Depth

- [ ] Full dstl validator suite ported (all 30 field-level validators
      from `SapientServices/Data/Validation/*.cs`)
- [ ] Pcap capture: record every received message with framing bytes
      for regression / bug-report attachments
- [ ] Pcap replay: feed a recorded session back into a receiver as if
      it came from the wire
- [ ] jpackage native installers: `.msi` (Windows), `.dmg` (macOS),
      `.deb` (Linux)
- [ ] GitHub Actions CI: build + test on Linux/macOS/Windows matrices

## Beyond

- [ ] gRPC-service-style peer discovery (if the SAPIENT community
      standardises on one)
- [ ] Optional TLS wrapping via Netty SslContext
- [ ] "Scripted scenario" DSL — a sequence of send-then-expect steps
      with pass/fail verdicts, for regression suites
- [ ] Prometheus / OpenTelemetry metrics endpoint
