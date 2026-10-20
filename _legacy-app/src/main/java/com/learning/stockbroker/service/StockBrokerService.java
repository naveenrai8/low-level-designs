package com.learning.stockbroker.service;

import com.learning.stockbroker.model.Portfolio;
import com.learning.stockbroker.model.Stock;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class StockBrokerService {
    private final ConcurrentHashMap<String, Stock> stocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Portfolio> portfolios = new ConcurrentHashMap<>();

    public void addStock(Stock stock) {
        stocks.put(stock.getSymbol(), stock);
    }

    public Portfolio createPortfolio(String userId, double initialBalance) {
        Portfolio portfolio = new Portfolio(userId, initialBalance);
        portfolios.put(userId, portfolio);
        return portfolio;
    }

    public boolean buyStock(String userId, String symbol, int quantity) throws Exception {
        Portfolio portfolio = portfolios.get(userId);
        Stock stock = stocks.get(symbol);

        if (portfolio == null) throw new Exception("Portfolio not found");
        if (stock == null) throw new Exception("Stock not found");

        return portfolio.buyStock(symbol, quantity, stock.getPrice());
    }

    public boolean sellStock(String userId, String symbol, int quantity) throws Exception {
        Portfolio portfolio = portfolios.get(userId);
        Stock stock = stocks.get(symbol);

        if (portfolio == null) throw new Exception("Portfolio not found");
        if (stock == null) throw new Exception("Stock not found");

        return portfolio.sellStock(symbol, quantity, stock.getPrice());
    }

    public void updateStockPrice(String symbol, double newPrice) throws Exception {
        Stock stock = stocks.get(symbol);
        if (stock == null) throw new Exception("Stock not found");
        stock.updatePrice(newPrice);
    }

    public Stock getStock(String symbol) {
        return stocks.get(symbol);
    }

    public Portfolio getPortfolio(String userId) {
        return portfolios.get(userId);
    }

    public List<Stock> getAllStocks() {
        return List.copyOf(stocks.values());
    }
}
