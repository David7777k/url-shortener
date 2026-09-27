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
    private final CodeGenerator codeGenerator;
    private final TargetUrlValidator targetUrlValidator;
    private final Clock clock;

    public LinkService(LinkRepository linkRepository,
                       LinkWriter linkWriter,
                       CodeGenerator codeGenerator,
                       TargetUrlValidator targetUrlValidator,
                       Clock clock) {
        this.linkRepository = linkRepository;
        this.linkWriter = linkWriter;
        this.codeGenerator = codeGenerator;
        this.targetUrlValidator = targetUrlValidator;
        this.clock = clock;
    }

    /**
     * Creates a link, retrying if the generated code is already taken.
     *
     * <p>The retry is driven by the unique index rather than by a prior
     * "is this code free?" query. Checking first is a read followed by a write,
     * and two requests can pass the check before either inserts. Letting the
     * insert fail is the only version that cannot race.
     *
     * <p>Each attempt is its own transaction, in a separate bean - see
     * {@link LinkWriter} for why that matters.
     */
    public LinkResponse create(CreateLinkRequest request, String createdBy) {
        String target = targetUrlValidator.validate(request.targetUrl());

        for (int attempt = 1; attempt <= MAX_CODE_ATTEMPTS; attempt++) {
            try {
                return LinkResponse.from(linkWriter.insert(
                        codeGenerator.generate(), target, createdBy, request.expiresAt()));
            } catch (DataIntegrityViolationException collision) {
                log.debug("Code collision on attempt {}", attempt);
            }
        }

        throw new CodeGenerationException(MAX_CODE_ATTEMPTS);
    }

    /**
     * Resolves a code to its target.
     *
     * <p>An expired link answers 410 rather than 404: it existed, and saying so
     * is more useful than pretending it never did.
     */
    @Transactional(readOnly = true)
    public String resolve(String code) {
        Link link = linkRepository.findByCode(code)
                .orElseThrow(() -> new LinkNotFoundException(code));

        if (link.isExpiredAt(clock.instant())) {
            throw new LinkExpiredException(code);
        }

        return link.getTargetUrl();
    }

    @Transactional(readOnly = true)
    public LinkResponse get(String code) {
        return LinkResponse.from(linkRepository.findByCode(code)
                .orElseThrow(() -> new LinkNotFoundException(code)));
    }
}
