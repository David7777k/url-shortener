package io.github.david7777k.trimly.link.web;

import io.github.david7777k.trimly.click.ClickRecorder;
import io.github.david7777k.trimly.link.LinkService;
import io.github.david7777k.trimly.link.ResolvedLink;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The hot path: everything else in this service exists to make this fast.
 */
@RestController
public class RedirectController {

    private final LinkService linkService;
    private final ClickRecorder clickRecorder;

    public RedirectController(LinkService linkService, ClickRecorder clickRecorder) {
        this.linkService = linkService;
        this.clickRecorder = clickRecorder;
    }

    /**
     * 302, not 301.
     *
     * <p>A 301 is permanent: browsers cache it, often indefinitely, and stop
     * asking. Faster, but it makes a link impossible to retarget or revoke and
     * silently ends click counting after the first visit. 307 would also do and
     * differs only in preserving the method, which is irrelevant when the only
     * method here is GET.
     */
    @GetMapping("/{code:[0-9a-zA-Z]{4,16}}")
    public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
        ResolvedLink link = linkService.resolve(code);

        // Queues the event and returns immediately. Nothing about the response
        // waits for it to be written.
        clickRecorder.record(
                link.linkId(),
                request.getHeader(HttpHeaders.REFERER),
                request.getHeader(HttpHeaders.USER_AGENT));

        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, link.targetUrl())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }
}
