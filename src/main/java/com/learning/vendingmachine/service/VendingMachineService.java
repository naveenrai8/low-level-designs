package com.learning.vendingmachine.service;

import com.learning.vendingmachine.model.Money;
import com.learning.vendingmachine.model.Product;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class VendingMachineService {
    private final ConcurrentHashMap<String, Product> inventory = new ConcurrentHashMap<>();
    private final ReentrantLock stateLock = new ReentrantLock();
    private Product selectedProduct;
    private double currentPayment = 0.0;

    public VendingMachineService() {
        initializeInventory();
    }

    private void initializeInventory() {
        inventory.put("COKE", new Product("COKE", "Coca Cola", 1.50, 10));
        inventory.put("PEPSI", new Product("PEPSI", "Pepsi", 1.50, 10));
        inventory.put("WATER", new Product("WATER", "Water", 1.00, 15));
        inventory.put("CHIPS", new Product("CHIPS", "Chips", 2.00, 8));
    }

    public void selectProduct(String productId) throws Exception {
        stateLock.lock();
        try {
            Product product = inventory.get(productId);
            if (product == null) throw new Exception("Product not found");
            if (!product.isAvailable()) throw new Exception("Product out of stock");
            selectedProduct = product;
            currentPayment = 0.0;
        } finally {
            stateLock.unlock();
        }
    }

    public void insertMoney(Money money) {
        stateLock.lock();
        try {
            currentPayment += money.getValue();
        } finally {
            stateLock.unlock();
        }
    }

    public Map<String, Object> dispense() throws Exception {
        stateLock.lock();
        try {
            if (selectedProduct == null) throw new Exception("No product selected");
            if (currentPayment < selectedProduct.getPrice()) throw new Exception("Insufficient payment");

            selectedProduct.decrementQuantity();
            double change = currentPayment - selectedProduct.getPrice();

            Map<String, Object> result = new HashMap<>();
            result.put("product", selectedProduct.getName());
            result.put("change", change);

            reset();
            return result;
        } finally {
            stateLock.unlock();
        }
    }

    public double cancel() {
        stateLock.lock();
        try {
            double refund = currentPayment;
            reset();
            return refund;
        } finally {
            stateLock.unlock();
        }
    }

    public Collection<Product> getInventory() {
        return inventory.values();
    }

    private void reset() {
        selectedProduct = null;
        currentPayment = 0.0;
    }
}
