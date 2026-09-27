package io.github.david7777k.trimly.link;

import io.github.david7777k.trimly.common.error.InvalidTargetException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * Checks that a target is somewhere worth redirecting to.
 *
 * <p>A shortener is a redirect service handed arbitrary input, which makes it a
 * useful tool for other people's purposes unless it is picky about what it will
 * point at.
 */
@Component
public class TargetUrlValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final Set<String> ownHosts;

    public TargetUrlValidator(@Value("${trimly.own-hosts:localhost}") Set<String> ownHosts) {
        this.ownHosts = ownHosts.stream().map(h -> h.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
    }

    public String validate(String candidate) {
        URI uri;
        try {
            uri = new URI(candidate.trim());
        } catch (URISyntaxException e) {
            throw new InvalidTargetException("Target is not a valid URL");
        }

        if (!uri.isAbsolute() || uri.getScheme() == null) {
            throw new InvalidTargetException("Target must be an absolute URL");
        }

        // Rejecting everything but http(s) is what stops javascript: and data:
        // targets, which would turn a redirect into a way to run someone else's
        // script under this service's name.
        if (!ALLOWED_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
            throw new InvalidTargetException("Target must use http or https");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidTargetException("Target must have a host");
        }

        // Without this a link can point at another code on this service, and a
        // pair of them redirects in a circle until the client gives up.
        if (ownHosts.contains(host.toLowerCase(Locale.ROOT))) {
            throw new InvalidTargetException("Target must not point back at this service");
        }

        return uri.toString();
    }
}
