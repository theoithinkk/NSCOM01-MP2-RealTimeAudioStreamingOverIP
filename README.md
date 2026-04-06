# Real-Time Audio Streaming over IP

Java-based VoIP client for the **NSCOM01 MCO2** project. The application uses:

- **SIP over UDP** for call signaling
- **SDP** for session negotiation
- **RTP over UDP** for audio streaming
- **RTCP over UDP** for standards-based Sender Reports

It supports three call modes:

- `file` - stream a chosen WAV file, defaulting to `sample_audio.wav`
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

- SIP `INVITE`, `180 Ringing`, `200 OK`, `ACK`, and `BYE` signaling with stronger SIP headers
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

### Sample device pairing

If one device uses the default Profile A settings, the other device should use the default Profile B settings.

| Device | Local SIP | Local RTP | Local RTCP | Default Remote SIP |
| --- | ---: | ---: | ---: | ---: |
| Device A | 5060 | 8000 | 8001 | 5062 |
| Device B | 5062 | 8002 | 8003 | 5060 |

This means:

- if Device A uses Profile A, Device B should use Profile B
- if Device B uses Profile B, Device A should use Profile A
- the `Default Remote SIP` value should point to the other device's SIP listening port

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
call <IP> <sipPort> <file|mic|twoway> [audioFile]
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
Audio file path [sample_audio.wav]:
```

or use the legacy command:

```bash
call 127.0.0.1 5062 file
```

To stream a different WAV file:

```bash
call 127.0.0.1 5062 file lecture.wav
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
- A provisional `180 Ringing` is sent before `200 OK`
- SDP negotiates the receiver RTP and RTCP ports
- The selected WAV file is streamed over RTP
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

### 3. Choose ports on each device

Example setup:

- Device A: Profile A
- Device B: Profile B

You may also use Custom on either side as long as:

- each device uses its own local SIP, RTP, and RTCP ports
- the caller knows the receiver's SIP port
- firewalls allow UDP traffic on those ports

Example custom setup:

- Device A: SIP `5070`, RTP `9000`, RTCP `9001`
- Device B: SIP `5072`, RTP `9100`, RTCP `9101`

### 4. Start each side

- Computer A: choose any local profile
- Computer B: choose any local profile
- Make sure the caller knows the receiver's **SIP port**

Then call the receiver using its real local network IP and SIP port:

```bash
call 192.168.1.50 5062 twoway
```

You can replace `twoway` with `file` or `mic`.

RTP and RTCP will be learned from the receiver's SDP response.

### 5. What SDP negotiates between two devices

The caller first sends an `INVITE` with an SDP body that advertises:

- the caller IP address with `c=IN IP4 <caller-ip>`
- the caller RTP port with `m=audio <caller-rtp-port> RTP/AVP 96`
- the caller RTCP port with `a=rtcp:<caller-rtcp-port>`
- the codec with `a=rtpmap:96 L16/8000/1`
- the media direction with `a=sendonly`, `a=recvonly`, or `a=sendrecv`

The receiver replies with `200 OK` and its own SDP body containing:

- the receiver IP address
- the receiver RTP port
- the receiver RTCP port
- the receiver codec description
- the receiver media direction

After that exchange:

- SIP continues to use the receiver's SIP port
- RTP is sent to the remote RTP port learned from SDP
- RTCP is sent to the remote RTCP port learned from SDP

This means the media path is negotiated dynamically. The caller does not need to hard-code the remote RTP or RTCP port as long as it can reach the remote SIP port.

### 6. Example two-device call flow

Example:

- Device A IP: `192.168.1.50`
- Device A ports: SIP `5060`, RTP `8000`, RTCP `8001`
- Device B IP: `192.168.1.60`
- Device B ports: SIP `5062`, RTP `8002`, RTCP `8003`

Device A starts the call:

```bash
call 192.168.1.60 5062 twoway
```

Negotiation result:

- SIP signaling goes to `192.168.1.60:5062`
- Device A advertises `8000/8001` in SDP
- Device B advertises `8002/8003` in SDP
- Device A sends RTP to `192.168.1.60:8002`
- Device A sends RTCP to `192.168.1.60:8003`
- Device B sends RTP to `192.168.1.50:8000`
- Device B sends RTCP to `192.168.1.50:8001`

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
- `180 Ringing`
- `200 OK`
- `ACK`
- `BYE`

You can also inspect the SDP body inside `INVITE` and `200 OK`.

Things to verify:

- `m=audio <port>` advertises the RTP port
- `a=rtcp:<port>` advertises the RTCP port
- `a=rtpmap:96 L16/8000/1` advertises the codec
- `c=IN IP4 <address>` matches the device sending that SDP

For two different devices, compare the SDP in both directions:

- the `INVITE` should advertise the caller's media IP and ports
- the `200 OK` should advertise the receiver's media IP and ports
- the RTP stream should go to the port advertised in the peer's SDP, not just the peer's SIP port

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

For two-device testing, capture on both machines if possible:

- on the caller, confirm outgoing RTP goes to the receiver's SDP-advertised RTP port
- on the receiver, confirm incoming RTP arrives on the receiver's advertised RTP port

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

For two-device testing:

- the caller should send RTCP to the receiver's SDP-advertised RTCP port
- the receiver should send RTCP to the caller's SDP-advertised RTCP port

### Combined filter

For the default localhost profiles, this filter captures the whole demo:

```text
udp.port == 5060 || udp.port == 5062 || udp.port == 8000 || udp.port == 8001 || udp.port == 8002 || udp.port == 8003
```

## Notes

- For `file` mode, you can use `sample_audio.wav` or provide another WAV file path when starting the call
- Localhost two-way mode may produce echo because both sides run on the same machine
- The RTP receiver socket uses a timeout so the app can exit the media loop cleanly when the call ends
- During teardown, media sockets are closed and reopened so the next call starts from a clean state

## Reference

Project specification:

- `NSCOM01_MP2_Project Specifications.pdf`
