package com.lld.vending;

/** The hardware seam: the motor or brewer that actually delivers a product. Tests plug in fakes that succeed, jam or probe the machine. */
public interface Dispenser {

    /** Delivers the product. Throws {@link DispenseJamException} when it could not, in which case nothing was delivered. */
    void dispense(Product product) throws DispenseJamException;
}
