package io.github.david7777k.trimly.link;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeGeneratorTest {

    private static final Pattern BASE62 = Pattern.compile("^[0-9a-zA-Z]+$");

    private final CodeGenerator generator = new CodeGenerator(7);

    @Test
    void producesCodesOfTheConfiguredLength() {
        for (int i = 0; i < 1_000; i++) {
            assertThat(generator.generate()).hasSize(7);
        }
    }

    @Test
    void producesOnlyUrlSafeCharacters() {
        for (int i = 0; i < 1_000; i++) {
            assertThat(generator.generate()).matches(BASE62);
        }
    }

    @Test
    void doesNotRepeatItselfOverAShortRun() {
        // Not a collision-freedom proof - that is what the unique index is for.
        // This only catches a generator that is broken enough to return the same
        // value repeatedly.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            seen.add(generator.generate());
        }
        assertThat(seen).hasSize(100_000);
    }

    @Test
    void usesTheWholeAlphabet() {
        // A generator indexing with the wrong bound would silently never emit
        // the last character, and nothing above would notice.
        Set<Character> seen = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            for (char c : generator.generate().toCharArray()) {
                seen.add(c);
            }
        }
        assertThat(seen).hasSize(62);
    }

    @Test
    void rejectsAnUnreasonableLength() {
        assertThatThrownBy(() -> new CodeGenerator(3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CodeGenerator(64))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
