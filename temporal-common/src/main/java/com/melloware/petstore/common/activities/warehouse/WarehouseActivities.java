package com.melloware.petstore.common.activities.warehouse;

import com.melloware.petstore.common.models.json.CheckInventoryRequest;

import io.temporal.activity.ActivityInterface;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Warehouse activities
 * 
 
 */
@ActivityInterface
public interface WarehouseActivities {
    
    /**
     * Check for instock status for the list of products and quantities
     * 
     * @param request {@link CheckInventoryRequest}
     * @throws Exception if any product is not in stock
     */
    void checkInventory(@Valid @NotNull CheckInventoryRequest request);
}