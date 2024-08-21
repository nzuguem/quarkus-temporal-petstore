package com.mycompany.order.purchasing.gateway.app.workflows;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.apache.commons.lang3.exception.ExceptionUtils;

import com.mycompany.order.purchasing.gateway.app.ActivityStubsProvider;
import com.mycompany.order.purchasing.gateway.app.json.OrderPurchaseContext;
import com.mycompany.order.purchasing.shared.activities.order.OrderNotificationActivities;
import com.mycompany.order.purchasing.shared.activities.order.OrderServiceActivities;
import com.mycompany.order.purchasing.shared.activities.payment.PaymentActivities;
import com.mycompany.order.purchasing.shared.activities.shipper.ShipperActivities;
import com.mycompany.order.purchasing.shared.activities.warehouse.WarehouseActivities;
import com.mycompany.order.purchasing.shared.models.enums.OrderFailureReason;
import com.mycompany.order.purchasing.shared.models.exceptions.BadPaymentInfoException;
import com.mycompany.order.purchasing.shared.models.exceptions.OutOfStockException;
import com.mycompany.order.purchasing.shared.models.exceptions.PaymentDeclinedException;
import com.mycompany.order.purchasing.shared.models.json.CheckInventoryRequest;
import com.mycompany.order.purchasing.shared.models.json.CreateOrderRequest;
import com.mycompany.order.purchasing.shared.models.json.CreateOrderResponse;
import com.mycompany.order.purchasing.shared.models.json.CreateTrackingNumberRequest;
import com.mycompany.order.purchasing.shared.models.json.DebitCreditCardRequest;
import com.mycompany.order.purchasing.shared.models.json.DebitCreditCardResponse;
import com.mycompany.order.purchasing.shared.models.json.MarkOrderCompleteRequest;
import com.mycompany.order.purchasing.shared.models.json.MarkOrderFailedRequest;
import com.mycompany.order.purchasing.shared.models.json.OrderErrorEmailNotificationRequest;
import com.mycompany.order.purchasing.shared.models.json.OrderReceivedEmailNotificationRequest;
import com.mycompany.order.purchasing.shared.models.json.OrderSuccessEmailNotificationRequest;
import com.mycompany.order.purchasing.shared.models.json.Product;
import com.mycompany.order.purchasing.shared.models.json.ReverseActionsForTransactionRequest;
import com.mycompany.order.purchasing.shared.utils.TemporalActivityExceptionChecker;

import io.temporal.failure.CanceledFailure;
import io.temporal.failure.TemporalFailure;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.extern.jbosslog.JBossLog;

/**
 * Demo workflow for a simulated Product order scenario
 * <p>
 * The steps taken are:
 * <ul>
 * <li> Receive JSON request</li>
 * <li> Send acceptance email</li>
 * <li> Create the initial order record in the DB</li>
 * <li> Calculate the order total</li>
 * <li> Charge the credit card the calculated amount</li>
 * <li> Check the warehouse for inventory </li>
 * <li> Generate a shipping/tracking number <li>
 * <li> Finalize the order with more information and send email</li>
 * </ul>
 */
@JBossLog
public class OrderPurchaseWorkflowImpl implements OrderPurchaseWorkflow {


    private final PaymentActivities paymentActivity = ActivityStubsProvider.getPaymentActivities();
    private final OrderNotificationActivities notificationActivity = ActivityStubsProvider.getOrderNotificationActivities();
    private final OrderServiceActivities orderActivity = ActivityStubsProvider.getOrderServiceActivities();
    private final WarehouseActivities warehouseActivity = ActivityStubsProvider.getWarehouseActivities();
    private final ShipperActivities shipperActivity = ActivityStubsProvider.getShipperActivities();

