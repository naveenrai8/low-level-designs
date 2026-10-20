package com.lld.mistakes;

import java.util.List;

/** A set of unique names, e.g. subscribed webhook URLs. */
public interface Registry {

    /** @return {@code true} if the name was added, {@code false} if it was already there. */
    boolean register(String name);

    List<String> all();
}
