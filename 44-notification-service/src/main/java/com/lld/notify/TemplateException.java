package com.lld.notify;

/** A template problem the caller must fix: a variable the template needs was not supplied. (An unknown template name is reported by the service as an {@link InvalidRequestException}.) */
public final class TemplateException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception with a message naming the template or variable. */
    public TemplateException(String message) {
        super(message);
    }
}
