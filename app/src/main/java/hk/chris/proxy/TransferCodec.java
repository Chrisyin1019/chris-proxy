package hk.chris.proxy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Seals a transfer bundle with a one-time transfer code.
 * Layout: "CPX1" | salt(16) | iv(12) | AES-256-GCM(deflate(json)).
 * The key is PBKDF2-HMAC-SHA256 of the code; the code never travels with
 * the data, so a photographed QR or a leaked file is useless without it.
 */
public final class TransferCodec {
    public static final String QR_PREFIX = "CPX1:";
    private static final byte[] MAGIC = {'C', 'P', 'X', '1'};
    /** No 0/O, 1/I/L: the code is read off one screen and typed on another. */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 10;
    private static final int ITERATIONS = 310_000;
    private static final int MAX_INFLATED = 32 * 1024 * 1024;

    private TransferCodec() {}

    public static final class WrongCodeException extends Exception {
        WrongCodeException() { super("轉移碼不正確"); }
    }

    public static String newCode() {
        SecureRandom random = new SecureRandom();
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < CODE_LENGTH; i++) code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return code.toString();
    }

    /** "ABCDE23456" → "ABCDE-23456" for display. */
    public static String format(String code) {
        return code.substring(0, 5) + "-" + code.substring(5);
    }

    /** Accepts lower case, spaces and dashes as typed by a person. */
    public static String normalize(String typed) {
        return typed == null ? "" : typed.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    public static boolean isSealed(byte[] data) {
        return data != null && data.length > MAGIC.length + 28
                && Arrays.equals(Arrays.copyOf(data, MAGIC.length), MAGIC);
    }

    public static byte[] seal(String json, String code) throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        byte[] iv = new byte[12];
        random.nextBytes(salt);
        random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(code, salt), new GCMParameterSpec(128, iv));
        cipher.updateAAD(MAGIC);
        byte[] sealed = cipher.doFinal(deflate(json.getBytes(StandardCharsets.UTF_8)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(MAGIC);
        out.write(salt);
        out.write(iv);
        out.write(sealed);
        return out.toByteArray();
    }

    public static String open(byte[] data, String code) throws Exception {
        if (!isSealed(data)) throw new IllegalArgumentException("不是 Chris Proxy 轉移資料");
        byte[] salt = Arrays.copyOfRange(data, 4, 20);
        byte[] iv = Arrays.copyOfRange(data, 20, 32);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(normalize(code), salt), new GCMParameterSpec(128, iv));
        cipher.updateAAD(MAGIC);
        try {
            return new String(inflate(cipher.doFinal(data, 32, data.length - 32)), StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new WrongCodeException();
        }
    }

    public static String toQr(byte[] sealed) {
        return QR_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(sealed);
    }

    public static byte[] fromQr(String text) {
        String value = text == null ? "" : text.trim();
        if (!value.startsWith(QR_PREFIX)) throw new IllegalArgumentException("不是 Chris Proxy 轉移 QR");
        return Base64.getUrlDecoder().decode(value.substring(QR_PREFIX.length()));
    }

    private static SecretKeySpec key(String code, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(normalize(code).toCharArray(), salt, ITERATIONS, 256);
        try {
            byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new SecretKeySpec(raw, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(input);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
        deflater.end();
        return out.toByteArray();
    }

    private static byte[] inflate(byte[] input) throws DataFormatException {
        Inflater inflater = new Inflater();
        inflater.setInput(input);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                out.write(buffer, 0, n);
                if (out.size() > MAX_INFLATED) throw new DataFormatException("轉移資料過大");
            }
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }
}
