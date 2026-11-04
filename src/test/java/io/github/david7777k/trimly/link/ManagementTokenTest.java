package io.github.david7777k.trimly.link;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ManagementTokenTest {
    @Test
    void tokenDigestRejectsMissingWrongAndOversizedCredentials() {
        String token = ManagementToken.generate();
        String hash = ManagementToken.hash(token);
        assertThat(token).hasSize(43);
        assertThat(ManagementToken.matches(token, hash)).isTrue();
        assertThat(ManagementToken.matches(null, hash)).isFalse();
        assertThat(ManagementToken.matches(token, null)).isFalse();
        assertThat(ManagementToken.matches(ManagementToken.generate(), hash)).isFalse();
        assertThat(ManagementToken.matches("x".repeat(10000), hash)).isFalse();
    }
}
