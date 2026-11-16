package hk.chris.proxy;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;

/**
 * Gate in front of every export, the same approach as Chris Secure's native
 * management: a P-256 key in Android Keystore (StrongBox when available)
 * that can only sign after a fresh biometric or screen-lock check, and the
 * export proceeds only when that per-use signature actually succeeds — not on
 * a bare "authenticated" callback.
 */
public final class ExportGate {
    private static final String ALIAS = "chrisproxy.export.gate.v1";
    private static final String KEYSTORE = "AndroidKeyStore";

    public interface Granted { void run(); }

    private ExportGate() {}

    private static int authenticators() {
        return Build.VERSION.SDK_INT >= 30
                ? BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL
                : BiometricManager.Authenticators.BIOMETRIC_STRONG;
    }

    public static void authenticate(FragmentActivity activity, String title, Granted granted) {
        int status = BiometricManager.from(activity).canAuthenticate(authenticators());
        if (status != BiometricManager.BIOMETRIC_SUCCESS) {
            android.widget.Toast.makeText(activity, "請先在系統設定螢幕鎖或指紋，才能導出設定",
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        Signature signature;
        try {
            signature = signature(activity, true);
        } catch (Exception e) {
            AppLogger.log(activity, "ERROR", "導出驗證金鑰無法使用=" + e.getClass().getSimpleName());
            android.widget.Toast.makeText(activity, "無法使用安全金鑰：" + e.getClass().getSimpleName(),
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        BiometricPrompt.PromptInfo.Builder info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle("導出內容包含節點密碼，請確認是你本人")
                .setAllowedAuthenticators(authenticators());
        if (Build.VERSION.SDK_INT < 30) info.setNegativeButtonText("取消");
        BiometricPrompt prompt = new BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        try {
                            Signature signer = result.getCryptoObject() == null ? null : result.getCryptoObject().getSignature();
                            if (signer == null) throw new IllegalStateException("no signature");
                            signer.update(TransferCrypto.random(32));
                            signer.sign();
                            AppLogger.log(activity, "INFO", "導出驗證通過");
                            granted.run();
                        } catch (Exception e) {
                            AppLogger.log(activity, "WARN", "導出驗證簽章失敗=" + e.getClass().getSimpleName());
                        }
                    }

                    @Override public void onAuthenticationError(int code, CharSequence message) {
                        if (code != BiometricPrompt.ERROR_USER_CANCELED && code != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                                && code != BiometricPrompt.ERROR_CANCELED) {
                            android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG).show();
                        }
                    }
                });
        prompt.authenticate(info.build(), new BiometricPrompt.CryptoObject(signature));
    }

    private static Signature signature(Context context, boolean retry) throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        if (!store.containsAlias(ALIAS)) generate();
        try {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign((PrivateKey) store.getKey(ALIAS, null));
            return signature;
        } catch (KeyPermanentlyInvalidatedException e) {
            // Biometric enrollment changed; the old key is gone for good. Make a new one.
            store.deleteEntry(ALIAS);
            AppLogger.log(context, "WARN", "生物辨識變更，已重建導出驗證金鑰");
            if (!retry) throw e;
            return signature(context, false);
        }
    }

    private static void generate() throws Exception {
        if (Build.VERSION.SDK_INT < 28) {
            generate(false);
            return;
        }
        try {
            generate(true);
        } catch (java.security.ProviderException e) {
            // StrongBoxUnavailableException (a ProviderException): fall back to TEE.
            generate(false);
        }
    }

    private static void generate(boolean strongBox) throws Exception {
        KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(true);
        if (Build.VERSION.SDK_INT >= 30) {
            spec.setUserAuthenticationParameters(0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL);
        }
        if (Build.VERSION.SDK_INT >= 28 && strongBox) spec.setIsStrongBoxBacked(true);
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE);
        generator.initialize(spec.build());
        generator.generateKeyPair();
    }
}
