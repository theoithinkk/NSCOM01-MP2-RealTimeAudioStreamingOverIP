import javax.sound.sampled.*;
import java.io.File;
import java.net.*;
import java.util.Random;
import java.util.Scanner;


public class VoIPClient {
    
    // NETWORKING SOCKETS 
    private DatagramSocket sipSocket, rtpSocket, rtcpSocket;
    
    // Local ports (Where we listen)
    private int localSip, localRtp, localRtcp;
    
    // Target ports (Where we send data - learned dynamically via SDP)
    private int targetRtp, targetRtcp; 
    
    private String localIP = "127.0.0.1";
    private String remoteIP = "";
    
    // CALL STATE MANAGEMENT
    private volatile boolean inCall = false;      // True if audio is actively flowing
    private volatile boolean isRinging = false;   // True if waiting for someone to type 'answer'
    private boolean isCaller = false;             // True if call was intiated
    
    private String currentCallMode = "twoway";    // Can be 'file', 'mic', or 'twoway'
    private String callId = "";                   // Unique ID to track the current SIP session
    private long mySSRC;                          // Synchronization Source ID (links RTP and RTCP)
    
    // Holds the raw INVITE message so we can process it later when the user types 'answer'
    private String pendingInviteMsg = "";
    private int pendingSenderPort = 0;
    
    // AUDIO CONFIGURATION 
    // Standard RFC payload type 11 (L16): 8000Hz, 16-bit, Mono, Signed, Little-Endian
    private AudioFormat audioFormat = new AudioFormat(8000.0f, 16, 1, true, false);
    private Clip ringingClip; // Background player for the ringing sound effect

    
    // Constructor: Initializes the client and binds the UDP sockets to the local ports.
    public VoIPClient(int lSip, int lRtp) throws Exception {
        this.localSip = lSip; 
        this.localRtp = lRtp; 
        this.localRtcp = lRtp + 1; // RTCP is always RTP port + 1
        
        // Generate a random unique identifier for our audio stream
        this.mySSRC = new Random().nextInt(Integer.MAX_VALUE);
        
        this.sipSocket = new DatagramSocket(localSip);
        this.rtpSocket = new DatagramSocket(localRtp);
        this.rtcpSocket = new DatagramSocket(localRtcp);
        
        // Prevents the RTP receiver from freezing the program if the other person hangs up abruptly
        this.rtpSocket.setSoTimeout(1000); 
        System.out.println("[READY] SIP: " + localSip + " | RTP: " + localRtp);
    }


     //Background thread that constantly listens for incoming SIP UDP packets.

