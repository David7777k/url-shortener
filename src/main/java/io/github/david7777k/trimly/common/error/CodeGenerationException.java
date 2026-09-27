package io.github.david7777k.trimly.common.error;

/** Every generated code collided. Practically unreachable; not impossible. */
public class CodeGenerationException extends RuntimeException {

    public CodeGenerationException(int attempts) {
        super("Failed to allocate a unique code in " + attempts + " attempts");
    }
}
