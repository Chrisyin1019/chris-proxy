package hk.chris.proxy;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Crypto for the live phone-to-phone transfer: ephemeral ECDH P-256, HKDF-SHA256
 * over a transcript of (token, exporter key, importer key), a six-digit check
 * code both screens show, and AES-256-GCM frames whose AAD binds direction and
 * sequence number so frames cannot be replayed or reflected.
 */
final class TransferCrypto {
    static final int MAX_FRAME = 32 * 1024 * 1024;
    static final byte TO_IMPORTER = 1;
    static final byte TO_EXPORTER = 2;

    private TransferCrypto() {}

    static KeyPair newKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    static PublicKey decodePublic(byte[] encoded) throws Exception {
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded));
    }

    static byte[] random(int length) {
        byte[] out = new byte[length];
        new SecureRandom().nextBytes(out);
        return out;
    }

    static String otp() {
        return String.format(Locale.ROOT, "%06d", new SecureRandom().nextInt(1_000_000));
    }

    static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** Shared state of one transfer; both sides derive the same key and check code. */
    static final class Session {
        private final SecretKeySpec key;
        final String checkCode;
        private int sendSeq;
        private int receiveSeq;
        private final byte sendDirection;
        private final byte receiveDirection;

        private Session(byte[] key, String checkCode, boolean exporter) {
            this.key = new SecretKeySpec(key, "AES");
            this.checkCode = checkCode;
            this.sendDirection = exporter ? TO_IMPORTER : TO_EXPORTER;
            this.receiveDirection = exporter ? TO_EXPORTER : TO_IMPORTER;
        }

        void send(DataOutputStream out, String message) throws Exception {
            byte[] iv = random(12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(sendDirection, sendSeq++));
            byte[] sealed = cipher.doFinal(message.getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(iv);
            frame.write(sealed);
            writeFrame(out, frame.toByteArray());
        }

        String receive(DataInputStream in) throws Exception {
            byte[] frame = readFrame(in);
            if (frame.length < 12 + 16) throw new IOException("加密封包格式錯誤");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, frame, 0, 12));
            cipher.updateAAD(aad(receiveDirection, receiveSeq++));
            return new String(cipher.doFinal(frame, 12, frame.length - 12), StandardCharsets.UTF_8);
        }

        private static byte[] aad(byte direction, int seq) {
            return ByteBuffer.allocate(5).put(direction).putInt(seq).array();
        }
    }

    static Session derive(KeyPair mine, byte[] exporterPublic, byte[] importerPublic, byte[] token,
                          boolean exporter) throws Exception {
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(mine.getPrivate());
        agreement.doPhase(decodePublic(exporter ? importerPublic : exporterPublic), true);
        byte[] shared = agreement.generateSecret();
        ByteArrayOutputStream transcript = new ByteArrayOutputStream();
        transcript.write(token);
        transcript.write(exporterPublic);
        transcript.write(importerPublic);
        byte[] salt = MessageDigest.getInstance("SHA-256").digest(transcript.toByteArray());
        byte[] key = hkdf(shared, salt, "chris-proxy transfer v1 key", 32);
        byte[] check = hkdf(shared, salt, "chris-proxy transfer v1 check", 4);
        int number = ((check[0] & 0x7f) << 24 | (check[1] & 0xff) << 16 | (check[2] & 0xff) << 8 | (check[3] & 0xff)) % 1_000_000;
        Arrays.fill(shared, (byte) 0);
        return new Session(key, String.format(Locale.ROOT, "%06d", number), exporter);
    }

    static byte[] hkdf(byte[] ikm, byte[] salt, String info, int length) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] previous = new byte[0];
        for (int i = 1; out.size() < length; i++) {
            mac.update(previous);
            mac.update(info.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) i);
            previous = mac.doFinal();
            out.write(previous);
        }
        return Arrays.copyOf(out.toByteArray(), length);
    }

    static void writeFrame(DataOutputStream out, byte[] data) throws IOException {
        out.writeInt(data.length);
        out.write(data);
        out.flush();
    }

    static byte[] readFrame(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_FRAME) throw new IOException("封包大小異常");
        byte[] data = new byte[length];
        in.readFully(data);
        return data;
    }
}
