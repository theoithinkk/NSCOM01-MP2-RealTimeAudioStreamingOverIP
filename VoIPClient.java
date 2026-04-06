import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Real-Time Audio Streaming over IP (NSCOM01-MCO2)
 */
public class VoIPClient {

    private static final int RTP_HEADER_SIZE = 12;
    private static final int SAMPLE_RATE = 8000;
    private static final int SAMPLE_SIZE_BYTES = 2;
    private static final int CHANNELS = 1;
    private static final int FRAME_DURATION_MS = 20;
    private static final int AUDIO_SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_DURATION_MS / 1000;
    private static final int AUDIO_BYTES_PER_FRAME = AUDIO_SAMPLES_PER_FRAME * SAMPLE_SIZE_BYTES * CHANNELS;
    private static final int RTP_PAYLOAD_TYPE = 96;
    private static final long NTP_UNIX_EPOCH_OFFSET = 2208988800L;

    private final AudioFormat audioFormat = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
    private final PortsProfile localProfile;

    private String localIP;

    private volatile DatagramSocket sipSocket;
    private volatile DatagramSocket rtpSocket;
    private volatile DatagramSocket rtcpSocket;

    private volatile boolean running = true;
    private volatile boolean inCall = false;
    private volatile boolean localMediaDirectionSend = false;
    private volatile String currentCallMode = "twoway";

    private volatile String remoteIP = "";
    private volatile int remoteSipPort;
    private volatile int remoteRtpPort;
    private volatile int remoteRtcpPort;
    private volatile String remoteCodec = "L16/8000/1";
    private volatile boolean waitingForFinalResponse = false;
    private volatile String selectedAudioFile = "sample_audio.wav";

    private volatile String localTag = buildTag();
    private volatile String remoteTag = "";
    private volatile String activeCallId = "";
    private volatile int localInviteCSeq = 1;

    private volatile long rtpPacketsSent = 0;
    private volatile long rtpOctetsSent = 0;
    private volatile long lastRtpTimestamp = 0;
    private volatile long activeSsrc = 0;
    private volatile TargetDataLine microphone;
    private volatile SourceDataLine speakers;
    private volatile AudioInputStream currentFileStream;

    public VoIPClient(PortsProfile localProfile) throws Exception {
        this.localProfile = localProfile;
        this.remoteSipPort = localProfile.defaultRemoteSipPort;
        this.localIP = InetAddress.getLocalHost().getHostAddress();

        openSipSocket();
        openMediaSockets();

        printStartupSummary();
    }

    public void startListening() {
        Thread sipListener = new Thread(() -> {
            while (running) {
                try {
                    byte[] buffer = new byte[4096];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    sipSocket.receive(packet);

                    String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    String senderIP = packet.getAddress().getHostAddress();
                    int senderPort = packet.getPort();

                    System.out.println("\n--- Received SIP Message ---\n" + message.trim());
                    handleSipMessage(message, senderIP, senderPort);
                } catch (SocketException e) {
                    if (running) {
                        System.err.println("SIP listener stopped unexpectedly: " + e.getMessage());
                    }
                    break;
                } catch (Exception e) {
                    if (running) {
                        System.err.println("SIP listener error: " + e.getMessage());
                    }
                }
            }
        }, "sip-listener");
        sipListener.setDaemon(true);
        sipListener.start();
    }