    /**
     * Method to start the workflow
     *
     * @param orderCtx {@link OrderPurchaseContext}
     */
    @Override
    public void placeOrder(@Valid @NotNull OrderPurchaseContext orderCtx) {

        Objects.requireNonNull(orderCtx, "OrderPurchaseContext is required");

        // Will let us do rollbacks later
        Saga saga = new Saga(new Saga.Options.Builder().build());
        
        try {

            // 1. Send the acknowledgement of the order request
            sendOrderReceivedEmail(orderCtx);

            // 2. Create the initial order record
            CreateOrderResponse newOrder = generateProductOrder(orderCtx);

            // Set order number into context
            orderCtx = orderCtx.toBuilder()
                    .orderNumber(newOrder.getOrderNumber())
                    .status(newOrder.getStatus())
                    .build();

            
            // 3. Calculate the order total
            double orderTotal = calculateTotalPrice(orderCtx.getProducts());
            
            // Set the total into the context
            orderCtx = orderCtx
                    .toBuilder()
                    .orderTotal(orderTotal)
                    .build();
            
            // 4. Charge the credit card
            // This could throw an error for something like
            // INVALID_CARD_INFO, PAYMENT_DECLINED, etc.
            // In the REAL WORLD this would call out to a 3rd party service
            // We are just managing our own service for the demo
            debitCreditCard(saga, orderCtx);
            
            // 5. Check with warehouse to see if the products are in stock - fail if not
            // If this fails, we compensate the customers credit card and reverse the charge
            // For this demo we are choosing to check inventory after charging the customer
            // for the products.
            // In a REAL WORLD scenario, this might be done before charging the customer
            
            /** NOTE: Any exception after this point will cause the compensation to run **/
            CheckInventoryRequest invRequest = CheckInventoryRequest.builder()
                    .products(orderCtx.getProducts())
                    .build();
                    
            warehouseActivity.checkInventory(invRequest);
            
            // 6. get the shipping information/tracking number from the shipper
            CreateTrackingNumberRequest trackRequest = CreateTrackingNumberRequest.builder()
                    .products(orderCtx.getProducts())
                    .build();
            
            // Add it into the order context
            String trackingNumber = shipperActivity.createTrackingNumber(trackRequest);
            orderCtx = orderCtx.toBuilder()
                    .trackingNumber(trackingNumber)
                    .build();
            
            // 7. Save order history and send out email
            completeOrder(orderCtx);

        } catch (TemporalFailure e) {
            log.error(ExceptionUtils.getRootCauseMessage(e), e);
            OrderPurchaseContext theCtx = orderCtx; // stupid to get around effectively final error
            Workflow.newDetachedCancellationScope(() -> cleanup(e, saga, theCtx, theCtx.getTransactionId())).run();
            throw e;
        }
    }

    /**
     * Calculates the total price for a list of products based on quantity and price.
     *
     * @param products List of products.
     * @return Total price.
     */
    private double calculateTotalPrice(List<Product> products) {
        return products.stream()
                .mapToDouble(product -> product.getQuantity() * product.getPrice())
                .sum();
    }

    
    /**
     * Creates a new order with some initial information from the
     * request.
     * <p>
     * The rest of the data will be added later in the process
     *
     * @param purchaseCtx {@link OrderPurchaseContext}
     * @return {@link CreateOrderResponse}
     */
    private CreateOrderResponse generateProductOrder(OrderPurchaseContext purchaseCtx) {
        log.infof("Generating new order record for TX id %s", purchaseCtx.getTransactionId());
        CreateOrderRequest newOrderReq = CreateOrderRequest.builder()
                .customerEmail(purchaseCtx.getCustomerEmail())
                .orderDate(purchaseCtx.getRequestDate())
                .transactionId(purchaseCtx.getTransactionId())
                .requestedByHost(purchaseCtx.getRequestedByHost())
                .requestedByUser(purchaseCtx.getRequestedByUser())
                .products(purchaseCtx.getProducts())
                .build();

        return orderActivity.createOrder(newOrderReq);
    }

    /**
     * Sends the order received email
     *
     * @param purchaseCtx {@link OrderPurchaseContext}
     */
    private void sendOrderReceivedEmail(OrderPurchaseContext purchaseCtx) {
        log.info("Sending order request received notification");
        OrderReceivedEmailNotificationRequest orderRcvReq = OrderReceivedEmailNotificationRequest.builder()
                .transactionNumber(purchaseCtx.getTransactionId())
                .customerEmail(purchaseCtx.getCustomerEmail())
                .orderDate(purchaseCtx.getRequestDate())
                .products(purchaseCtx.getProducts())
                .build();

        notificationActivity.sendOrderReceivedEmail(orderRcvReq);
    }

    /**
     * Perform compensations and other cleanups
     *
     * @param e
     */
    private void cleanup(Exception e, Saga saga, OrderPurchaseContext ctx, UUID transactionId) {
        log.infof("Performing cleanup operations for TX id %s", transactionId);

        // Perform compensations
        try {
            if (saga != null) {
                saga.compensate();
            }
        } catch (Exception cpe) {
            log.error("Failed to complete compensations!", cpe);
        }

        // If error
        // Cancelled failures are when the workflow is cancelled
        // from the WebUI or through code
        // You could handle that situation in your workflow
        // if you allowed cancellations..in this demo we do not
        if (!(e instanceof CanceledFailure)) {
            if (ctx != null) {
                failOrder(e, ctx);
            }
        }

        log.infof("Finished cleanup operations for TX id %s", transactionId);
    }

