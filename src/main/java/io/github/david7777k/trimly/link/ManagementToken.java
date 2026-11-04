package io.github.david7777k.trimly.link;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Per-link bearer capability. Only its digest is stored. */
public final class ManagementToken {
    private static final SecureRandom RANDOM = new SecureRandom();
    private ManagementToken() { }

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean matches(String token, String digest) {
        return token != null && token.length() == 43 && digest != null
                && MessageDigest.isEqual(hash(token).getBytes(StandardCharsets.US_ASCII),
                        digest.getBytes(StandardCharsets.US_ASCII));
    }
}
