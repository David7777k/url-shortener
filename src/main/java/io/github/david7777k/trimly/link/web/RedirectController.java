package io.github.david7777k.trimly.link.web;

import io.github.david7777k.trimly.link.LinkService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * The hot path: everything else in this service exists to make this fast.
 */
@RestController
public class RedirectController {

    private final LinkService linkService;

    public RedirectController(LinkService linkService) {
        this.linkService = linkService;
    }

    /**
     * 302, not 301.
     *
     * <p>A 301 is permanent: browsers cache it, often indefinitely, and stop
     * asking. That is faster, but it makes a link impossible to retarget or
     * revoke and silently ends click counting after the first visit. 307 would
     * also do, and differs only in preserving the method - irrelevant when the
     * only method here is GET.
     */
    @GetMapping("/{code:[0-9a-zA-Z]{4,16}}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        String target = linkService.resolve(code);

        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, target)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }
}
