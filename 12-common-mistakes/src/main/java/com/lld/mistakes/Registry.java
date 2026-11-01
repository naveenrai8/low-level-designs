package com.lld.mistakes;

import java.util.List;

/**
 * A set of unique names, for example subscribed webhook URLs.
 */
public interface Registry {

    /**
     * Adds the name.
     * 
     * @return {@code true} if the name was added, {@code false} if it was already there.
     */
    boolean register(String name);

    /**
     * Returns the registered names.
     */
    List<String> all();
}
