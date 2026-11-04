package dev.mibox.taskmanager;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;

/**
 * Minimal client for the adb wire protocol, used to run shell commands on the box's own adbd
 * (127.0.0.1:5555). Shell-level access is what lets the app read every process's memory and CPU
 * and run "am force-stop", neither of which a normal app is allowed to do.
 *
 * The first connection with a new key makes the TV show "Allow network debugging?"; with
 * "Always allow" ticked, later connections authenticate silently with the stored key.
 */
final class AdbClient implements Closeable {

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;

    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;

    private static final int VERSION = 0x01000000;
    private static final int MAX_DATA = 256 * 1024;

    // DER prefix of a SHA-1 DigestInfo: adbd verifies the token as if it were a SHA-1 digest.
    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    /** Thrown while the "Allow network debugging?" prompt is waiting on the TV. */
    static final class AuthPendingException extends IOException {
        AuthPendingException() { super("Waiting for the debugging prompt on the TV"); }
    }

    private static final class Message {
        int command, arg0, arg1;
        byte[] data;
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextLocalId = 1;

    /**
     * Connects and authenticates. Blocks up to {@code authWaitMs} for the user to accept the key
     * on the TV when the device doesn't know it yet.
     */
    AdbClient(String host, int port, KeyPair keys, String keyName, int authWaitMs, Runnable onPrompt)
            throws IOException, GeneralSecurityException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 3000);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(10000);
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        out = new BufferedOutputStream(socket.getOutputStream());

        try {
            send(A_CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.US_ASCII));
            boolean sentSignature = false, sentKey = false;
            while (true) {
                Message m = read();
                if (m.command == A_CNXN) break;
                if (m.command != A_AUTH || m.arg0 != AUTH_TOKEN) {
                    throw new IOException("Unexpected adb message " + Integer.toHexString(m.command));
                }
                if (!sentSignature) {
                    send(A_AUTH, AUTH_SIGNATURE, 0, sign(keys.getPrivate(), m.data));
                    sentSignature = true;
                } else if (!sentKey) {
                    // Unknown key: offer it, which shows the prompt on the TV.
                    send(A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyPayload((RSAPublicKey) keys.getPublic(), keyName));
                    sentKey = true;
                    if (onPrompt != null) onPrompt.run();
                    socket.setSoTimeout(authWaitMs);
                    try {
                        m = read();
                    } catch (java.net.SocketTimeoutException e) {
                        throw new AuthPendingException();
                    }
                    if (m.command == A_CNXN) break;
                    throw new IOException("Debugging prompt was declined");
                }
            }
            socket.setSoTimeout(30000);
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            close();
            throw e;
        }
    }

    /** Runs a shell command and returns its combined output with \r removed. */
    synchronized String exec(String command) throws IOException {
        int local = nextLocalId++;
        send(A_OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int remote = 0;
        while (true) {
            Message m = read();
            if (m.arg1 != local) continue; // leftover from an earlier stream
            if (m.command == A_OKAY) {
                remote = m.arg0;
            } else if (m.command == A_WRTE) {
                buf.write(m.data);
                send(A_OKAY, local, m.arg0, null);
            } else if (m.command == A_CLSE) {
                if (remote != 0) send(A_CLSE, local, remote, null);
                return new String(buf.toByteArray(), StandardCharsets.UTF_8).replace("\r", "");
            }
        }
    }

    @Override
    public void close() {
        try { socket.close(); } catch (IOException ignored) { }
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        int length = data == null ? 0 : data.length;
        int checksum = 0;
        for (int i = 0; i < length; i++) checksum += data[i] & 0xff;
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(command).putInt(arg0).putInt(arg1).putInt(length).putInt(checksum).putInt(~command);
        out.write(header.array());
        if (length > 0) out.write(data);
        out.flush();
    }

    private Message read() throws IOException {
        byte[] raw = new byte[24];
        in.readFully(raw);
        ByteBuffer header = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = header.getInt();
        m.arg0 = header.getInt();
        m.arg1 = header.getInt();
        int length = header.getInt();
        header.getInt(); // checksum: not checked, TCP already does
        if (header.getInt() != ~m.command) throw new IOException("Corrupt adb header");
        if (length < 0 || length > MAX_DATA * 4) throw new IOException("Bad adb payload length " + length);
        m.data = new byte[length];
        in.readFully(m.data);
        return m;
    }

    private static byte[] sign(PrivateKey key, byte[] token) throws GeneralSecurityException {
        Signature s = Signature.getInstance("NONEwithRSA");
        s.initSign(key);
        s.update(SHA1_DIGEST_INFO);
        s.update(token);
        return s.sign();
    }

    /** The key in adb's own format (mincrypt RSAPublicKey struct), base64 plus a name. */
    private static byte[] publicKeyPayload(RSAPublicKey key, String name) {
        BigInteger n = key.getModulus();
        int bytes = 256; // 2048-bit key
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(bytes * 8 * 2).mod(n);

        ByteBuffer b = ByteBuffer.allocate(4 + 4 + bytes + bytes + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(bytes / 4);
        b.putInt(n0inv.intValue());
        putLittleEndian(b, n, bytes);
        putLittleEndian(b, rr, bytes);
        b.putInt(key.getPublicExponent().intValue());
        String text = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " " + name + "\0";
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static void putLittleEndian(ByteBuffer b, BigInteger value, int size) {
        byte[] be = value.toByteArray(); // big-endian, maybe with a leading 0 sign byte
        for (int i = 0; i < size; i++) {
            int idx = be.length - 1 - i;
            b.put(idx >= 0 ? be[idx] : 0);
        }
    }
}
