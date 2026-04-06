# Real-Time Audio Streaming over IP

Java-based VoIP client for the **NSCOM01 MCO2** project. The application uses:

- **SIP over UDP** for call signaling
- **SDP** for session negotiation
- **RTP over UDP** for audio streaming
- **RTCP over UDP** for standards-based Sender Reports

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

- PCM linear audio
- 8000 Hz
- 16-bit
- Mono

## Features

- SIP `INVITE`, `200 OK`, `ACK`, and `BYE` signaling with stronger SIP headers
- SDP offer/answer exchange that negotiates remote RTP and RTCP ports
- RTP packet construction with sequence number, timestamp, and SSRC
- RTCP Sender Reports every 5 seconds using the standard RTCP SR packet format
- Built-in localhost profiles plus custom user-defined port profiles
- Menu-driven console interface with legacy command support
- Graceful call teardown that closes media sockets and reopens clean ones for the next call

## Port Profiles

The app includes two built-in profiles for same-machine testing:

| Profile | SIP | RTP | RTCP | Default Remote SIP |
| --- | ---: | ---: | ---: | ---: |
| A | 5060 | 8000 | 8001 | 5062 |
| B | 5062 | 8002 | 8003 | 5060 |

You can also choose **Custom** at startup and define:

- Local SIP port
- Local RTP port
- Local RTCP port
- Default remote SIP port

Important:

- SIP still uses the destination IP and SIP port you dial
- RTP and RTCP destination ports are learned from the remote SDP body

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

At launch, choose:

- `1` for **Profile A**
- `2` for **Profile B**
- `3` for **Custom**

## Interface

After startup, the program shows a menu:

```text
1. Start a call
2. Hang up
3. Show session status
4. Send malformed SIP packet
5. Show help
6. Quit
```

It also supports legacy commands:

```text
call <IP> <sipPort> <file|mic|twoway>
hangup
status
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

Choose:

```text
2
```

This starts **Profile B** on:

- SIP `5062`
- RTP `8002`
- RTCP `8003`

### 2. Start the caller

In the second terminal:

```bash
java VoIPClient
```

Choose:

```text
1
```

This starts **Profile A** on:

- SIP `5060`
- RTP `8000`
- RTCP `8001`

### 3. Start a call

From the caller, either use the menu:

```text
1
Remote IP or hostname: 127.0.0.1
Remote SIP port [5062]:
Call mode (file/mic/twoway): file
```

or use the legacy command:

```bash
call 127.0.0.1 5062 file
```

For other tests:

```bash
call 127.0.0.1 5062 mic
call 127.0.0.1 5062 twoway
```

## Test Scenarios

### A. File Streaming

Command:

```bash
call 127.0.0.1 5062 file
```

Expected behavior:

- SIP handshake completes with `INVITE`, `200 OK`, and `ACK`
- SDP negotiates the receiver RTP and RTCP ports
- `sample_audio.wav` is streamed over RTP
- RTCP Sender Reports are transmitted every 5 seconds
- The receiving side plays the audio through speakers
- The call ends automatically when the file finishes

### B. One-Way Microphone Streaming

Command:

```bash
call 127.0.0.1 5062 mic
```

Expected behavior:

- Caller captures live microphone input
- Receiver advertises media ports through SDP
- Audio is streamed to the negotiated RTP port
- Receiver plays the incoming stream
- Use `hangup` to end the call

### C. Two-Way Communication

Command:

```bash
call 127.0.0.1 5062 twoway
```

Expected behavior:

- Both clients exchange SDP
- Both clients open microphone and speaker lines
- Audio flows in both directions
- RTCP Sender Reports are sent by both sides
- Use `hangup` to end the call

### D. Error Handling

Command:

```text
4
```

or:

```bash
garbage
```

Expected behavior:

- A malformed message is sent to the most recent SIP destination
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

- Computer A: choose any local profile
- Computer B: choose any local profile
- Make sure the caller knows the receiver's **SIP port**

Then call the receiver using its real local network IP and SIP port:

```bash
call 192.168.1.50 5062 twoway
```

You can replace `twoway` with `file` or `mic`.

RTP and RTCP will be learned from the receiver's SDP response.

## Wireshark Verification

For localhost testing, capture on:

- **Windows:** Npcap Loopback Adapter / loopback interface
- **macOS / Linux:** `lo0` or the loopback interface

### SIP signaling

Use:

```text
sip
```

You should see:

- `INVITE`
- `200 OK`
- `ACK`
- `BYE`

You can also inspect the SDP body inside `INVITE` and `200 OK`.

Things to verify:

- `m=audio <port>` advertises the RTP port
- `a=rtcp:<port>` advertises the RTCP port
- `a=rtpmap:96 L16/8000/1` advertises the codec

### RTP media

Use:

```text
rtp
```

If Wireshark does not auto-detect RTP, use:

```text
udp.port == 8000 || udp.port == 8002
```

or your custom RTP ports, then right-click a packet and choose:

```text
Decode As... -> RTP
```

You should see:

- RTP packets on the negotiated media ports
- Sequence numbers increasing
- Timestamps increasing
- A consistent SSRC value per media stream

### RTCP Sender Reports

Use:

```text
rtcp
```

or filter by the negotiated RTCP ports:

```text
udp.port == 8001 || udp.port == 8003
```

You should now see **real RTCP Sender Reports**, not ASCII text payloads.

Things to verify:

- Version `2`
- Packet type `200` for Sender Report
- SSRC present
- Sender's packet count increases over time
- Sender's octet count increases over time

### Combined filter

For the default localhost profiles, this filter captures the whole demo:

```text
udp.port == 5060 || udp.port == 5062 || udp.port == 8000 || udp.port == 8001 || udp.port == 8002 || udp.port == 8003
```

## Notes

- `sample_audio.wav` must be present in the same directory as `VoIPClient.java` when using `file` mode
- Localhost two-way mode may produce echo because both sides run on the same machine
- The RTP receiver socket uses a timeout so the app can exit the media loop cleanly when the call ends
- During teardown, media sockets are closed and reopened so the next call starts from a clean state

## Reference

Project specification:

- `NSCOM01_MP2_Project Specifications.pdf`
