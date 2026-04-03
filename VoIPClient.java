import javax.sound.sampled.*;
import java.io.*;
import java.net.*;
import java.util.Random;
import java.util.Scanner;

/**
 * Real-Time Audio Streaming over IP (NSCOM01-MCO2)
 */
public class VoIPClient {

    private int localSipPort, localRtpPort, localRtcpPort;
    private int targetSipPort, targetRtpPort, targetRtcpPort;
    
    private String localIP;
    private DatagramSocket sipSocket;
    private DatagramSocket rtpSocket;
    private DatagramSocket rtcpSocket;
    
    private volatile boolean inCall = false;
    private String remoteIP = "";
    private String currentCallMode = "twoway"; // Default mode
    
    // Audio configuration: PCM 8000Hz, 16-bit, mono
    private AudioFormat audioFormat = new AudioFormat(8000.0f, 16, 1, true, false);

    public VoIPClient(int lSip, int lRtp, int lRtcp, int tSip, int tRtp, int tRtcp) throws Exception {
        this.localSipPort = lSip;
        this.localRtpPort = lRtp;
        this.localRtcpPort = lRtcp;
        
        this.targetSipPort = tSip;
        this.targetRtpPort = tRtp;
        this.targetRtcpPort = tRtcp;

        this.localIP = InetAddress.getLocalHost().getHostAddress();
        
        this.sipSocket = new DatagramSocket(localSipPort);
        this.rtpSocket = new DatagramSocket(localRtpPort);
        this.rtcpSocket = new DatagramSocket(localRtcpPort);
        
        // Add a timeout so the RTP thread doesn't get permanently stuck waiting for packets
        this.rtpSocket.setSoTimeout(500); 
        
        System.out.println("Client initialized on IP: " + localIP);
        System.out.println("Listening on SIP Port: " + localSipPort + " | RTP Port: " + localRtpPort);
    }

    // SIP SIGNALING & SDP NEGOTIATION

