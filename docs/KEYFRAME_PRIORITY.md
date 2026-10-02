Ported from irlserver/srtla_send v4.1.0 docs (MIT); adapted for the Kotlin/JVM port.

# Keyframe priority sidecar

srtla_send_kotlin offers two complementary ways to treat keyframe / parameter-set packets as critical and route them to the most reliable link:

1. A packet-size heuristic (built into the sender) that watches for runs of max-MTU packets and declares a burst when 5 or more land in a row.
2. An out-of-band UDP sidecar where an upstream encoder explicitly opens a short "critical window".

The two are OR-combined. An encoder that knows it is about to emit a keyframe opens a window; the heuristic keeps catching bursts on its own when no encoder feedback is available.

## Why a sidecar UDP, not the JSON-RPC control socket

The JSON-RPC control socket rides a separate path from SRT packets. A hint that arrives microseconds after the packets it describes misses them entirely. Sharing the network stack with the data (UDP loopback, same socket discipline on srtla_send) keeps hints ordered tightly against the packets they describe.

## Wire format

One 5-byte UDP datagram per request:

```
byte 0     : 0xC1              — magic tag (Critical v1)
bytes 1..4 : u32 big-endian    — window length in milliseconds
```

srtla_send stores `now + window_ms` as the current critical deadline.
`isCriticalNow()` returns true while `now < deadline`.

Overlapping windows extend the deadline monotonically (max semantics). A late datagram referring to an earlier deadline is ignored — it can never shrink an active window.

## Enabling

Pass `--priority-bind ADDR:PORT` to srtla_send_kotlin:

```
java -jar srtla_send_kotlin.jar --priority-bind 127.0.0.1:7000 \
           --control-socket /tmp/srtla.sock \
           6000 rec.example.com 5000 /tmp/uplinks
```

Or with gradle:

```
./gradlew run --args='--priority-bind 127.0.0.1:7000 6000 rec.example.com 5000 /tmp/uplinks'
```

The sender (encoder or test harness) binds any local UDP socket, connects to that address, and sends 5-byte datagrams when a keyframe is emitted. Any loopback UDP datagram that doesn't match the magic byte and length is counted as malformed (visible in `get_status` as `critical_malformed_datagrams`).

## Picking a window length

A window of 30–80 ms covers a typical keyframe burst at 24–60 fps. Err on the high side — marking a couple of non-keyframe trailing packets critical is harmless; missing the last keyframe packet is not. The default used by most encoders is 50 ms.

## Telemetry

`get_status` exposes two counters:

```json
{
  "critical_windows_received": 142,
  "critical_malformed_datagrams": 0
}
```

`critical_malformed_datagrams > 0` almost always means a mismatched magic byte (version skew) or a sender writing short datagrams.

## Java/Kotlin sender snippet

To send a keyframe priority hint from a test harness or encoder:

```kotlin
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

fun sendKeyframePriority(priorityAddr: String, priorityPort: Int, windowMs: Int) {
    val sock = DatagramSocket()
    try {
        // 5-byte datagram: 0xC1 + u32 big-endian window_ms
        val buf = ByteArray(5)
        buf[0] = 0xC1.toByte()
        buf[1] = (windowMs shr 24).toByte()
        buf[2] = (windowMs shr 16).toByte()
        buf[3] = (windowMs shr 8).toByte()
        buf[4] = windowMs.toByte()
        
        val packet = DatagramPacket(buf, buf.size, InetAddress.getByName(priorityAddr), priorityPort)
        sock.send(packet)
    } finally {
        sock.close()
    }
}

// Example: call when encoder emits keyframe
sendKeyframePriority("127.0.0.1", 7000, 50)
```

Or in plain Java:

```java
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class KeyframePriority {
    public static void sendKeyframePriority(String priorityAddr, int priorityPort, int windowMs) throws Exception {
        DatagramSocket sock = new DatagramSocket();
        try {
            byte[] buf = new byte[5];
            buf[0] = (byte) 0xC1;
            buf[1] = (byte) (windowMs >> 24);
            buf[2] = (byte) (windowMs >> 16);
            buf[3] = (byte) (windowMs >> 8);
            buf[4] = (byte) windowMs;
            
            DatagramPacket packet = new DatagramPacket(buf, buf.size, 
                InetAddress.getByName(priorityAddr), priorityPort);
            sock.send(packet);
        } finally {
            sock.close();
        }
    }
    
    public static void main(String[] args) throws Exception {
        sendKeyframePriority("127.0.0.1", 7000, 50);
    }
}
```

## Testing with netcat

Send a priority window from the command line (50 ms window):

```bash
# Using printf + xxd to build the 5-byte datagram
printf '\xc1\x00\x00\x00\x32' | nc -u 127.0.0.1 7000
```

Or using Python:

```python
import socket

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
buf = bytes([0xc1, 0x00, 0x00, 0x00, 0x32])  # 0xC1 + 50 ms
sock.sendto(buf, ("127.0.0.1", 7000))
```