    /**
     * Performs operations when a order fails
     *
     * @param ctx {@link LicenseOrderContext}
     */
    private void failOrder(Exception e, OrderPurchaseContext ctx) {

        // Default to SYSTEM error
        OrderFailureReason reason = OrderFailureReason.SYSTEM_ERROR;

        // Check for specific errors
        if (TemporalActivityExceptionChecker.isExceptionType(e, PaymentDeclinedException.class)) {
            reason = OrderFailureReason.PAYMENT_DECLINED;
        } else if (TemporalActivityExceptionChecker.isExceptionType(e, BadPaymentInfoException.class)) {
            reason = OrderFailureReason.INVALID_PAYMENT_METHOD;
        } else if (TemporalActivityExceptionChecker.isExceptionType(e, OutOfStockException.class)) {
            reason = OrderFailureReason.OUT_OF_STOCK_ITEMS;
        }

        log.infof("Marking order as failed with TX id %s", ctx.getTransactionId());

        // Mark the order as failed
        MarkOrderFailedRequest req = MarkOrderFailedRequest.builder()
                .orderNumber(ctx.getOrderNumber())
                .transactionId(ctx.getTransactionId())
                .reason(reason)
                .build();

        // Call activity
        orderActivity.markOrderAsFailed(req);

        // send error email
        OrderErrorEmailNotificationRequest emailRequest = OrderErrorEmailNotificationRequest.builder()
                .orderDate(ctx.getRequestDate())
                .customerEmail(ctx.getCustomerEmail())
                .orderNumber(ctx.getOrderNumber())
                .transactionNumber(ctx.getTransactionId())
                .build();

        // Call activity to send email
        notificationActivity.sendOrderErrorEmail(emailRequest);
    }


    /**
     * Performs operations when a order completes
     *
     * @param ctx {@link OrderPurchaseContext}
     */
    private void completeOrder(OrderPurchaseContext ctx) {

        log.infof("Marking order %s as complete with TX id %s", ctx.getOrderNumber(), ctx.getTransactionId());
        
        /** Save the final order to the database **/
        MarkOrderCompleteRequest completeReq = MarkOrderCompleteRequest.builder()
                .products(ctx.getProducts())
                .orderDate(ctx.getRequestDate())
                .orderNumber(ctx.getOrderNumber())
                .transactionId(ctx.getTransactionId())
                .customerEmail(ctx.getCustomerEmail())
                .orderTotal(ctx.getOrderTotal())
                .build();

        orderActivity.markOrderAsComplete(completeReq);

        /** Send NOTIFICATION ***/
        // Create request
        OrderSuccessEmailNotificationRequest emailRequest = OrderSuccessEmailNotificationRequest.builder()
                .customerEmail(ctx.getCustomerEmail())
                .transactionNumber(ctx.getTransactionId())
                .orderNumber(ctx.getOrderNumber())
                .orderDate(ctx.getRequestDate())
                .products(ctx.getProducts())
                .trackingNumber(ctx.getTrackingNumber())
                .orderTotal(ctx.getOrderTotal())
                .build();

        // Call activity to send email
        log.infof("Order updated..Sending notification email to %s", ctx.getCustomerEmail());
        notificationActivity.sendOrderSuccessEmail(emailRequest);
    }

    /**
     * Process the credit card specified
     *
     * @param saga Saga to add compensation actions
     * @param ctx {@link OrderPurchaseContext}
     */
    private DebitCreditCardResponse debitCreditCard(Saga saga, OrderPurchaseContext ctx) {

        log.info("Calling Payment service to debit credit card");

        // Setup the compensation
        // Create the reversal in case of compensations later
        ReverseActionsForTransactionRequest reverseReq = ReverseActionsForTransactionRequest.builder()
                .requestedByHost(ctx.getRequestedByHost())
                .requestedByUser(ctx.getRequestedByUser())
                .transactionId(ctx.getTransactionId())
                .build();

        saga.addCompensation(() -> paymentActivity.reversePaymentTransactions(reverseReq));

        // debit card and return some sort of auth number or whatever
        DebitCreditCardRequest cardRequest = DebitCreditCardRequest.builder()
                .amount(ctx.getOrderTotal())
                .creditCard(ctx.getCreditCard())
                .customerEmail(ctx.getCustomerEmail())
                .transactionId(ctx.getTransactionId())
                .requestedByHost(ctx.getRequestedByHost())
                .requestedByUser(ctx.getRequestedByUser())
                .build();
                       
        return paymentActivity.debitCreditCard(cardRequest);
        
    }
}
