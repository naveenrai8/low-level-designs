package com.lld.mistakes;

/**
 * A 2-D point written by one thread and read by many. The invariant: a read sees x and y from the same write.
 */
public interface Point {

    /**
     * Sets both coordinates together.
     */
    void moveTo(double x, double y);

    /**
     * Reads both coordinates.
     * 
     * @return {x, y} from one consistent moment.
     */
    double[] read();
}
