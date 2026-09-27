package io.github.david7777k.trimly.link;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Produces short codes as random Base62 strings.
 *
 * <p>Random rather than an encoded id. Sequential codes are guessable - having
 * created one you can walk to your neighbours' links - and they leak how many
 * links the service holds. Random codes collide instead, which is a problem
 * with a known fix: the unique index rejects a duplicate and the caller retries.
 *
 * <p>Not a hash of the target either. That would make the same URL always yield
 * the same code, which deduplicates for free but also lets anyone test whether a
 * particular address has been shortened.
 *
 * <p>Base62 because the result goes into a URL unescaped; Base64 uses
 * {@code + / =}, which do not.
 */
@Component
public class CodeGenerator {

    private static final char[] ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    /**
     * SecureRandom, not Random. Random is a linear congruential generator: a
     * handful of outputs is enough to recover its state and predict the rest,
     * which would make codes guessable for exactly the reason sequential ones
     * were rejected.
     */
    private final SecureRandom random = new SecureRandom();

    private final int codeLength;

    public CodeGenerator(@Value("${trimly.code-length:7}") int codeLength) {
        if (codeLength < 4 || codeLength > 16) {
            throw new IllegalArgumentException(
                    "code length must be between 4 and 16, was " + codeLength);
        }
        this.codeLength = codeLength;
    }

    public String generate() {
        char[] code = new char[codeLength];
        for (int i = 0; i < codeLength; i++) {
            code[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(code);
    }

    public int codeLength() {
        return codeLength;
    }
}