    public void startListening() {
        new Thread(() -> {
            try {
                byte[] buffer = new byte[2048];
                while (true) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    sipSocket.receive(packet);
                    String message = new String(packet.getData(), 0, packet.getLength());
                    String senderIP = packet.getAddress().getHostAddress();
                    
                    System.out.println("\n--- Received SIP Message ---\n" + message.trim());
                    handleSipMessage(message, senderIP);
                }
            } catch (Exception e) {
                if (!sipSocket.isClosed()) e.printStackTrace();
            }
        }).start();
    }

    private void handleSipMessage(String message, String senderIP) throws Exception {
        if (message.startsWith("INVITE")) {
            this.remoteIP = senderIP;
            this.currentCallMode = extractHeader(message, "X-Audio-Mode", "twoway");
            System.out.println(">>> Incoming [" + currentCallMode.toUpperCase() + "] call from " + remoteIP + "...");
            
            // Send 200 OK with embedded SDP and echo the Audio Mode back
            sendSipMessage("SIP/2.0 200 OK\r\n" +
                           "Contact: <sip:" + localIP + ":" + localSipPort + ">\r\n" +
                           "X-Audio-Mode: " + currentCallMode + "\r\n" +
                           "Content-Type: application/sdp\r\n\r\n" +
                           "v=0\r\n" +
                           "o=- 123456 123457 IN IP4 " + localIP + "\r\n" +
                           "c=IN IP4 " + localIP + "\r\n" +
                           "m=audio " + localRtpPort + " RTP/AVP 0\r\n", remoteIP);
        } 
        else if (message.startsWith("SIP/2.0 200 OK") && message.contains("application/sdp")) {
            this.remoteIP = senderIP;
            sendSipMessage("ACK sip:" + remoteIP + " SIP/2.0\r\n\r\n", remoteIP);
            this.inCall = true;
            
            System.out.println(">>> Call established! Mode: " + currentCallMode.toUpperCase());
            startRtcpSender();
            startRtpReceiver(); // Caller always listens just in case
            
            // Start appropriate sender based on CLI choice
            if (this.currentCallMode.equals("file")) {
                startRtpSenderFile("sample_audio.wav");
            } else {
                startRtpSenderMic(); // For both 'mic' and 'twoway'
            }
        } 
        else if (message.startsWith("ACK")) {
            this.inCall = true;
            System.out.println(">>> Call established! Mode: " + currentCallMode.toUpperCase());
            
            startRtcpSender();
            startRtpReceiver(); // Receiver always listens to incoming audio
            
            // If it's a two-way call, the receiver must ALSO open their microphone
            if (this.currentCallMode.equals("twoway")) {
                startRtpSenderMic();
            } else {
                System.out.println("Listening to incoming one-way audio stream...");
            }
        } 
        else if (message.startsWith("BYE")) {
            System.out.println(">>> Remote hung up.");
            sendSipMessage("SIP/2.0 200 OK\r\n\r\n", senderIP);
            endCallLocally();
        }
        else if (message.matches("^SIP/2\\.0 [45]\\d{2}.*")) {
            System.err.println(">>> SIP Error received from remote: " + message.split("\r\n")[0]);
            System.out.println("Aborting call setup gracefully.");
            endCallLocally();
        } 
        else {
            System.out.println(">>> Received unexpected or unknown packet. Ignoring to prevent crash.");
        }
    }

    public void call(String targetIP, String mode) {
        try {
            this.remoteIP = targetIP;
            this.currentCallMode = mode;
            
            // Custom X-Audio-Mode header tells the receiver how to configure their streams
            String inviteMsg = "INVITE sip:" + targetIP + " SIP/2.0\r\n" +
                               "Via: SIP/2.0/UDP " + localIP + ":" + localSipPort + "\r\n" +
                               "From: <sip:caller@" + localIP + ">\r\n" +
                               "To: <sip:receiver@" + targetIP + ">\r\n" +
                               "Call-ID: " + System.currentTimeMillis() + "@" + localIP + "\r\n" +
                               "CSeq: 1 INVITE\r\n" +
                               "X-Audio-Mode: " + mode + "\r\n" +
                               "Content-Type: application/sdp\r\n\r\n" +
                               "v=0\r\n" +
                               "o=- 123456 123456 IN IP4 " + localIP + "\r\n" +
                               "c=IN IP4 " + localIP + "\r\n" +
                               "m=audio " + localRtpPort + " RTP/AVP 0\r\n";
            sendSipMessage(inviteMsg, targetIP);
            System.out.println(">>> Calling " + targetIP + " on port " + targetSipPort + " in " + mode.toUpperCase() + " mode...");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void hangUp() {
        if (inCall) {
            String byeMsg = "BYE sip:" + remoteIP + " SIP/2.0\r\n" +
                            "Call-ID: 12345@" + localIP + "\r\n" +
                            "CSeq: 2 BYE\r\n\r\n";
            sendSipMessage(byeMsg, remoteIP);
            endCallLocally();
        } else {
            System.out.println("You are not currently in a call.");
        }
    }

    private void endCallLocally() {
        inCall = false;
        System.out.println(">>> Call ended. Resources freed.");
    }

    public void sendSipMessage(String msg, String targetIP) {
        try {
            byte[] buffer = msg.getBytes();
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, InetAddress.getByName(targetIP), targetSipPort);
            sipSocket.send(packet);
        } catch (Exception e) {
            System.err.println("Failed to send SIP message: " + e.getMessage());
        }
    }
    
    // Helper function to extract our custom SIP header
    private String extractHeader(String message, String headerName, String defaultValue) {
        for (String line : message.split("\r\n")) {
            if (line.startsWith(headerName + ": ")) {
                return line.substring(headerName.length() + 2).trim();
            }
        }
        return defaultValue;
    }

    // RTP MEDIA (OVER UDP)
    // Send audio from file
    private void startRtpSenderFile(String filePath) {
        new Thread(() -> {
            try {
                File audioFile = new File(filePath);
                if (!audioFile.exists()) {
                    System.err.println("ERROR: Could not find '" + filePath + "'. Ending call.");
                    hangUp();
                    return;
                }

                AudioInputStream ais = AudioSystem.getAudioInputStream(audioFile);
                int seqNum = 0;
                long timestamp = 0;
                long ssrc = new Random().nextInt(Integer.MAX_VALUE);
                byte[] payload = new byte[320]; // 20ms of audio at 8000Hz

                System.out.println("Streaming 'sample_audio.wav' over RTP...");

                while (inCall && ais.read(payload, 0, payload.length) != -1) {
                    sendRtpPacket(payload, seqNum, timestamp, ssrc);
                    seqNum++;
                    timestamp += 160; 
                    Thread.sleep(20); // 20ms playback timing
                }
                
                ais.close();
                if (inCall) {
                    System.out.println("Audio file finished. Hanging up.");
                    hangUp();
                }
            } catch (Exception e) {
                System.err.println("RTP File Sender Error: " + e.getMessage());
            }
        }).start();
    }

    //  Send audio from live Microphone
    private void startRtpSenderMic() {
        new Thread(() -> {
            try {
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, audioFormat);
                if (!AudioSystem.isLineSupported(info)) {
                    System.err.println("Microphone not supported!");
                    return;
                }
                
                TargetDataLine microphone = (TargetDataLine) AudioSystem.getLine(info);
                microphone.open(audioFormat);
                microphone.start();
                
                int seqNum = 0;
                long timestamp = 0;
                long ssrc = new Random().nextInt(Integer.MAX_VALUE);
                byte[] payload = new byte[160]; 

                System.out.println("Microphone live. Streaming audio over RTP...");

                while (inCall) {
                    int bytesRead = microphone.read(payload, 0, payload.length);
                    if (bytesRead > 0) {
                        sendRtpPacket(payload, seqNum, timestamp, ssrc);
                        seqNum++;
                        timestamp += 160; 
                    }
                }
                microphone.stop();
                microphone.close();
            } catch (Exception e) {
                System.err.println("RTP Mic Sender Error: " + e.getMessage());
            }
        }).start();
    }
    
    // Helper function to build RTP header and send over UDP
    private void sendRtpPacket(byte[] payload, int seqNum, long timestamp, long ssrc) throws Exception {
        byte[] rtpPacket = new byte[12 + payload.length];
        rtpPacket[0] = (byte) 0x80; 
        rtpPacket[1] = (byte) 0x00; 
        rtpPacket[2] = (byte) (seqNum >> 8);
        rtpPacket[3] = (byte) (seqNum & 0xFF);
        rtpPacket[4] = (byte) (timestamp >> 24);
        rtpPacket[5] = (byte) (timestamp >> 16);
        rtpPacket[6] = (byte) (timestamp >> 8);
        rtpPacket[7] = (byte) (timestamp & 0xFF);
        rtpPacket[8] = (byte) (ssrc >> 24);
        rtpPacket[9] = (byte) (ssrc >> 16);
        rtpPacket[10] = (byte) (ssrc >> 8);
        rtpPacket[11] = (byte) (ssrc & 0xFF);
        
        System.arraycopy(payload, 0, rtpPacket, 12, payload.length);
        DatagramPacket packet = new DatagramPacket(rtpPacket, rtpPacket.length, InetAddress.getByName(remoteIP), targetRtpPort);
        rtpSocket.send(packet);
    }

    // Play incoming audio packets
    private void startRtpReceiver() {
        new Thread(() -> {
            try {
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
                SourceDataLine speakers = (SourceDataLine) AudioSystem.getLine(info);
                speakers.open(audioFormat);
                speakers.start();

                byte[] buffer = new byte[2048];
                while (inCall) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        rtpSocket.receive(packet); // Will timeout after 500ms if no data, allowing loop to check inCall status
                        
                        int payloadLength = packet.getLength() - 12; 
                        if (payloadLength > 0) {
                            speakers.write(packet.getData(), 12, payloadLength);
                        }
                    } catch (SocketTimeoutException timeout) {
                        // Expected behavior every 500ms when no audio is arriving. Allows while(inCall) to exit.
                    }
                }
                speakers.drain();
                speakers.close();
            } catch (Exception e) {
                System.err.println("RTP Receiver Error: " + e.getMessage());
            }
        }).start();
    }
    // RTCP USAGE

    private void startRtcpSender() {
        new Thread(() -> {
            try {
                int packetCount = 0;
                while (inCall) {
                    Thread.sleep(5000);  
                    String rtcpReport = "RTCP Sender Report - Packet Count Approx: " + packetCount;
                    byte[] buffer = rtcpReport.getBytes();
                    
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length, InetAddress.getByName(remoteIP), targetRtcpPort);
                    rtcpSocket.send(packet);
                    packetCount += 250; 
                }
            } catch (Exception e) {
                // Ignore sleep interruptions when call ends
            }
        }).start();
    }

    // MAIN EXECUTION
    public static void main(String[] args) {
        try {
            Scanner scanner = new Scanner(System.in);
            System.out.println("=================================================");
            System.out.println("              VoIP TESTING CLIENT                ");
            System.out.println("=================================================");
            System.out.println("Select Localhost Profile:");
            System.out.println("1 - Profile A (Uses Ports 5060, 8000)");
            System.out.println("2 - Profile B (Uses Ports 5062, 8002)");
            System.out.print("Enter choice (1 or 2): ");
            
            int choice = scanner.nextInt();
            scanner.nextLine(); // consume newline
            
            int lSip, lRtp, lRtcp, tSip, tRtp, tRtcp;
            
            if (choice == 1) {
                lSip = 5060; lRtp = 8000; lRtcp = 8001;
                tSip = 5062; tRtp = 8002; tRtcp = 8003;
            } else {
                lSip = 5062; lRtp = 8002; lRtcp = 8003;
                tSip = 5060; tRtp = 8000; tRtcp = 8001;
            }

            VoIPClient client = new VoIPClient(lSip, lRtp, lRtcp, tSip, tRtp, tRtcp);
            client.startListening();
            
            System.out.println("\n=================== COMMANDS ====================");
            System.out.println("  call <IP> file   : Streams sample_audio.wav");
            System.out.println("  call <IP> mic    : Streams your microphone one-way");
            System.out.println("  call <IP> twoway : Two-way microphone call");
            System.out.println("  hangup           : End the current call");
            System.out.println("  garbage          : Test error handling (unexpected packet)");
            System.out.println("  quit             : Shutdown program");
            System.out.println("=================================================");
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            
            while (true) {
                System.out.print("> ");
                String input = reader.readLine().trim();
                String[] parts = input.split("\\s+");
                
                if (parts[0].equalsIgnoreCase("call") && parts.length >= 2) {
                    String targetIP = parts[1];
                    String mode = (parts.length >= 3) ? parts[2].toLowerCase() : "twoway";
                    
                    if (!mode.equals("file") && !mode.equals("mic") && !mode.equals("twoway")) {
                        System.out.println("Invalid mode. Use 'file', 'mic', or 'twoway'.");
                        continue;
                    }
                    client.call(targetIP, mode);
                } 
                else if (parts[0].equalsIgnoreCase("hangup")) {
                    client.hangUp();
                } 
                else if (parts[0].equalsIgnoreCase("garbage")) {
                    System.out.println("Sending garbage packet to test error handling...");
                    client.sendSipMessage("BLAH BLAH BLAH THIS IS NOT A SIP PACKET", "127.0.0.1");
                } 
                else if (parts[0].equalsIgnoreCase("quit") || parts[0].equalsIgnoreCase("exit")) {
                    System.out.println("Shutting down VoIP Client...");
                    System.exit(0); 
                }
                else {
                    System.out.println("Unknown command. Check syntax above.");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}