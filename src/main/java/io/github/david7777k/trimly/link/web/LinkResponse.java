package io.github.david7777k.trimly.link.web;

import io.github.david7777k.trimly.link.Link;

import java.time.Instant;

public record LinkResponse(
        String code,
        String targetUrl,
        Instant expiresAt,
        Instant createdAt) {

    public static LinkResponse from(Link link) {
        return new LinkResponse(
                link.getCode(), link.getTargetUrl(), link.getExpiresAt(), link.getCreatedAt());
    }
}
