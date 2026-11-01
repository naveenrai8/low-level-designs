package com.lld.vending;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Coffee variant (F1): recipes and ingredient stock. */
class RecipeInventoryTest {

    /** Builds an inventory with espresso (water, coffee) and latte (water, milk, coffee). */
    private static RecipeInventory coffee() {
        RecipeInventory inv = new RecipeInventory();
        inv.defineRecipe("ESP", Map.of("water", 30, "coffee", 18));
        inv.defineRecipe("LAT", Map.of("water", 30, "milk", 120, "coffee", 18));
        inv.refill("water", 1000);
        inv.refill("coffee", 100);
        inv.refill("milk", 100);
        return inv;
    }

    /** A drink with one short ingredient is out of stock while others are fine. */
    @Test
    void oneShortIngredientMakesDrinkUnavailable() {
        RecipeInventory inv = coffee();
        assertTrue(inv.inStock("ESP"));
        assertFalse(inv.inStock("LAT"));
        assertFalse(inv.inStock("UNKNOWN"));
    }

    /** Taking a drink removes all its ingredients together. */
    @Test
    void takeRemovesEveryIngredient() {
        RecipeInventory inv = coffee();
        inv.take("ESP");
        assertEquals(970, inv.level("water"));
        assertEquals(82, inv.level("coffee"));
    }

    /** A refused take changes nothing: no ingredient is half-used. */
    @Test
    void failedTakeIsAllOrNothing() {
        RecipeInventory inv = coffee();
        assertThrows(IllegalStateException.class, () -> inv.take("LAT"));
        assertEquals(1000, inv.level("water"));
        assertEquals(100, inv.level("coffee"));
        assertEquals(100, inv.level("milk"));
    }

    /** Running dry mid-day: drinks are served until an ingredient runs out, then select refuses. */
    @Test
    void machineStopsSellingWhenIngredientRunsOut() {
        RecipeInventory inv = coffee();
        DefaultVendingMachine m = new DefaultVendingMachine(List.of(new Product("ESP", "Espresso", 250)), Set.of(250L), inv, Fixtures.working(), Telemetry.none(), Fixtures.CLOCK);
        int sold = 0;
        while (inv.inStock("ESP")) {
            m.select("ESP");
            m.insert(250);
            assertEquals(SaleResult.Status.SOLD, m.dispense().status());
            sold++;
        }
        assertEquals(5, sold);
        assertThrows(SoldOutException.class, () -> m.select("ESP"));
    }

    /** A jam does not use ingredients (nothing was brewed to the customer), so stock is untouched. */
    @Test
    void jamLeavesIngredientsAlone() {
        RecipeInventory inv = coffee();
        DefaultVendingMachine m = new DefaultVendingMachine(List.of(new Product("ESP", "Espresso", 250)), Set.of(250L), inv, Fixtures.jamming(), Telemetry.none(), Fixtures.CLOCK);
        m.select("ESP");
        m.insert(250);
        assertEquals(SaleResult.Status.JAMMED_REFUNDED, m.dispense().status());
        assertEquals(1000, inv.level("water"));
        assertEquals(100, inv.level("coffee"));
    }

    /** A recipe with no ingredients would be "always in stock, costs nothing", so it is rejected. */
    @Test
    void emptyRecipeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RecipeInventory().defineRecipe("X", Map.of()));
    }
}
