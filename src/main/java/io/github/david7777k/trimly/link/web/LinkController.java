package io.github.david7777k.trimly.link.web;

import io.github.david7777k.trimly.click.ClickStatsResponse;
import io.github.david7777k.trimly.click.ClickStatsService;
import io.github.david7777k.trimly.link.LinkService;
import jakarta.validation.Valid;
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

        return ResponseEntity.created(location).body(link);
    }

    @GetMapping("/{code}")
    public LinkResponse getLink(@PathVariable String code) {
        return linkService.get(code);
    }

    @GetMapping("/{code}/stats")
    public ClickStatsResponse getStats(
            @PathVariable String code,
            @RequestParam(defaultValue = "30") int days) {
        return clickStatsService.statsFor(code, days);
    }

    @DeleteMapping("/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLink(@PathVariable String code) {
        linkService.delete(code);
    }
}
