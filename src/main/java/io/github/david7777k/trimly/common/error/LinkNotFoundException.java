package io.github.david7777k.trimly.common.error;

public class LinkNotFoundException extends RuntimeException {

    public LinkNotFoundException(String code) {
        super("No link with code " + code);
    }
}
