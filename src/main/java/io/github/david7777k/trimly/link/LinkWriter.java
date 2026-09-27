package io.github.david7777k.trimly.link;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Inserts a link in a transaction of its own.
 *
 * <p>A separate bean on purpose. Spring applies {@code @Transactional} through a
 * proxy, so a method calling another method of the same class bypasses it
 * entirely - the retry loop in LinkService would have run every attempt inside
 * one transaction, which a constraint violation marks rollback-only, and the
 * commit would fail however many codes were tried.
 */
@Component
public class LinkWriter {

    private final LinkRepository linkRepository;

    public LinkWriter(LinkRepository linkRepository) {
        this.linkRepository = linkRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Link insert(String code, String targetUrl, String createdBy, Instant expiresAt) {
        return linkRepository.saveAndFlush(new Link(code, targetUrl, createdBy, expiresAt));
    }
}
