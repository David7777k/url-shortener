package io.github.david7777k.trimly.common.error;

/** The link existed and has expired. Answered as 410, not 404. */
public class LinkExpiredException extends RuntimeException {

    public LinkExpiredException(String code) {
        super("Link " + code + " has expired");
    }
}
