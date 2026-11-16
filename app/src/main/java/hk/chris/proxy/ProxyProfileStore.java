package hk.chris.proxy;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Keystore-backed at-rest storage. SharedPreferences contains ciphertext only. */
public final class ProxyProfileStore {
    private static final String PREFS = "proxy_profiles";
    private static final String DATA = "encrypted_data";
    private static final String SELECTED = "selected_index";
    private static final String KEY_ALIAS = "chrisproxy.profile.aes.v1";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    private ProxyProfileStore() {}

    public static List<ProxyProfile> load(Context context) {
        ArrayList<ProxyProfile> result = new ArrayList<>();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String packed = prefs.getString(DATA, "");
        if (packed == null || packed.isEmpty()) return result;
        try {
            String clear = decrypt(packed);
            for (String record : clear.split("\\n\\n", -1)) {
                if (!record.trim().isEmpty()) result.add(ProxyProfileParser.parse(record));
            }
        } catch (Exception ignored) {
            // A revoked/invalidated key must not expose or silently recreate secrets.
        }
        return result;
    }

    public static void add(Context context, ProxyProfile profile) {
        List<ProxyProfile> profiles = load(context);
        profiles.add(profile);
        replace(context, profiles);
    }

    public static void replace(Context context, List<ProxyProfile> profiles) {
        StringBuilder clear = new StringBuilder();
        for (ProxyProfile item : profiles) {
            if (clear.length() > 0) clear.append("\n\n");
            clear.append(item.rawConfig);
        }
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(DATA, encrypt(clear.toString())).apply();
        } catch (Exception e) {
            throw new IllegalStateException("無法使用 Android Keystore 加密保存節點", e);
        }
        int selected = selectedIndex(context);
        if (profiles.isEmpty()) setSelectedIndex(context, 0);
        else if (selected >= profiles.size()) setSelectedIndex(context, profiles.size() - 1);
    }

    /** Removes one node and keeps the node in use pointing at the same entry. */
    public static void remove(Context context, int index) {
        List<ProxyProfile> profiles = load(context);
        if (index < 0 || index >= profiles.size()) return;
        int selected = selectedIndex(context);
        profiles.remove(index);
        replace(context, profiles);
        if (index < selected) setSelectedIndex(context, selected - 1);
        else if (index == selected) setSelectedIndex(context, 0);
    }

    public static int selectedIndex(Context context) {
        int selected = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(SELECTED, 0);
        List<ProxyProfile> profiles = load(context);
        return profiles.isEmpty() ? 0 : Math.max(0, Math.min(selected, profiles.size() - 1));
    }

    public static void setSelectedIndex(Context context, int index) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(SELECTED, Math.max(0, index)).apply();
    }

    public static ProxyProfile selected(Context context) {
        List<ProxyProfile> profiles = load(context);
        return profiles.isEmpty() ? null : profiles.get(selectedIndex(context));
    }

    /** Plaintext is returned only for an explicit user-selected export operation. */
    public static String exportPlaintext(Context context) {
        StringBuilder clear = new StringBuilder();
        for (ProxyProfile item : load(context)) {
            if (clear.length() > 0) clear.append("\n\n");
            clear.append(item.rawConfig);
        }
        return clear.toString();
    }

    /** Keystore AES-GCM for other secrets at rest (subscription URLs carry tokens). */
    static String seal(String clear) {
        try {
            return encrypt(clear);
        } catch (Exception e) {
            throw new IllegalStateException("無法使用 Android Keystore 加密", e);
        }
    }

    static String unseal(String packed) {
        try {
            return decrypt(packed);
        } catch (Exception e) {
            return "";
        }
    }

    private static String encrypt(String clear) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(clear.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        packed.write(cipher.getIV().length);
        packed.write(cipher.getIV());
        packed.write(encrypted);
        return Base64.getEncoder().encodeToString(packed.toByteArray());
    }

    private static String decrypt(String packedText) throws Exception {
        byte[] packed = Base64.getDecoder().decode(packedText);
        int ivLength = packed[0] & 0xff;
        byte[] iv = java.util.Arrays.copyOfRange(packed, 1, 1 + ivLength);
        byte[] encrypted = java.util.Arrays.copyOfRange(packed, 1 + ivLength, packed.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
