package io.github.david7777k.trimly.link;

/** What the redirect needs: where to send the caller, and which link it was. */
public record ResolvedLink(long linkId, String targetUrl) {
}
