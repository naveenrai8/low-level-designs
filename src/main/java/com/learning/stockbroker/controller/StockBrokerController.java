package com.learning.stockbroker.controller;

import com.learning.stockbroker.dto.StockRequest;
import com.learning.stockbroker.dto.TradeRequest;
import com.learning.stockbroker.model.Portfolio;
import com.learning.stockbroker.model.Stock;
import com.learning.stockbroker.service.StockBrokerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/stockbroker")
@Tag(name = "Stock Broker", description = "Stock brokerage operations")
public class StockBrokerController {

    private final StockBrokerService stockBrokerService;

    public StockBrokerController(StockBrokerService stockBrokerService) {
        this.stockBrokerService = stockBrokerService;
    }

    @PostMapping("/stocks")
    @Operation(summary = "Add a stock")
    public ResponseEntity<String> addStock(@Valid @RequestBody StockRequest request) {
        Stock stock = new Stock(request.symbol(), request.name(), request.initialPrice());
        stockBrokerService.addStock(stock);
        return ResponseEntity.status(HttpStatus.CREATED).body("Stock added successfully");
    }

    @GetMapping("/stocks")
    @Operation(summary = "Get all stocks")
    public ResponseEntity<List<Stock>> getAllStocks() {
        return ResponseEntity.ok(stockBrokerService.getAllStocks());
    }

    @GetMapping("/stocks/{symbol}")
    @Operation(summary = "Get stock details")
    public ResponseEntity<Stock> getStock(@PathVariable String symbol) {
        Stock stock = stockBrokerService.getStock(symbol);
        return stock != null ? ResponseEntity.ok(stock) : ResponseEntity.notFound().build();
    }

    @PutMapping("/stocks/{symbol}/price")
    @Operation(summary = "Update stock price")
    public ResponseEntity<?> updatePrice(@PathVariable String symbol, @RequestParam double newPrice) {
        try {
            stockBrokerService.updateStockPrice(symbol, newPrice);
            return ResponseEntity.ok("Price updated successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/portfolios/{userId}")
    @Operation(summary = "Create a portfolio")
    public ResponseEntity<Portfolio> createPortfolio(
            @PathVariable String userId,
            @RequestParam double initialBalance) {
        Portfolio portfolio = stockBrokerService.createPortfolio(userId, initialBalance);
        return ResponseEntity.status(HttpStatus.CREATED).body(portfolio);
    }

    @GetMapping("/portfolios/{userId}")
    @Operation(summary = "Get portfolio")
    public ResponseEntity<Portfolio> getPortfolio(@PathVariable String userId) {
        Portfolio portfolio = stockBrokerService.getPortfolio(userId);
        return portfolio != null ? ResponseEntity.ok(portfolio) : ResponseEntity.notFound().build();
    }

    @PostMapping("/portfolios/{userId}/buy")
    @Operation(summary = "Buy stock")
    public ResponseEntity<?> buyStock(
            @PathVariable String userId,
            @Valid @RequestBody TradeRequest request) {
        try {
            boolean success = stockBrokerService.buyStock(userId, request.symbol(), request.quantity());
            return success ? ResponseEntity.ok("Stock purchased successfully")
                          : ResponseEntity.badRequest().body("Insufficient balance");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/portfolios/{userId}/sell")
    @Operation(summary = "Sell stock")
    public ResponseEntity<?> sellStock(
            @PathVariable String userId,
            @Valid @RequestBody TradeRequest request) {
        try {
            boolean success = stockBrokerService.sellStock(userId, request.symbol(), request.quantity());
            return success ? ResponseEntity.ok("Stock sold successfully")
                          : ResponseEntity.badRequest().body("Insufficient holdings");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