    private synchronized void handleSipMessage(String message, String senderIP, int senderPort) throws Exception {
        SipMessage sipMessage = SipMessage.parse(message);
        String firstLine = sipMessage.startLine;

        if (firstLine.startsWith("INVITE")) {
            if (inCall) {
                sendSimpleResponse(senderIP, senderPort, "SIP/2.0 486 Busy Here", sipMessage, false, null);
                return;
            }

            SessionDescription remoteSession = SessionDescription.parse(sipMessage.body, senderIP);
            remoteIP = remoteSession.connectionAddress;
            remoteSipPort = senderPort;
            remoteRtpPort = remoteSession.audioPort;
            remoteRtcpPort = remoteSession.rtcpPort;
            remoteCodec = remoteSession.codecName;
            currentCallMode = sipMessage.headerOrDefault("X-Audio-Mode", "twoway").toLowerCase(Locale.ROOT);
            localMediaDirectionSend = "twoway".equals(currentCallMode);
            activeCallId = sipMessage.headerOrDefault("Call-ID", buildCallId());
            remoteTag = extractTag(sipMessage.headerOrDefault("From", ""));
            localTag = buildTag();
            localInviteCSeq = parseCSeqNumber(sipMessage.headerOrDefault("CSeq", "1 INVITE"));

            System.out.println(">>> Incoming [" + currentCallMode.toUpperCase(Locale.ROOT) + "] call from " + remoteIP + ":" + remoteSipPort);
            System.out.println(">>> Remote SDP negotiated RTP=" + remoteRtpPort + " RTCP=" + remoteRtcpPort + " Codec=" + remoteCodec);

            ensureMediaSocketsOpen();
            resetSessionCounters();

            sendSimpleResponse(senderIP, senderPort, "SIP/2.0 180 Ringing", sipMessage, false, null);
            System.out.println(">>> Sent 180 Ringing to caller.");

            String responseBody = buildSdpBody(localProfile.rtpPort, localProfile.rtcpPort, currentCallMode, false);
            sendSimpleResponse(senderIP, senderPort, "SIP/2.0 200 OK", sipMessage, true, responseBody);
        } else if (firstLine.startsWith("SIP/2.0 200 OK")) {
            waitingForFinalResponse = false;
            remoteIP = senderIP;
            remoteSipPort = senderPort;
            remoteTag = extractTag(sipMessage.headerOrDefault("To", ""));
            if (!sipMessage.body.isEmpty()) {
                SessionDescription negotiatedSession = SessionDescription.parse(sipMessage.body, senderIP);
                remoteIP = negotiatedSession.connectionAddress;
                remoteRtpPort = negotiatedSession.audioPort;
                remoteRtcpPort = negotiatedSession.rtcpPort;
                remoteCodec = negotiatedSession.codecName;
            }

            System.out.println(">>> 200 OK received. Remote SDP negotiated RTP=" + remoteRtpPort + " RTCP=" + remoteRtcpPort + " Codec=" + remoteCodec);

            sendSipRequest(buildAckRequest(), remoteIP, remoteSipPort);
            startEstablishedMedia();
        } else if (firstLine.startsWith("SIP/2.0 180 Ringing")) {
            waitingForFinalResponse = true;
            remoteIP = senderIP;
            remoteSipPort = senderPort;
            remoteTag = extractTag(sipMessage.headerOrDefault("To", ""));
            System.out.println(">>> Remote endpoint is ringing...");
        } else if (firstLine.startsWith("ACK")) {
            startEstablishedMedia();
        } else if (firstLine.startsWith("BYE")) {
            System.out.println(">>> Remote hung up.");
            sendSimpleResponse(senderIP, senderPort, "SIP/2.0 200 OK", sipMessage, false, null);
            endCallLocally(true);
        } else if (firstLine.matches("^SIP/2\\.0 [45]\\d{2}.*")) {
            waitingForFinalResponse = false;
            System.err.println(">>> SIP Error received from remote: " + firstLine);
            endCallLocally(true);
        } else {
            System.out.println(">>> Received unexpected or unknown SIP packet. Ignoring to prevent crash.");
        }
    }

    public synchronized void call(String targetIP, int targetSipPort, String mode, String audioFilePath) {
        try {
            if (inCall) {
                System.out.println("Finish the active call first before starting a new one.");
                return;
            }

            ensureMediaSocketsOpen();
            resetSessionCounters();

            remoteIP = normalizeHost(targetIP);
            remoteSipPort = targetSipPort;
            remoteRtpPort = 0;
            remoteRtcpPort = 0;
            remoteCodec = "pending";
            currentCallMode = mode.toLowerCase(Locale.ROOT);
            selectedAudioFile = (audioFilePath == null || audioFilePath.trim().isEmpty()) ? "sample_audio.wav" : audioFilePath.trim();
            localMediaDirectionSend = true;
            activeCallId = buildCallId();
            localTag = buildTag();
            remoteTag = "";
            localInviteCSeq = 1;
            waitingForFinalResponse = true;

            String inviteBody = buildSdpBody(localProfile.rtpPort, localProfile.rtcpPort, currentCallMode, true);
            sendSipRequest(buildInviteRequest(remoteIP, inviteBody), remoteIP, remoteSipPort);
            System.out.println(">>> Calling " + remoteIP + ":" + remoteSipPort + " in " + currentCallMode.toUpperCase(Locale.ROOT) + " mode...");
            if ("file".equals(currentCallMode)) {
                System.out.println(">>> Selected audio file: " + selectedAudioFile);
            }
        } catch (Exception e) {
            System.err.println("Unable to start call: " + e.getMessage());
        }
    }

    public synchronized void hangUp() {
        if (!inCall && activeCallId.isEmpty()) {
            System.out.println("You are not currently in a call.");
            return;
        }

        if (!remoteIP.isEmpty() && remoteSipPort > 0) {
            sendSipRequest(buildByeRequest(), remoteIP, remoteSipPort);
        }
        endCallLocally(true);
    }

    private synchronized void startEstablishedMedia() {
        if (inCall) {
            return;
        }

        if (remoteRtpPort <= 0 || remoteRtcpPort <= 0) {
            System.err.println("Remote SDP did not provide valid RTP/RTCP ports. Ending call.");
            endCallLocally(true);
            return;
        }

        inCall = true;

        System.out.println(">>> Call established! Mode: " + currentCallMode.toUpperCase(Locale.ROOT));
        System.out.println(">>> Sending media to RTP " + remoteRtpPort + " / RTCP " + remoteRtcpPort);

        startRtpReceiver();
        startRtcpSender();

        if ("file".equals(currentCallMode) && localMediaDirectionSend) {
            startRtpSenderFile(selectedAudioFile);
        } else if (localMediaDirectionSend) {
            startRtpSenderMic();
        } else {
            System.out.println("Listening to incoming one-way audio stream...");
        }
    }

