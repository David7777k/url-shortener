package io.github.david7777k.trimly.link.web;

import io.github.david7777k.trimly.click.ClickStatsResponse;
import io.github.david7777k.trimly.click.ClickStatsService;
import io.github.david7777k.trimly.link.LinkService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/links")
public class LinkController {

    @org.springframework.web.bind.annotation.ModelAttribute
    public void preventManagementResponseCaching(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    private final LinkService linkService;
    private final ClickStatsService clickStatsService;

    public LinkController(LinkService linkService, ClickStatsService clickStatsService) {
        this.linkService = linkService;
        this.clickStatsService = clickStatsService;
    }

    @PostMapping
    public ResponseEntity<LinkResponse> createLink(@Valid @RequestBody CreateLinkRequest request) {
        LinkResponse link = linkService.create(request, null);

        URI location = UriComponentsBuilder.fromPath("/api/v1/links/{code}")
                .buildAndExpand(link.code())
                .toUri();

        return ResponseEntity.created(location).header("Cache-Control", "no-store").body(link);
    }

    @GetMapping("/{code}")
    public LinkResponse getLink(@PathVariable String code,
            @RequestHeader(value = "X-Management-Token", required = false) String token) {
        linkService.requireManagementAccess(code, token);
        return linkService.get(code);
    }

    @GetMapping("/{code}/stats")
    public ClickStatsResponse getStats(
            @PathVariable String code,
            @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days,
            @RequestHeader(value = "X-Management-Token", required = false) String token) {
        linkService.requireManagementAccess(code, token);
        return clickStatsService.statsFor(code, days);
    }

    @DeleteMapping("/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLink(@PathVariable String code,
            @RequestHeader(value = "X-Management-Token", required = false) String token) {
        linkService.requireManagementAccess(code, token);
        linkService.delete(code);
    }
}
