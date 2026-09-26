package io.till.store.orders;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.till.store.auth.Customer;
import io.till.store.catalogue.Game;
import io.till.store.catalogue.Games;
import io.till.store.orders.OrderViews.OrderPage;
import io.till.store.orders.OrderViews.OrderView;
import io.till.store.orders.OrderViews.PlaceOrder;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A signed-in customer's orders.
 *
 * <p>Every mutating endpoint requires an {@code Idempotency-Key}. A checkout is exactly the request a
 * customer double-clicks and a flaky connection retries, and the key is what makes the second copy
 * return the first order instead of placing another.
 */
@RestController
@RequestMapping("/api/orders")
@Tag(name = "orders", description = "Checkout and order history. Signed-in customers only.")
class OrderController {

    private final OrderService service;
    private final Games games;

    OrderController(OrderService service, Games games) {
        this.service = service;
        this.games = games;
    }

    @Operation(operationId = "placeOrder", summary = "Place an order, holding its stock")
    @ApiResponse(responseCode = "201", description = "Placed — or, for a key already used, the order it placed",
            useReturnTypeSchema = true)
    @PostMapping
    ResponseEntity<OrderView> place(
            @AuthenticationPrincipal OidcUser user,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody PlaceOrder body) {
        Order order = service.place(
                Customer.id(user),
                key,
                body.lines().stream().map(line -> new OrderService.Requested(line.sku(), line.quantity())).toList());
        return ResponseEntity.created(URI.create("/api/orders/" + order.id())).body(view(order));
    }

    @Operation(operationId = "listOrders", summary = "Your orders, newest first")
    @GetMapping
    OrderPage history(@AuthenticationPrincipal OidcUser user) {
        List<Order> orders = service.history(Customer.id(user));
        Map<String, Game> catalogue = catalogue(orders);
        return new OrderPage(orders.stream().map(order -> OrderView.of(order, service.now(), catalogue)).toList());
    }

    @Operation(operationId = "getOrder", summary = "One of your orders")
    @GetMapping("/{id}")
    OrderView one(@AuthenticationPrincipal OidcUser user, @PathVariable UUID id) {
        return view(service.owned(Customer.id(user), id));
    }

    @Operation(operationId = "payOrder", summary = "Pay for an order (demonstration — no money moves)")
    @PostMapping("/{id}/pay")
    OrderView pay(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key) {
        return view(service.pay(Customer.id(user), id, key));
    }

    @Operation(operationId = "cancelOrder", summary = "Cancel an unpaid order, giving its stock back")
    @PostMapping("/{id}/cancel")
    OrderView cancel(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key) {
        return view(service.cancel(Customer.id(user), id, key));
    }

    private OrderView view(Order order) {
        return OrderView.of(order, service.now(), catalogue(List.of(order)));
    }

    /** Covers for the order lines, in one query across every order on the page. */
    private Map<String, Game> catalogue(Collection<Order> orders) {
        return games.findAll(orders.stream().flatMap(o -> o.lines().stream()).map(Order.Line::sku).distinct().toList());
    }
}
