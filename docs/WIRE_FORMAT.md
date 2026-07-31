# SAPIENT Wire Format Reference

_Authoritative reference for anyone implementing a SAPIENT-compatible peer
in any language, based on reverse-engineering the dstl reference C# harness
and the BSI Flex 335 v2 protobuf definitions._

## Framing

Every SAPIENT message on TCP is framed as:

```
Offset  Length  Field
------  ------  --------------------------------------------------------
0       4       payload length in bytes, little-endian unsigned int32
4       N       fully-serialised SapientMessage protobuf payload
```

- `N = payload length`; the length field **excludes itself**.
- **Endianness is little-endian on the wire** on every host, regardless of
  the host's native byte order. dstl's C# uses `BitConverter.GetBytes(int)`
  with an explicit `Array.Reverse` on big-endian hosts, guaranteeing wire
  little-endian everywhere.
- **No sync marker, no start-of-frame byte, no delimiter, no null terminator.**
- Multiple messages may be sent back-to-back on the same connection. The
  receiver treats the stream as an infinite sequence of `[len][payload]`
  frames.
- Practical max frame length: dstl's reference caps at
  `SocketCommsCommon.MaximumPacketSize` (~10 MiB). This project uses 16 MiB
  as a slightly more generous default.

## Payload

The payload is a serialised `SapientMessage` protobuf as defined in
`proto/sapient_msg/bsi_flex_335_v2_0/sapient_message.proto`. Top-level shape:

```proto
message SapientMessage {
  optional google.protobuf.Timestamp timestamp = 1;   // mandatory
  optional string node_id = 2;                        // mandatory, UUID
  optional string destination_id = 3;                 // optional, UUID
  oneof content {                                     // mandatory: pick one
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
}
```

## Non-goals of this framing

- No compression is negotiated.
- No TLS on the wire (dstl's reference is plain TCP). Deploy inside a
  trusted network or wrap in stunnel / SSH tunnel if ye need transport
  security.
- No message-level authentication / signing.
- No back-pressure protocol beyond TCP's own flow control.

## Compatibility notes

- The dstl DA config field `sendNullTermination` refers to a **legacy
  XML-over-TCP path** that predates the protobuf wire. It has no effect
  on the framing described here.
- Some dstl components have separate ports for "tasking" vs "client"
  traffic (see `SDAClientPort`, `HDATaskingPort` in the reference
  configs). These are all the same wire format; they just represent
  logically-separated channels.

## Reference implementation snippets

**Encoding (Java):**
```java
byte[] payload = message.toByteArray();
buf.writeIntLE(payload.length);
buf.writeBytes(payload);
```

**Decoding (Netty):**
```java
new LengthFieldBasedFrameDecoder(
    ByteOrder.LITTLE_ENDIAN,
    16 * 1024 * 1024,   // max frame length
    0,                  // length field offset
    4,                  // length field length
    0,                  // length adjustment
    4);                 // bytes to strip (the header)
```

**Encoding (dstl C# — `ByteDataMessageBuilder.AddHeader`):**
```csharp
byte[] dataLengthInBytes = BitConverter.GetBytes(data.Length);
if (!BitConverter.IsLittleEndian) Array.Reverse(dataLengthInBytes);
return dataLengthInBytes.Concat(data).ToArray();
```

**Decoding (dstl C# — `ByteDataMessageBuilder.ProcessReceivedData`):**
Read 4 bytes → interpret as little-endian int32 → read that many bytes.
Repeat until buffer drained.
