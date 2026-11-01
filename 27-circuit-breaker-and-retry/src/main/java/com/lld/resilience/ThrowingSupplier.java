package com.lld.resilience;

/**
 * A call to a dependency: it returns a value or throws.
 *
 * @param <T> the type of the result
 */
@FunctionalInterface
public interface ThrowingSupplier<T> {

    /**
     * Runs the call.
     *
     * @return the result of the dependency
     * @throws Exception whatever the dependency throws
     */
    T get() throws Exception;
}
