package com.learning.vendingmachine.model;

public enum Money {
    PENNY(0.01),
    NICKEL(0.05),
    DIME(0.10),
    QUARTER(0.25),
    ONE_DOLLAR(1.00),
    FIVE_DOLLAR(5.00),
    TEN_DOLLAR(10.00),
    TWENTY_DOLLAR(20.00);

    private final double value;

    Money(double value) {
        this.value = value;
    }

    public double getValue() {
        return value;
    }
}
