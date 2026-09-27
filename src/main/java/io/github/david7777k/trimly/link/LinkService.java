package io.github.david7777k.trimly.link;

import io.github.david7777k.trimly.common.error.CodeGenerationException;
import io.github.david7777k.trimly.common.error.LinkExpiredException;
import io.github.david7777k.trimly.common.error.LinkNotFoundException;
import io.github.david7777k.trimly.link.web.CreateLinkRequest;
import io.github.david7777k.trimly.link.web.LinkResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

@Service
public class LinkService {

    private static final Logger log = LoggerFactory.getLogger(LinkService.class);

    /**
     * At seven characters a collision is a one-in-millions event, so five
     * attempts is astronomically more than enough. The loop exists to make the
     * rare case correct, not because it is expected to spin.
     */
    private static final int MAX_CODE_ATTEMPTS = 5;

    private final LinkRepository linkRepository;
    private final LinkWriter linkWriter;
    private final LinkCache cache;
    private final CodeGenerator codeGenerator;
    private final TargetUrlValidator targetUrlValidator;
    private final Clock clock;

    public LinkService(LinkRepository linkRepository,
                       LinkWriter linkWriter,
                       LinkCache cache,
                       CodeGenerator codeGenerator,
                       TargetUrlValidator targetUrlValidator,
                       Clock clock) {
        this.linkRepository = linkRepository;
        this.linkWriter = linkWriter;
        this.cache = cache;
        this.codeGenerator = codeGenerator;
        this.targetUrlValidator = targetUrlValidator;
        this.clock = clock;
    }

    /**
     * Creates a link, retrying if the generated code is already taken.
     *
     * <p>The retry is driven by the unique index rather than by a prior
     * "is this code free?" query. Checking first is a read followed by a write,
     * and two requests can pass the check before either inserts.
     *
     * <p>Each attempt is its own transaction, in a separate bean - see
     * {@link LinkWriter} for why that matters.
     */
    public LinkResponse create(CreateLinkRequest request, String createdBy) {
        String target = targetUrlValidator.validate(request.targetUrl());

        for (int attempt = 1; attempt <= MAX_CODE_ATTEMPTS; attempt++) {
            String code = codeGenerator.generate();
            try {
                Link link = linkWriter.insert(code, target, createdBy, request.expiresAt());

                // Somebody may have asked for this code before it existed and
                // had the miss cached. Unlikely with random codes, but the
                // consequence would be a live link answering 404 for a minute.
                cache.evict(code);

                return LinkResponse.from(link);
            } catch (DataIntegrityViolationException collision) {
                log.debug("Code collision on attempt {}", attempt);
            }
        }

        throw new CodeGenerationException(MAX_CODE_ATTEMPTS);
    }

    /**
     * Resolves a code to its target. This is the hot path.
     *
     * <p>Cache-aside: ask the cache, fall through to the database on a miss,
     * then populate. Write-through is the alternative, but links are read far
     * more often than written, and it would put the cache on the critical path
     * of creation - where a Redis failure would then fail the write.
     *
     * <p>Links that expire are deliberately not cached. Caching one means
     * serving it after it lapses, and the TTL arithmetic needed to avoid that
     * buys nothing: links with an expiry are the rare case.
     */
    @Transactional(readOnly = true)
    public String resolve(String code) {
        Optional<LinkCache.Hit> cached = cache.lookup(code);
        if (cached.isPresent()) {
            LinkCache.Hit hit = cached.get();
            if (hit.isMissing()) {
                throw new LinkNotFoundException(code);
            }
            return hit.targetUrl();
        }

        Optional<Link> found = linkRepository.findByCode(code);

        if (found.isEmpty()) {
            cache.putMissing(code);
            throw new LinkNotFoundException(code);
        }

        Link link = found.get();
        if (link.isExpiredAt(clock.instant())) {
            throw new LinkExpiredException(code);
        }

        if (link.getExpiresAt() == null) {
            cache.put(code, link.getTargetUrl());
        }

        return link.getTargetUrl();
    }

    @Transactional(readOnly = true)
    public LinkResponse get(String code) {
        return LinkResponse.from(linkRepository.findByCode(code)
                .orElseThrow(() -> new LinkNotFoundException(code)));
    }

    /**
     * Deletes a link and drops it from the cache.
     *
     * <p>A stale entry would keep redirecting to a target the owner removed,
     * which is the one cache inconsistency here with a real consequence.
     */
    @Transactional
    public void delete(String code) {
        Link link = linkRepository.findByCode(code)
                .orElseThrow(() -> new LinkNotFoundException(code));

        linkRepository.delete(link);
        cache.evict(code);

        log.debug("Deleted link {}", code);
    }
}