    public void startListening() {
        new Thread(() -> {
            try {
                while (true) {
                    DatagramPacket packet = new DatagramPacket(new byte[2048], 2048);
                    sipSocket.receive(packet); // Blocks here until a packet arrives
                    
                    String msg = new String(packet.getData(), 0, packet.getLength());
                    String senderIP = packet.getAddress().getHostAddress();
                    int senderPort = packet.getPort(); // Captures the exact port the peer used
                    
                    System.out.println("\n[SIP] <<< RECEIVED FROM " + senderPort + ":\n" + msg.trim());
                    handleSipMessage(msg, senderIP, senderPort);
                }
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    
     //The "Brain" of the SIP Protocol. Routes messages based on their type (INVITE, OK, ACK, BYE).
    private void handleSipMessage(String msg, String senderIP, int senderPort) throws Exception {
        
        //  SCENARIO 1: SOMEONE IS CALLING US 
        if (msg.startsWith("INVITE")) {
            this.remoteIP = senderIP;
            this.currentCallMode = extractHeader(msg, "X-Audio-Mode", "twoway");
            this.callId = extractHeader(msg, "Call-ID", "12345");
            this.isCaller = false; // We are the receiver
            
            parseSDP(msg); // Learn where the caller wants us to send our audio
            
            // Save the incoming request so we can respond to it later
            this.pendingInviteMsg = msg;
            this.pendingSenderPort = senderPort;
            this.isRinging = true;
            
            System.out.println("\n=============================================");
            System.out.println(">>> INCOMING " + currentCallMode.toUpperCase() + " CALL FROM " + senderPort);
            System.out.println(">>> Type 'answer' to accept or 'hangup' to reject.");
            System.out.println("=============================================");
            
            startRinging("ringtone.wav"); // Play local ringing sound
            startTimeoutCountdown();      // Start the 15-second missed call timer
        } 
        
        //  SCENARIO 2: THEY ACCEPTED OUR CALL 
        else if (msg.startsWith("SIP/2.0 200 OK") && msg.contains("application/sdp")) {
            this.isRinging = false; // Stop the 15-second timer
            stopRinging();          // Stop the ringback tone
            
            parseSDP(msg); // Learn where the receiver wants us to send our audio
            
            // Build the final handshake confirmation (ACK)
            String via = extractHeader(msg, "Via", "");
            String ack = "ACK sip:peer SIP/2.0\r\n" + via + "\r\n" +
                         "Call-ID: " + callId + "\r\nCSeq: 1 ACK\r\n\r\n";
            
            sendSip(ack, remoteIP, senderPort);
            startMediaThreads(); // Handshake done and start playing audio
        } 
        
        // SCENARIO 3: THEY CONFIRMED OUR ACCEPTANCE 
        else if (msg.startsWith("ACK")) {
            startMediaThreads();
        } 
        
        //  SCENARIO 4: THEY HUNG UP 
        else if (msg.startsWith("BYE")) {
            System.out.println("[SIP] Peer hung up or canceled the call.");
            String via = extractHeader(msg, "Via", "");
            
            // Confirm we received the teardown request
            String ok = "SIP/2.0 200 OK\r\n" + via + "\r\nCall-ID: " + callId + "\r\nCSeq: 2 BYE\r\n\r\n";
            sendSip(ok, remoteIP, senderPort);
            endCallLocally(); // Shut down local audio threads
        }
    }

    public void answerCall() {
        if (!isRinging || isCaller) {
            System.out.println("[DEBUG] No incoming call to answer right now.");
            return;
        }
        
        System.out.println("[STATE] Accepting call. Sending 200 OK...");
        this.isRinging = false; 
        stopRinging();
        
        // Build the 200 OK using the data we saved from the pending INVITE
        String via = extractHeader(pendingInviteMsg, "Via", "");
        String ok = "SIP/2.0 200 OK\r\n" + via + "\r\n" +
                    extractHeader(pendingInviteMsg, "From", "") + "\r\n" +
                    extractHeader(pendingInviteMsg, "To", "") + ";tag=456\r\n" +
                    "Call-ID: " + callId + "\r\n" +
                    "CSeq: 1 INVITE\r\n" +
                    "Content-Type: application/sdp\r\n\r\n" + generateSDP(); 
        
        sendSip(ok, remoteIP, pendingSenderPort); 
    }

    // Initiates an outbound call by sending an INVITE packet.
    public void call(String tIP, int tSip, String mode) {
        this.remoteIP = tIP;
        this.currentCallMode = mode;
        this.callId = System.currentTimeMillis() + "@" + localIP; // Generate unique session ID
        this.isCaller = true; 
        this.isRinging = true; 
        
        // Build the INVITE packet with embedded SDP (our local media capabilities)
        String invite = "INVITE sip:" + tIP + " SIP/2.0\r\n" +
                        "Via: SIP/2.0/UDP " + localIP + ":" + localSip + "\r\n" +
                        "From: <sip:caller@loc>;tag=123\r\nTo: <sip:receiver@loc>\r\n" +
                        "Call-ID: " + callId + "\r\nCSeq: 1 INVITE\r\n" +
                        "X-Audio-Mode: " + mode + "\r\n" +
                        "Content-Type: application/sdp\r\n\r\n" + generateSDP();
        
        sendSip(invite, tIP, tSip);
        System.out.println("[DEBUG] Calling... Waiting for peer to answer.");
        
        startRinging("ringtone.wav"); 
        startTimeoutCountdown();  
    }

    // A background thread that waits 15 seconds. If the call isn't answered, it cancels it.
    private void startTimeoutCountdown() {
        String currentCall = this.callId; // Lock onto this specific call attempt
        new Thread(() -> {
            try {
                int seconds = 0;
                // Check every second. If 'answer' is typed, isRinging becomes false and the loop breaks early.
                while (seconds < 15 && isRinging && callId.equals(currentCall)) {
                    Thread.sleep(1000);
                    seconds++;
                }
                
                // If 15 seconds pass and we are STILL ringing...
                if (isRinging && callId.equals(currentCall)) {
                    System.out.println("\n[TIMEOUT] 15 seconds elapsed without an answer.");
                    if (isCaller) {
                        System.out.println("[DEBUG] Canceling outbound call...");
                        hangUp(); // Send BYE to cancel the attempt
                    } else {
                        System.out.println("[DEBUG] Missed call. Stopping ringtone...");
                        endCallLocally(); // Just stop the local ringing
                    }
                }
            } catch (Exception e) {}
        }).start();
    }

    
     // Ends an active call by sending a BYE message to the peer.
    public void hangUp() {
        if (!inCall && !isRinging) return;
        int targetSip = (localSip == 5060) ? 5062 : 5060; 
        String bye = "BYE sip:peer SIP/2.0\r\nCall-ID: " + callId + "\r\nCSeq: 2 BYE\r\n\r\n";
        sendSip(bye, remoteIP, targetSip);
        endCallLocally();
    }

    /**
     * Cleans up local resources (stops audio loops, resets variables).
     */
    private void endCallLocally() {
        stopRinging();
        inCall = false;      // This breaks all the 'while(inCall)' audio thread loops
        isRinging = false;
        isCaller = false;
        this.mySSRC = new Random().nextInt(Integer.MAX_VALUE); // Refresh SSRC for the next call
        System.out.println("[STATE] Cleared. Ready for next action.");
    }
    
    
     // Tells the peer exactly which IP and Port we are using for RTP audio.
    private String generateSDP() {
        return "v=0\r\no=- 123 1 IN IP4 " + localIP + "\r\n" +
               "c=IN IP4 " + localIP + "\r\n" +
               "m=audio " + localRtp + " RTP/AVP 11\r\n"; // AVP 11 = L16 Audio
    }

     // Reads the peer's SDP message to figure out where we should send our audio.
    private void parseSDP(String msg) {
        for (String line : msg.split("\r\n")) {
            if (line.startsWith("m=audio ")) {
                this.targetRtp = Integer.parseInt(line.split(" ")[1]);
                this.targetRtcp = this.targetRtp + 1; // RTCP is always RTP port + 1
            }
        }
    }

    private String extractHeader(String msg, String header, String def) {
        for (String line : msg.split("\r\n")) if (line.startsWith(header + ": ")) return line.substring(header.length() + 2).trim();
        return def;
    }

    public void sendSip(String msg, String ip, int port) {
        try { sipSocket.send(new DatagramPacket(msg.getBytes(), msg.length(), InetAddress.getByName(ip), port)); } 
        catch (Exception e) {}
    }

    private void startMediaThreads() {
        if (inCall) return;
        inCall = true;
        System.out.println("[DEBUG] Audio Streams starting... Mode: " + currentCallMode.toUpperCase());
        
        startRtcpSender();
        startRtpReceiver(); // Everyone always turns on their speakers to listen
        
        if (isCaller) {
            // The Caller always transmits audio
            if (currentCallMode.equals("file")) startRtpSenderFile("sample_audio.wav");
            else {
                System.out.println("[DEBUG] [MEDIA] You are the Caller. Mic is ON.");
                startRtpSenderMic(); 
            }
        } else {
            // The Receiver ONLY transmits audio if it's a Two-Way call
            if (currentCallMode.equals("twoway")) {
                System.out.println("[DEBUG] [MEDIA] Two-Way Call. Mic is ON.");
                startRtpSenderMic();
            } else {
                System.out.println("[DEBUG] [MEDIA] Receiver Mode: LISTENING ONLY. Mic is OFF.");
            }
        }
    }


    private void sendRtpPacket(byte[] payload, int seq, long ts) throws Exception {
        byte[] rtp = new byte[12 + payload.length];
        
        rtp[0] = (byte) 0x80; // V=2 (RTP Version 2)
        rtp[1] = (byte) 11;   // PT=11 (Payload Type: L16 Audio)
        
        // Sequence Number (2 bytes)
        rtp[2] = (byte) (seq >> 8); rtp[3] = (byte) (seq & 0xFF);
        
        // Timestamp (4 bytes)
        rtp[4] = (byte) (ts >> 24); rtp[5] = (byte) (ts >> 16); 
        rtp[6] = (byte) (ts >> 8);  rtp[7] = (byte) (ts & 0xFF);
        
        // SSRC Identifier (4 bytes)
        rtp[8] = (byte) (mySSRC >> 24); rtp[9] = (byte) (mySSRC >> 16); 
        rtp[10] = (byte) (mySSRC >> 8); rtp[11] = (byte) (mySSRC & 0xFF);
        
        // Copy audio payload right after the 12-byte header
        System.arraycopy(payload, 0, rtp, 12, payload.length);
        
        // Send to the dynamically negotiated port!
        rtpSocket.send(new DatagramPacket(rtp, rtp.length, InetAddress.getByName(remoteIP), targetRtp));
    }

 
    private void startRtpSenderFile(String path) {
        new Thread(() -> {
            try {
                File f = new File(path);
                if (!f.exists()) return;
                AudioInputStream ais = AudioSystem.getAudioInputStream(f);
                int seq = 0; long ts = 0; byte[] buf = new byte[320]; // 320 bytes = 20ms of audio
                
                while (inCall && ais.read(buf, 0, buf.length) != -1) {
                    sendRtpPacket(buf, seq++, ts); 
                    ts += 160; // Advance timestamp by 160 samples (8000Hz * 0.02s)
                    Thread.sleep(20); // Maintain real-time playback speed
                }
                ais.close(); 
                if (inCall) hangUp(); // Auto hangup when file finishes
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    
     //Captures live microphone input and sends it over RTP.
    
    private void startRtpSenderMic() {
        new Thread(() -> {
            try {
                TargetDataLine mic = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, audioFormat));
                mic.open(audioFormat); mic.start();
                int seq = 0; long ts = 0; byte[] buf = new byte[320];
                
                while (inCall) { 
                    if (mic.read(buf, 0, buf.length) > 0) { 
                        sendRtpPacket(buf, seq++, ts); 
                        ts += 160; 
                    } 
                }
                mic.stop(); mic.close();
            } catch (Exception e) {}
        }).start();
    }

    
     //Listens for incoming RTP packets, strips the 12-byte header, and plays the audio to speakers.
    private void startRtpReceiver() {
        new Thread(() -> {
            try {
                SourceDataLine spk = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, audioFormat));
                spk.open(audioFormat); spk.start();
                byte[] buf = new byte[2048];
                
                while (inCall) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        rtpSocket.receive(p);
                        
                        // Ignore the first 12 bytes (the header), play the rest (the payload)
                        if (p.getLength() > 12) spk.write(p.getData(), 12, p.getLength() - 12);
                    } catch (Exception e) {}
                }
                spk.flush(); spk.close(); // Clears ghost audio when call ends
            } catch (Exception e) {}
        }).start();
    }

    
     // Periodically sends an RFC 3550 standard RTCP Sender Report (PT 200).
    private void startRtcpSender() {
        new Thread(() -> {
            try {
                int pCount = 0; // Packet count and Octet count
                while (inCall) {
                    Thread.sleep(5000); // Send report every 5 seconds
                    
                    byte[] rtcp = new byte[28];
                    rtcp[0] = (byte)0x80; // V=2
                    rtcp[1] = (byte)0xC8; // PT=200 (Sender Report)
                    rtcp[3] = 0x06;       // Length
                    
                    // SSRC (Matches the RTP audio stream so Wireshark links them)
                    rtcp[4] = (byte)(mySSRC>>24); rtcp[5] = (byte)(mySSRC>>16); 
                    rtcp[6] = (byte)(mySSRC>>8);  rtcp[7] = (byte)(mySSRC&0xFF);
                    
                    // Add packet count to the report
                    rtcp[22] = (byte)(pCount>>8); rtcp[23] = (byte)(pCount&0xFF);
                    
                    rtcpSocket.send(new DatagramPacket(rtcp, rtcp.length, InetAddress.getByName(remoteIP), targetRtcp));
                    
                    pCount += 250; // Estimate: 50 packets per sec * 5 seconds = 250
                }
            } catch (Exception e) {}
        }).start();
    }
    // Ringing logic
    private void startRinging(String path) {
        try {
            AudioInputStream ais = AudioSystem.getAudioInputStream(new File(path));
            ringingClip = AudioSystem.getClip(); 
            ringingClip.open(ais);
            ringingClip.loop(Clip.LOOP_CONTINUOUSLY); // Keep ringing until someone answers
            ringingClip.start();
        } catch (Exception e) {}
    }

    private void stopRinging() { 
        if (ringingClip != null && ringingClip.isRunning()) { 
            ringingClip.stop(); 
            ringingClip.close(); 
        } 
    }

// Main
    public static void main(String[] args) {
        try {
            Scanner sc = new Scanner(System.in);
            System.out.print("Select Profile: 1 (Ports 5060/8000) or 2 (Ports 5062/8002): ");
            int ch = sc.nextInt(); sc.nextLine();
            
            // Assign ports based on profile choice
            int lSip = (ch == 1) ? 5060 : 5062;
            int lRtp = (ch == 1) ? 8000 : 8002;
            int dSip = (ch == 1) ? 5062 : 5060; // Default target port to make calling easier

            VoIPClient client = new VoIPClient(lSip, lRtp);
            client.startListening();

            System.out.println("\n--- DYNAMIC VOIP TERMINAL ---");
            System.out.println("1. Call (File Mode)");
            System.out.println("2. Call (Mic Mode - ONE WAY)");
            System.out.println("3. Call (Two-Way - FULL DUPLEX)");
            System.out.println("4. Answer incoming call");
            System.out.println("5. Hangup / Reject");

            // Main input loop
            while (true) {
                System.out.print("> ");
                String input = sc.nextLine().trim();
                if (input.equals("1")) client.call("127.0.0.1", dSip, "file");
                else if (input.equals("2")) client.call("127.0.0.1", dSip, "mic");
                else if (input.equals("3")) client.call("127.0.0.1", dSip, "twoway");
                else if (input.equals("4") || input.equalsIgnoreCase("answer")) client.answerCall();
                else if (input.equals("5") || input.equalsIgnoreCase("hangup")) client.hangUp();
                else if (input.equalsIgnoreCase("quit")) System.exit(0);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }
}