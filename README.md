# Real-Time Audio Streaming over IP

Java-based VoIP client for the **NSCOM01 MCO2** project. The application uses:

- **SIP over UDP** for call signaling
- **SDP** for simple session description
- **RTP over UDP** for audio streaming
- **RTCP over UDP** for basic sender reports

It supports three call modes:

- `file` - stream `sample_audio.wav`
- `mic` - one-way live microphone streaming
- `twoway` - two-way microphone communication

## Project Files

```text
MC02/
|-- VoIPClient.java
|-- sample_audio.wav
|-- README.md
`-- NSCOM01_MP2_Project Specifications.pdf
```

## Audio Format

The client uses:

- PCM
- 8000 Hz
- 16-bit
- Mono

## Features

- SIP-style `INVITE`, `200 OK`, `ACK`, and `BYE` signaling
- SDP payload included in call setup messages
- RTP packet construction over UDP
- RTCP sender reports every 5 seconds
- Localhost profile selection to avoid port conflicts on one machine
- Basic malformed-packet handling for stability testing

## Port Profiles

To make same-machine testing easier, the app provides two localhost profiles:

| Profile | SIP | RTP | RTCP |
| --- | ---: | ---: | ---: |
| A | 5060 | 8000 | 8001 |
| B | 5062 | 8002 | 8003 |

When using two terminals on one computer:

- Terminal 1: choose **Profile B**
- Terminal 2: choose **Profile A**

## Requirements

- Java JDK 8 or newer
- A microphone and speakers for `mic` or `twoway` mode
- Two terminal windows for localhost testing
- Optional: Wireshark for packet verification

## Compile and Run

From the project directory:

```bash
javac VoIPClient.java
java VoIPClient
```

When prompted, choose:

- `1` for **Profile A**
- `2` for **Profile B**

## Available Commands

```text
call <IP> file
call <IP> mic
call <IP> twoway
hangup
garbage
quit
```

## Localhost Testing

Use two terminals on the same computer.

### 1. Start the receiver

In the first terminal:

```bash
java VoIPClient
```

Select:

```text
2
```

This starts **Profile B** on ports `5062 / 8002 / 8003`.

### 2. Start the caller

In the second terminal:

```bash
java VoIPClient
```

Select:

```text
1
```

This starts **Profile A** on ports `5060 / 8000 / 8001`.

### 3. Run test calls

Use Terminal 2 and call:

```bash
call 127.0.0.1 file
```

or

```bash
call 127.0.0.1 mic
```

or

```bash
call 127.0.0.1 twoway
```

## Test Scenarios

### A. File Streaming

Command:

```bash
call 127.0.0.1 file
```

Expected behavior:

- SIP handshake completes with `INVITE`, `200 OK`, and `ACK`
- `sample_audio.wav` is streamed over RTP
- The receiving side plays the audio through speakers
- The call ends automatically when the file finishes

### B. One-Way Microphone Streaming

Command:

```bash
call 127.0.0.1 mic
```

Expected behavior:

- Caller captures live microphone input
- Audio is streamed to the receiver
- Receiver plays the incoming stream
- Use `hangup` to end the call

### C. Two-Way Communication

Command:

```bash
call 127.0.0.1 twoway
```

Expected behavior:

- Both clients open microphone and speaker lines
- Audio flows in both directions
- You may hear slight delay or echo during local testing
- Use `hangup` to end the call

### D. Error Handling

Command:

```bash
garbage
```

Expected behavior:

- A malformed message is sent to the SIP port
- The receiver ignores it safely
- The program continues running without crashing

## Two-Computer Testing

### 1. Connect both computers to the same network

- Use the same Wi-Fi or LAN
- Allow Java through the OS firewall if prompted
- If audio or signaling fails, verify UDP traffic is not being blocked

### 2. Find the receiver IP address

Run the client and look for:

```text
Client initialized on IP: <your-ip-address>
```

Example:

```text
Client initialized on IP: 192.168.1.50
```

### 3. Start each side

- Computer A (caller): choose **Profile A**
- Computer B (receiver): choose **Profile B**

Then call the receiver using its real local network IP:

```bash
call 192.168.1.50 twoway
```

You can also replace `twoway` with `file` or `mic`.

## Wireshark Verification

For localhost testing, capture on:

- **Windows:** Loopback adapter
- **macOS / Linux:** `lo0` or loopback interface

### SIP and RTP traffic

Use this display filter:

```text
udp.port in {5060, 5062, 8000, 8001, 8002, 8003}
```

You should see:

- SIP text messages such as `INVITE`, `200 OK`, and `ACK`
- RTP packets flowing between the RTP ports

### RTCP traffic

Use this filter:

```text
udp.port == 8001 || udp.port == 8003
```

Then:

1. Start a call.
2. Wait at least 10 seconds.
3. Open one of the UDP packets in Wireshark.
4. Inspect the packet data payload.

You should find text similar to:

```text
RTCP Sender Report - Packet Count Approx: 250
```

The value increases as more reports are sent.

## Notes

- `sample_audio.wav` must be present in the same directory as `VoIPClient.java` when using `file` mode.
- Localhost two-way mode may produce echo because both sides run on the same machine.
- The RTP receiver socket uses a timeout so the app can exit the media loop cleanly when the call ends.

## Reference

Project specification:

- `NSCOM01_MP2_Project Specifications.pdf`