    private synchronized void endCallLocally(boolean reopenMediaSockets) {
        inCall = false;
        localMediaDirectionSend = false;
        closeAudioResources();
        closeMediaSockets();

        if (reopenMediaSockets && running) {
            try {
                openMediaSockets();
            } catch (SocketException e) {
                System.err.println("Failed to reopen media sockets: " + e.getMessage());
            }
        }

        activeCallId = "";
        remoteTag = "";
        remoteIP = "";
        remoteRtpPort = 0;
        remoteRtcpPort = 0;
        remoteCodec = "L16/8000/1";
        waitingForFinalResponse = false;

        System.out.println(">>> Call ended. Media sockets reset.");
    }

    private synchronized void shutdownClient() {
        running = false;
        inCall = false;
        closeAudioResources();
        closeMediaSockets();

        if (sipSocket != null && !sipSocket.isClosed()) {
            sipSocket.close();
        }

        System.out.println("Shutting down VoIP Client...");
    }

    private synchronized void sendGarbagePacket() {
        if (remoteIP.isEmpty()) {
            System.out.println("No recent remote target found. Send a call first or use a SIP destination.");
            return;
        }
        sendSipRequest("BLAH BLAH BLAH THIS IS NOT A SIP PACKET", remoteIP, remoteSipPort);
    }

    private synchronized void showSessionStatus() {
        System.out.println("\n================ SESSION STATUS ================");
        System.out.println("Local IP          : " + localIP);
        System.out.println("Local profile     : " + localProfile.name);
        System.out.println("Local SIP         : " + printableEndpoint(localIP, localProfile.sipPort));
        System.out.println("Local RTP/RTCP    : " + printableEndpoint(localIP, localProfile.rtpPort) + " / " + localProfile.rtcpPort);
        System.out.println("Default remote SIP: " + localProfile.defaultRemoteSipPort);
        System.out.println("In call          : " + inCall);
        System.out.println("Mode             : " + currentCallMode);
        System.out.println("Audio file       : " + selectedAudioFile);
        System.out.println("Waiting final SIP: " + waitingForFinalResponse);
        System.out.println("Remote SIP       : " + printableEndpoint(remoteIP, remoteSipPort));
        System.out.println("Remote RTP/RTCP  : " + printableEndpoint(remoteIP, remoteRtpPort) + " / " + remoteRtcpPort);
        System.out.println("Negotiated codec : " + remoteCodec);
        System.out.println("Packets sent     : " + rtpPacketsSent);
        System.out.println("Octets sent      : " + rtpOctetsSent);
        System.out.println("================================================");
    }

    private String buildInviteRequest(String targetIP, String body) {
        StringBuilder builder = new StringBuilder();
        builder.append("INVITE sip:").append(targetIP).append(":").append(remoteSipPort).append(" SIP/2.0\r\n");
        builder.append("Via: SIP/2.0/UDP ").append(localIP).append(":").append(localProfile.sipPort).append(";branch=").append(buildBranch()).append("\r\n");
        builder.append("Max-Forwards: 70\r\n");
        builder.append("From: <sip:caller@").append(localIP).append(">;tag=").append(localTag).append("\r\n");
        builder.append("To: <sip:receiver@").append(targetIP).append(">\r\n");
        builder.append("Call-ID: ").append(activeCallId).append("\r\n");
        builder.append("CSeq: ").append(localInviteCSeq).append(" INVITE\r\n");
        builder.append("Contact: <sip:").append(localIP).append(":").append(localProfile.sipPort).append(">\r\n");
        builder.append("Subject: NSCOM01 VoIP Session\r\n");
        builder.append("User-Agent: NSCOM01-VoIPClient/2.0\r\n");
        builder.append("X-Audio-Mode: ").append(currentCallMode).append("\r\n");
        builder.append("Content-Type: application/sdp\r\n");
        builder.append("Content-Length: ").append(body.getBytes(StandardCharsets.UTF_8).length).append("\r\n\r\n");
        builder.append(body);
        return builder.toString();
    }

