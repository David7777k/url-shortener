package io.github.david7777k.trimly.link.web;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record CreateLinkRequest(

        @NotBlank(message = "targetUrl must not be blank")
        @Size(max = 2048, message = "targetUrl must be at most 2048 characters")
        String targetUrl,

        /** Optional. Absent means the link never expires. */
        @Future(message = "expiresAt must be in the future")
        Instant expiresAt) {
}
