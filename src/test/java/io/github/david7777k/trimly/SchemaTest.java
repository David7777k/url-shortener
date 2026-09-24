package io.github.david7777k.trimly;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaTest extends AbstractIntegrationTest {

    @Test
    void migrationsApply() {
        Integer tables = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema = 'public' and table_name = 'link'
                """, Integer.class);

        assertThat(tables).isEqualTo(1);
    }

    @Test
    void acceptsAValidLink() {
        insert("abc123", "https://example.com/some/page");

        assertThat(jdbcTemplate.queryForObject(
                "select target_url from link where code = 'abc123'", String.class))
                .isEqualTo("https://example.com/some/page");
    }

    @Test
    void rejectsDuplicateCode() {
        insert("dup1", "https://example.com");

        assertThatThrownBy(() -> insert("dup1", "https://elsewhere.com"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsCodeOutsideTheAlphabet() {
        assertThatThrownBy(() -> insert("has space", "https://example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsTargetThatIsNotAnAbsoluteHttpUrl() {
        assertThatThrownBy(() -> insert("rel1", "/just/a/path"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insert("js1", "javascript:alert(1)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsExpiryInThePast() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into link (code, target_url, created_at, expires_at)
                values ('past1', 'https://example.com', now(), now() - interval '1 day')
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insert(String code, String target) {
        jdbcTemplate.update(
                "insert into link (code, target_url) values (?, ?)", code, target);
    }
}