    private String buildAckRequest() {
        StringBuilder builder = new StringBuilder();
        builder.append("ACK sip:").append(remoteIP).append(":").append(remoteSipPort).append(" SIP/2.0\r\n");
        builder.append("Via: SIP/2.0/UDP ").append(localIP).append(":").append(localProfile.sipPort).append(";branch=").append(buildBranch()).append("\r\n");
        builder.append("Max-Forwards: 70\r\n");
        builder.append("From: <sip:caller@").append(localIP).append(">;tag=").append(localTag).append("\r\n");
        builder.append("To: <sip:receiver@").append(remoteIP).append(">;tag=").append(remoteTag).append("\r\n");
        builder.append("Call-ID: ").append(activeCallId).append("\r\n");
        builder.append("CSeq: ").append(localInviteCSeq).append(" ACK\r\n");
        builder.append("Contact: <sip:").append(localIP).append(":").append(localProfile.sipPort).append(">\r\n");
        builder.append("Content-Length: 0\r\n\r\n");
        return builder.toString();
    }

    private String buildByeRequest() {
        String fromUser = localMediaDirectionSend ? "caller" : "receiver";
        String toUser = localMediaDirectionSend ? "receiver" : "caller";

        StringBuilder builder = new StringBuilder();
        builder.append("BYE sip:").append(remoteIP).append(":").append(remoteSipPort).append(" SIP/2.0\r\n");
        builder.append("Via: SIP/2.0/UDP ").append(localIP).append(":").append(localProfile.sipPort).append(";branch=").append(buildBranch()).append("\r\n");
        builder.append("Max-Forwards: 70\r\n");
        builder.append("From: <sip:").append(fromUser).append("@").append(localIP).append(">;tag=").append(localTag).append("\r\n");
        builder.append("To: <sip:").append(toUser).append("@").append(remoteIP).append(">");
        if (!remoteTag.isEmpty()) {
            builder.append(";tag=").append(remoteTag);
        }
        builder.append("\r\n");
        builder.append("Call-ID: ").append(activeCallId).append("\r\n");
        builder.append("CSeq: ").append(localInviteCSeq + 1).append(" BYE\r\n");
        builder.append("Contact: <sip:").append(localIP).append(":").append(localProfile.sipPort).append(">\r\n");
        builder.append("Content-Length: 0\r\n\r\n");
        return builder.toString();
    }

    private void sendSimpleResponse(String targetIP, int targetPort, String statusLine, SipMessage request, boolean includeBody, String body) {
        String payloadBody = includeBody && body != null ? body : "";
        StringBuilder builder = new StringBuilder();
        builder.append(statusLine).append("\r\n");
        builder.append("Via: ").append(request.headerOrDefault("Via", "SIP/2.0/UDP " + targetIP + ":" + targetPort)).append("\r\n");
        builder.append("From: ").append(request.headerOrDefault("From", "<sip:unknown@unknown>")).append("\r\n");
        builder.append("To: ").append(appendToTag(request.headerOrDefault("To", "<sip:unknown@unknown>"))).append("\r\n");
        builder.append("Call-ID: ").append(request.headerOrDefault("Call-ID", activeCallId)).append("\r\n");
        builder.append("CSeq: ").append(request.headerOrDefault("CSeq", "1 INVITE")).append("\r\n");
        builder.append("Contact: <sip:").append(localIP).append(":").append(localProfile.sipPort).append(">\r\n");
        builder.append("User-Agent: NSCOM01-VoIPClient/2.0\r\n");
        if (includeBody) {
            builder.append("Content-Type: application/sdp\r\n");
        }
        builder.append("Content-Length: ").append(payloadBody.getBytes(StandardCharsets.UTF_8).length).append("\r\n\r\n");
        builder.append(payloadBody);
        sendSipRequest(builder.toString(), targetIP, targetPort);
    }

