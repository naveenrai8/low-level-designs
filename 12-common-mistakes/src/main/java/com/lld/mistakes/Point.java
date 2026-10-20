package com.lld.mistakes;

/** A 2-D point written by one thread and read by many. The invariant: a read sees x and y from the same write. */
public interface Point {

    void moveTo(double x, double y);

    /** @return {x, y} from one consistent moment. */
    double[] read();
}
