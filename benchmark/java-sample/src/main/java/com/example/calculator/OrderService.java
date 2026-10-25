package com.example.calculator;

public final class OrderService {
    private final int basePrice;

    public OrderService(int basePrice) {
        this.basePrice = basePrice;
    }

    public int totalFor(int quantity) {
        int subtotal = Calculator.multiply(basePrice, quantity);
        int discount = quantity >= 10 ? Calculator.divide(subtotal, 10) : 0;
        return Calculator.subtract(subtotal, discount);
    }
}