    private synchronized void sendSipRequest(String msg, String targetIP, int targetPort) {
        try {
            byte[] buffer = msg.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, InetAddress.getByName(targetIP), targetPort);
            sipSocket.send(packet);
        } catch (Exception e) {
            System.err.println("Failed to send SIP message: " + e.getMessage());
        }
    }

    private String buildSdpBody(int offeredRtpPort, int offeredRtcpPort, String mode, boolean callerSide) {
        String direction;
        if ("twoway".equals(mode)) {
            direction = "a=sendrecv\r\n";
        } else if (callerSide) {
            direction = "a=sendonly\r\n";
        } else {
            direction = "a=recvonly\r\n";
        }

        return "v=0\r\n" +
               "o=- " + System.currentTimeMillis() + " " + (System.currentTimeMillis() + 1) + " IN IP4 " + localIP + "\r\n" +
               "s=NSCOM01 Real-Time Audio Session\r\n" +
               "c=IN IP4 " + localIP + "\r\n" +
               "t=0 0\r\n" +
               "m=audio " + offeredRtpPort + " RTP/AVP " + RTP_PAYLOAD_TYPE + "\r\n" +
               "a=rtcp:" + offeredRtcpPort + "\r\n" +
               "a=rtpmap:" + RTP_PAYLOAD_TYPE + " L16/8000/1\r\n" +
               "a=ptime:" + FRAME_DURATION_MS + "\r\n" +
               direction;
    }

    private void startRtpSenderFile(String filePath) {
        Thread fileSender = new Thread(() -> {
            try {
                File audioFile = new File(filePath);
                if (!audioFile.exists()) {
                    System.err.println("ERROR: Could not find '" + filePath + "'. Ending call.");
                    hangUp();
                    return;
                }

                currentFileStream = AudioSystem.getAudioInputStream(audioFile);
                byte[] payload = new byte[AUDIO_BYTES_PER_FRAME];

                System.out.println("Streaming '" + audioFile.getName() + "' over RTP...");

                while (inCall) {
                    int bytesRead = currentFileStream.read(payload, 0, payload.length);
                    if (bytesRead == -1) {
                        break;
                    }

                    sendRtpPacket(payload, bytesRead);
                    Thread.sleep(FRAME_DURATION_MS);
                }

                if (inCall) {
                    System.out.println("Audio file finished. Hanging up.");
                    hangUp();
                }
            } catch (Exception e) {
                if (inCall) {
                    System.err.println("RTP file sender error: " + e.getMessage());
                }
            } finally {
                closeFileStream();
            }
        }, "rtp-file-sender");
        fileSender.setDaemon(true);
        fileSender.start();
    }

    private void startRtpSenderMic() {
        Thread micSender = new Thread(() -> {
            try {
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, audioFormat);
                if (!AudioSystem.isLineSupported(info)) {
                    System.err.println("Microphone not supported!");
                    hangUp();
                    return;
                }

                microphone = (TargetDataLine) AudioSystem.getLine(info);
                microphone.open(audioFormat);
                microphone.start();

                byte[] payload = new byte[AUDIO_BYTES_PER_FRAME];
                System.out.println("Microphone live. Streaming audio over RTP...");

                while (inCall) {
                    int bytesRead = microphone.read(payload, 0, payload.length);
                    if (bytesRead > 0) {
                        sendRtpPacket(payload, bytesRead);
                    }
                }
            } catch (Exception e) {
                if (inCall) {
                    System.err.println("RTP microphone sender error: " + e.getMessage());
                }
            } finally {
                closeMicrophone();
            }
        }, "rtp-mic-sender");
        micSender.setDaemon(true);
        micSender.start();
    }

    private synchronized void sendRtpPacket(byte[] payload, int payloadLength) throws Exception {
        if (!inCall || rtpSocket == null || rtpSocket.isClosed()) {
            return;
        }

        byte[] rtpPacket = new byte[RTP_HEADER_SIZE + payloadLength];
        rtpPacket[0] = (byte) 0x80;
        rtpPacket[1] = (byte) (RTP_PAYLOAD_TYPE & 0x7F);
        rtpPacket[2] = (byte) (rtpPacketsSent >> 8);
        rtpPacket[3] = (byte) (rtpPacketsSent & 0xFF);
        rtpPacket[4] = (byte) (lastRtpTimestamp >> 24);
        rtpPacket[5] = (byte) (lastRtpTimestamp >> 16);
        rtpPacket[6] = (byte) (lastRtpTimestamp >> 8);
        rtpPacket[7] = (byte) (lastRtpTimestamp & 0xFF);
        rtpPacket[8] = (byte) (activeSsrc >> 24);
        rtpPacket[9] = (byte) (activeSsrc >> 16);
        rtpPacket[10] = (byte) (activeSsrc >> 8);
        rtpPacket[11] = (byte) (activeSsrc & 0xFF);

        System.arraycopy(payload, 0, rtpPacket, RTP_HEADER_SIZE, payloadLength);

        DatagramPacket packet = new DatagramPacket(
            rtpPacket,
            rtpPacket.length,
            InetAddress.getByName(remoteIP),
            remoteRtpPort
        );
        rtpSocket.send(packet);

        rtpPacketsSent++;
        rtpOctetsSent += payloadLength;
        lastRtpTimestamp += payloadLength / SAMPLE_SIZE_BYTES;
    }

    private void startRtpReceiver() {
        Thread receiver = new Thread(() -> {
            DatagramSocket localRtpSocket = rtpSocket;
            if (localRtpSocket == null) {
                return;
            }

            try {
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
                speakers = (SourceDataLine) AudioSystem.getLine(info);
                speakers.open(audioFormat);
                speakers.start();

                byte[] buffer = new byte[2048];
                while (inCall && !localRtpSocket.isClosed()) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        localRtpSocket.receive(packet);

                        if (packet.getLength() < RTP_HEADER_SIZE) {
                            continue;
                        }

                        int payloadLength = packet.getLength() - RTP_HEADER_SIZE;
                        if (payloadLength > 0) {
                            speakers.write(packet.getData(), RTP_HEADER_SIZE, payloadLength);
                        }
                    } catch (SocketTimeoutException ignored) {
                        // Timeout lets the thread observe call teardown without hanging forever.
                    }
                }
            } catch (Exception e) {
                if (inCall) {
                    System.err.println("RTP receiver error: " + e.getMessage());
                }
            } finally {
                closeSpeakers();
            }
        }, "rtp-receiver");
        receiver.setDaemon(true);
        receiver.start();
    }

    private void startRtcpSender() {
        Thread rtcpSender = new Thread(() -> {
            DatagramSocket localRtcpSocket = rtcpSocket;
            if (localRtcpSocket == null) {
                return;
            }

            try {
                while (inCall && !localRtcpSocket.isClosed()) {
                    Thread.sleep(5000);
                    if (!inCall) {
                        break;
                    }

                    byte[] report = buildRtcpSenderReport();
                    DatagramPacket packet = new DatagramPacket(
                        report,
                        report.length,
                        InetAddress.getByName(remoteIP),
                        remoteRtcpPort
                    );
                    localRtcpSocket.send(packet);
                }
            } catch (Exception e) {
                if (inCall) {
                    System.err.println("RTCP sender error: " + e.getMessage());
                }
            }
        }, "rtcp-sender");
        rtcpSender.setDaemon(true);
        rtcpSender.start();
    }

    private synchronized byte[] buildRtcpSenderReport() {
        ByteBuffer buffer = ByteBuffer.allocate(28);
        buffer.order(ByteOrder.BIG_ENDIAN);

        long nowMillis = System.currentTimeMillis();
        long ntpSeconds = (nowMillis / 1000L) + NTP_UNIX_EPOCH_OFFSET;
        long remainderMillis = nowMillis % 1000L;
        long ntpFraction = (remainderMillis * 0x100000000L) / 1000L;

        buffer.put((byte) 0x80);
        buffer.put((byte) 200);
        buffer.putShort((short) 6);
        buffer.putInt((int) activeSsrc);
        buffer.putInt((int) ntpSeconds);
        buffer.putInt((int) ntpFraction);
        buffer.putInt((int) lastRtpTimestamp);
        buffer.putInt((int) rtpPacketsSent);
        buffer.putInt((int) rtpOctetsSent);

        return buffer.array();
    }

    private synchronized void resetSessionCounters() {
        rtpPacketsSent = 0;
        rtpOctetsSent = 0;
        lastRtpTimestamp = 0;
        activeSsrc = Integer.toUnsignedLong(new Random().nextInt());
    }

    private void openSipSocket() throws SocketException {
        sipSocket = new DatagramSocket(localProfile.sipPort);
    }

    private synchronized void ensureMediaSocketsOpen() throws SocketException {
        if (rtpSocket == null || rtpSocket.isClosed() || rtcpSocket == null || rtcpSocket.isClosed()) {
            openMediaSockets();
        }
    }

    private synchronized void openMediaSockets() throws SocketException {
        if (rtpSocket == null || rtpSocket.isClosed()) {
            rtpSocket = new DatagramSocket(localProfile.rtpPort);
            rtpSocket.setSoTimeout(500);
        }

        if (rtcpSocket == null || rtcpSocket.isClosed()) {
            rtcpSocket = new DatagramSocket(localProfile.rtcpPort);
        }
    }

    private synchronized void closeMediaSockets() {
        if (rtpSocket != null && !rtpSocket.isClosed()) {
            rtpSocket.close();
        }
        if (rtcpSocket != null && !rtcpSocket.isClosed()) {
            rtcpSocket.close();
        }
    }

    private synchronized void closeAudioResources() {
        closeMicrophone();
        closeSpeakers();
        closeFileStream();
    }

    private synchronized void closeMicrophone() {
        if (microphone != null) {
            microphone.stop();
            microphone.close();
            microphone = null;
        }
    }

    private synchronized void closeSpeakers() {
        if (speakers != null) {
            speakers.drain();
            speakers.close();
            speakers = null;
        }
    }

    private synchronized void closeFileStream() {
        if (currentFileStream != null) {
            try {
                currentFileStream.close();
            } catch (IOException ignored) {
                // Nothing else to do during shutdown.
            }
            currentFileStream = null;
        }
    }

    private static String extractTag(String headerValue) {
        int index = headerValue.toLowerCase(Locale.ROOT).indexOf(";tag=");
        if (index == -1) {
            return "";
        }
        return headerValue.substring(index + 5).trim();
    }

    private String appendToTag(String toHeader) {
        if (toHeader.toLowerCase(Locale.ROOT).contains(";tag=")) {
            remoteTag = extractTag(toHeader);
            return toHeader;
        }
        return toHeader + ";tag=" + localTag;
    }

    private static int parseCSeqNumber(String cseqHeader) {
        String[] parts = cseqHeader.trim().split("\\s+");
        if (parts.length == 0) {
            return 1;
        }
        try {
            return Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String normalizeHost(String host) throws UnknownHostException {
        return InetAddress.getByName(host).getHostAddress();
    }

    private static String buildTag() {
        return Long.toHexString(Double.doubleToLongBits(Math.random()));
    }

    private static String buildBranch() {
        return "z9hG4bK-" + Long.toHexString(Double.doubleToLongBits(Math.random()));
    }

    private String buildCallId() {
        return System.currentTimeMillis() + "@" + localIP;
    }

    private static String printableEndpoint(String ip, int port) {
        if (ip == null || ip.isEmpty() || port <= 0) {
            return "(not set)";
        }
        return ip + ":" + port;
    }

    private static void printProfile(PortsProfile profile) {
        System.out.println("SIP=" + profile.sipPort + " RTP=" + profile.rtpPort + " RTCP=" + profile.rtcpPort +
                           " Default Remote SIP=" + profile.defaultRemoteSipPort);
    }

    private void printStartupSummary() {
        System.out.println("\n================ LOCAL SESSION =================");
        System.out.println("Detected local IP : " + localIP);
        System.out.println("Selected profile  : " + localProfile.name);
        System.out.println("Local SIP         : " + printableEndpoint(localIP, localProfile.sipPort));
        System.out.println("Local RTP/RTCP    : " + printableEndpoint(localIP, localProfile.rtpPort) + " / " + localProfile.rtcpPort);
        System.out.println("Default remote SIP: " + localProfile.defaultRemoteSipPort);
        System.out.println("================================================");
    }

    private static void showMainMenu() {
        System.out.println("\n=================== ACTIONS ===================");
        System.out.println("1. Start a call");
        System.out.println("2. Hang up");
        System.out.println("3. Show session status");
        System.out.println("4. Send malformed SIP packet");
        System.out.println("5. Show help");
        System.out.println("6. Quit");
        System.out.println("================================================");
    }

    private static void showHelp() {
        System.out.println("\nHelp:");
        System.out.println("- Use the menu to start calls, hang up, inspect status, or quit.");
        System.out.println("- Detailed setup, Wireshark guidance, and legacy command examples are in README.md.");
    }

    private static String prompt(BufferedReader reader, String question) throws IOException {
        System.out.print(question);
        return reader.readLine().trim();
    }

    private static int promptForInt(BufferedReader reader, String question, int defaultValue) throws IOException {
        while (true) {
            String raw = prompt(reader, question + " [" + defaultValue + "]: ");
            if (raw.isEmpty()) {
                return defaultValue;
            }
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                System.out.println("Enter a valid number.");
            }
        }
    }

    private static PortsProfile chooseProfile(BufferedReader reader) throws IOException {
        while (true) {
            System.out.println("=================================================");
            System.out.println("              VoIP TESTING CLIENT                ");
            System.out.println("=================================================");
            System.out.println("Select a local profile:");
            System.out.println("1 - Profile A (SIP 5060, RTP 8000, RTCP 8001, Remote SIP 5062)");
            System.out.println("2 - Profile B (SIP 5062, RTP 8002, RTCP 8003, Remote SIP 5060)");
            System.out.println("3 - Custom profile");

            String choice = prompt(reader, "Enter choice (1, 2, or 3): ");

            if ("1".equals(choice)) {
                return new PortsProfile("Profile A", 5060, 8000, 8001, 5062);
            }
            if ("2".equals(choice)) {
                return new PortsProfile("Profile B", 5062, 8002, 8003, 5060);
            }
            if ("3".equals(choice)) {
                int sip = promptForInt(reader, "Local SIP port", 5060);
                int rtp = promptForInt(reader, "Local RTP port", 8000);
                int rtcp = promptForInt(reader, "Local RTCP port", rtp + 1);
                int remoteSip = promptForInt(reader, "Default remote SIP port", 5062);
                return new PortsProfile("Custom", sip, rtp, rtcp, remoteSip);
            }

            System.out.println("Choose 1, 2, or 3.\n");
        }
    }

    private static void handleMenuAction(VoIPClient client, BufferedReader reader, String input) throws IOException {
        switch (input) {
            case "1":
                String targetIp = prompt(reader, "Remote IP or hostname: ");
                int targetSipPort = promptForInt(reader, "Remote SIP port", client.localProfile.defaultRemoteSipPort);
                String mode = prompt(reader, "Call mode (file/mic/twoway): ").toLowerCase(Locale.ROOT);
                if (!mode.equals("file") && !mode.equals("mic") && !mode.equals("twoway")) {
                    System.out.println("Invalid mode. Use file, mic, or twoway.");
                    return;
                }
                String audioFile = "sample_audio.wav";
                if ("file".equals(mode)) {
                    String enteredFile = prompt(reader, "Audio file path [sample_audio.wav]: ");
                    if (!enteredFile.isEmpty()) {
                        audioFile = enteredFile;
                    }
                }
                client.call(targetIp, targetSipPort, mode, audioFile);
                return;
            case "2":
                client.hangUp();
                return;
            case "3":
                client.showSessionStatus();
                return;
            case "4":
                client.sendGarbagePacket();
                return;
            case "5":
                showHelp();
                return;
            case "6":
                client.shutdownClient();
                return;
            default:
                break;
        }

        String[] parts = input.split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return;
        }

        if (parts[0].equalsIgnoreCase("call")) {
            if (parts.length < 4) {
                System.out.println("Usage: call <IP> <sipPort> <file|mic|twoway> [audioFile]");
                return;
            }
            try {
                String audioFile = parts.length >= 5 ? parts[4] : "sample_audio.wav";
                client.call(parts[1], Integer.parseInt(parts[2]), parts[3], audioFile);
            } catch (NumberFormatException e) {
                System.out.println("Remote SIP port must be a number.");
            }
        } else if (parts[0].equalsIgnoreCase("hangup")) {
            client.hangUp();
        } else if (parts[0].equalsIgnoreCase("status")) {
            client.showSessionStatus();
        } else if (parts[0].equalsIgnoreCase("garbage")) {
            client.sendGarbagePacket();
        } else if (parts[0].equalsIgnoreCase("help")) {
            showHelp();
        } else if (parts[0].equalsIgnoreCase("quit") || parts[0].equalsIgnoreCase("exit")) {
            client.shutdownClient();
        } else {
            System.out.println("Unknown command. Choose a menu option or type help.");
        }
    }

    public static void main(String[] args) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            PortsProfile profile = chooseProfile(reader);
            VoIPClient client = new VoIPClient(profile);
            client.startListening();

            showMainMenu();
            showHelp();

            while (client.running) {
                String input = prompt(reader, "\nSelect action: ");
                handleMenuAction(client, reader, input);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static final class PortsProfile {
        private final String name;
        private final int sipPort;
        private final int rtpPort;
        private final int rtcpPort;
        private final int defaultRemoteSipPort;

        private PortsProfile(String name, int sipPort, int rtpPort, int rtcpPort, int defaultRemoteSipPort) {
            this.name = name;
            this.sipPort = sipPort;
            this.rtpPort = rtpPort;
            this.rtcpPort = rtcpPort;
            this.defaultRemoteSipPort = defaultRemoteSipPort;
        }
    }

    private static final class SessionDescription {
        private final String connectionAddress;
        private final int audioPort;
        private final int rtcpPort;
        private final String codecName;

        private SessionDescription(String connectionAddress, int audioPort, int rtcpPort, String codecName) {
            this.connectionAddress = connectionAddress;
            this.audioPort = audioPort;
            this.rtcpPort = rtcpPort;
            this.codecName = codecName;
        }

        private static SessionDescription parse(String sdpBody, String fallbackIp) {
            String connectionAddress = fallbackIp;
            int audioPort = 0;
            int rtcpPort = 0;
            String codecName = "L16/8000/1";

            for (String line : sdpBody.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("c=IN IP4 ")) {
                    connectionAddress = trimmed.substring("c=IN IP4 ".length()).trim();
                } else if (trimmed.startsWith("m=audio ")) {
                    String[] parts = trimmed.split("\\s+");
                    if (parts.length >= 2) {
                        audioPort = safeParseInt(parts[1], 0);
                    }
                } else if (trimmed.startsWith("a=rtcp:")) {
                    rtcpPort = safeParseInt(trimmed.substring("a=rtcp:".length()).trim(), 0);
                } else if (trimmed.startsWith("a=rtpmap:")) {
                    int spaceIndex = trimmed.indexOf(' ');
                    if (spaceIndex > 0 && spaceIndex + 1 < trimmed.length()) {
                        codecName = trimmed.substring(spaceIndex + 1).trim();
                    }
                }
            }

            if (rtcpPort == 0 && audioPort > 0) {
                rtcpPort = audioPort + 1;
            }

            return new SessionDescription(connectionAddress, audioPort, rtcpPort, codecName);
        }
    }

    private static final class SipMessage {
        private final String startLine;
        private final Map<String, String> headers;
        private final String body;

        private SipMessage(String startLine, Map<String, String> headers, String body) {
            this.startLine = startLine;
            this.headers = headers;
            this.body = body;
        }

        private static SipMessage parse(String rawMessage) {
            String[] sections = rawMessage.split("\\r?\\n\\r?\\n", 2);
            String headerSection = sections[0];
            String body = sections.length > 1 ? sections[1] : "";

            String[] lines = headerSection.split("\\r?\\n");
            String startLine = lines.length > 0 ? lines[0].trim() : "";
            Map<String, String> headers = new HashMap<>();

            for (int i = 1; i < lines.length; i++) {
                int colonIndex = lines[i].indexOf(':');
                if (colonIndex <= 0) {
                    continue;
                }
                String name = lines[i].substring(0, colonIndex).trim();
                String value = lines[i].substring(colonIndex + 1).trim();
                headers.put(name, value);
            }

            return new SipMessage(startLine, headers, body);
        }

        private String headerOrDefault(String name, String defaultValue) {
            return headers.getOrDefault(name, defaultValue);
        }
    }

    private static int safeParseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
