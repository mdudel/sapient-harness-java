# SAPIENT Connection & Hand-shake (BSI Flex 335 v2.0)

_A concise, implementation-oriented walk-through of how two SAPIENT peers – typically an **edge
node** (sensor / effector) and a **fusion node** – discover each other, register, and exchange their first
messages._

This note distils Clauses 4 & 6 of **BSI Flex 335 v2.0 (March 2024)** and cross-checks every step
against the reference dstl C# harness and the open-source Java harness in
[`sapient-harness-java`](https://github.com/mdudel/sapient-harness-java).

---

## 1  Transport & Framing

| Item | Requirement | Source |
|------|-------------|--------|
| Underlying protocol | **TCP/IP** – edge node acts as **client**, fusion node (or message handler) acts as **server**. | §4.1 |
| Time sync | All devices **MUST** sync to a common NTP source. | §4.1 |
| Encoding | **Protocol Buffers v3**. | §4.1 |
| Message delimiter | Each serialized `SapientMessage` **MUST** be prefixed by a **4-byte little-endian unsigned length** (payload only). | §4.2 |
| Multiple messages | Frames may be concatenated back-to-back on the same stream. | §4.2 |
| Max frame length | _Not fixed by the standard_; dstl reference harness limits to ≈ 10 MiB. | Implementation detail |

---

## 2  `SapientMessage` Wrapper

Every wire frame is a `SapientMessage` (Table 1). Mandatory fields:

```protobuf
message SapientMessage {
  google.protobuf.Timestamp timestamp = 1; // UTC when content was generated
  string                    node_id   = 2; // sender UUID
  string                    destination_id = 3; // optional
  oneof content {                         // exactly one of:
    Registration     registration      = 4;
    RegistrationAck  registration_ack  = 5;
    StatusReport     status_report     = 6;
    DetectionReport  detection_report  = 7;
    Task             task              = 8;
    TaskAck          task_ack          = 9;
    Alert            alert             = 10;
    AlertAck         alert_ack         = 11;
    Error            error             = 12;
  }
  string additional_information = 13; // discretionary
}
```

_Unspecified (zero) enum values are **invalid** for mandatory fields (§4.2 Note 3)._  
`node_id` **MUST** be a RFC-4122 UUID.

---

## 3  Connection & Initialisation Sequence

The **edge node drives** the sequence (Initialization, §4.4). No messages other than
`Registration` may be sent until a `RegistrationAck` (or `Error`) is received (6.2.2).

```
Edge Node                                            Fusion Node / Message Handler
──────────                                           ─────────────────────────────
TCP connect  ──────────────────────────────────────► (accept)

Registration ──────────────────────────────────────►
             (Table 15, §6.2)                        – Validate       – Store node record
                                                     – Decide accept/reject

                                      RegistrationAck ◄───────────────
                                      (Table 16, §6.2.2)

StatusReport (initial) ────────────────► (§6.3.1)     – Mark node “alive”

[heartbeat]
StatusReport ─────────────────────────►              – Update state
[telemetry]
DetectionReport ─────────────────────►               – Fuse / process detections

… (optional Alert, Task / TaskAck traffic)
```

### 3.1  Registration (edge → fusion)

* First message after TCP connect.
* **Mandatory** fields (Table 15):
  * `node_definition[]`, `ICD_version` (`"BSI Flex 335 v2.0"`), `capabilities[]`,
    `status_definition` (contains `status_interval`), `mode_definition[]`, `config_data[]`.
* May include `dependent_nodes[]`, `reporting_region[]` for platforms/hierarchies.

### 3.2  RegistrationAck (fusion → edge)

* Boolean `acceptance` **MUST** be present (defaults to `false` in proto3 – set it `true` on accept).
* `destination_id` **MUST** be populated with the registering node’s UUID (Table 1).
* On reject, include one or more `ack_response_reason` strings.
* Edge **MUST** wait for this message (or an `Error`) before sending anything else.

### 3.3  Initial StatusReport (edge → fusion)

* Sent **immediately after** receiving a successful `RegistrationAck` (6.3.1).
* Should include current `node_location` & `field_of_view` if available.

### 3.4  Steady State

| Direction | Message | Cadence / Trigger | Notes |
|-----------|---------|-------------------|-------|
| edge → fusion | **StatusReport** | Every `status_interval` declared in Registration. | If 3× intervals pass with no status, fusion **MUST** close the socket (6.3.2.1). |
| edge → fusion | **DetectionReport** | Event-driven (after initial status). | Object/localisation data. |
| fusion → edge | **Task** | Operator / automation. | See §6.5. |
| edge → fusion | **TaskAck** | Immediate reply to Task. | Table 86. |
| either way | **Alert / AlertAck** | Event-driven. | If alert requires response, recipient replies with AlertAck (Table 93). |
| either way | **Error** | On malformed or semantically invalid message. | Terminates processing of offending msg. |

### 3.5  Graceful Shutdown & Reconnect

* **Shutdown**: node sends `StatusReport.system = GOODBYE` before clean disconnect (§4.8).
* **Lost link**: nodes monitor the TCP session (§4.9). If disconnected they:
  * Attempt to reconnect every **10 s**.
  * If reconnection succeeds within **2 min**, **re-registration is _not_ required**.
  * If downtime > 2 min, reconnect **and resend Registration**.

---

## 4  Common Implementation Pitfalls & Clarifications

* **Length-prefix endianness** is little-endian regardless of host.
* **`acceptance` must be set**: an all-default `RegistrationAck` encodes `acceptance=false` → rejection.
* **Initial StatusReport precedes any DetectionReport** – contrary to some informal code examples.
* `node_location` is **mandatory** for sensors that report detections in `RangeBearing` coordinates (§6.3.1 Note 1 & 2).
* `status_interval` is a `Duration` (units enum + float value); convert to absolute milliseconds/seconds before timers.
* Use the **same `node_id`** when reconnecting _within 2 min_; generate a new UUID only for fresh node instances.
* The standard does **not** specify TLS or authentication – deploy inside a trusted network or wrap externally.

---

## 5  Minimal Pseudocode – Edge Node

```python
sock = tcp_connect(fusion_host, port)

send(registration())                    # build per Table 15
ack = recv()
assert ack.content == 'registration_ack' and ack.acceptance

send(status_report(initial=True))       # initial status

while connected:
    every status_interval:
        send(status_report())
    for detection in sensor.poll():
        send(detection_report(detection))
    for msg in recv_all():
        if msg.is_task():
            handle_task(msg)
        elif msg.is_alert_ack():
            handle_alert_ack(msg)
        elif msg.is_error():
            log_error(msg)
```

---

## 6  Reference Implementations

* **dstl:** [`BSI-Flex-335-v2-Test-Harness`](https://github.com/dstl/BSI-Flex-335-v2-Test-Harness) – C#
* **Java:** [`sapient-harness-java`](https://github.com/mdudel/sapient-harness-java) – cross-platform harness used to validate this document.

---

> **License**: This summary paraphrases publicly available information from
> _BSI Flex 335 v2.0:2024-03 – "SAPIENT network of autonomous sensors and effectors – interface
> control document – Specification"_. It is provided for ease of developer reference and does **not**
> supersede the official standard.
