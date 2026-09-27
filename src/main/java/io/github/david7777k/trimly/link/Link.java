package io.github.david7777k.trimly.link;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "link")
public class Link {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(name = "target_url", nullable = false)
    private String targetUrl;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Link() {
    }

    public Link(String code, String targetUrl, String createdBy, Instant expiresAt) {
        this.code = code;
        this.targetUrl = targetUrl;
        this.createdBy = createdBy;
        this.expiresAt = expiresAt;
    }

    public boolean isExpiredAt(Instant moment) {
        return expiresAt != null && !moment.isBefore(expiresAt);
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Link link) || id == null) {
            return false;
        }
        return id.equals(link.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
