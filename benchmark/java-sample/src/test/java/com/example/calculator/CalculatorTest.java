package com.example.calculator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CalculatorTest {
    @Test
    void addsNumbers() {
        assertEquals(5, Calculator.add(2, 3));
    }

    @Test
    void subtractsNumbers() {
        assertEquals(1, Calculator.subtract(3, 2));
    }

    @Test
    void multipliesNumbers() {
        assertEquals(6, Calculator.multiply(2, 3));
    }

    @Test
    void dividesNumbers() {
        assertEquals(2, Calculator.divide(6, 3));
    }

    @Test
    void rejectsZeroDivisor() {
        assertThrows(IllegalArgumentException.class, () -> Calculator.divide(1, 0));
    }
}

class OrderServiceTest {
    @Test
    void appliesDiscountForTenOrMore() {
        assertEquals(90, new OrderService(10).totalFor(10));
    }

    @Test
    void appliesNoDiscountBelowTen() {
        assertEquals(90, new OrderService(10).totalFor(9));
    }
}
