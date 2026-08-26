package com.ecommerce.order_service.unit;

import com.ecommerce.order_service.client.ProductServiceClient;
import com.ecommerce.order_service.exceptions.APIException;
import com.ecommerce.order_service.exceptions.ResourceNotFoundException;
import com.ecommerce.order_service.model.Cart;
import com.ecommerce.order_service.model.CartItem;
import com.ecommerce.order_service.model.Order;
import com.ecommerce.order_service.model.OrderItem;
import com.ecommerce.order_service.model.ProductSnapshot;
import com.ecommerce.order_service.payload.EmailDetails;
import com.ecommerce.order_service.payload.OrderDTO;
import com.ecommerce.order_service.repositories.CartRepository;
import com.ecommerce.order_service.repositories.OrderItemRepository;
import com.ecommerce.order_service.repositories.OrderRepository;
import com.ecommerce.order_service.service.CartService;
import com.ecommerce.order_service.service.NotificationPublisher;
import com.ecommerce.order_service.service.OrderServiceImpl;
import com.ecommerce.order_service.util.AuthUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.modelmapper.ModelMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrderServiceImpl} - checkout, which is where the platform commits:
 * it writes the order, calls another service to take the stock, empties the cart and asks
 * the notification service to mail a confirmation.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Unit - OrderServiceImpl")
class OrderServiceImplTest {

    private static final String BUYER = "buyer@techzone.test";

    @Mock
    private CartRepository cartRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private CartService cartService;

    @Mock
    private ProductServiceClient productServiceClient;

    @Mock
    private AuthUtil authUtil;

    @Mock
    private NotificationPublisher notificationPublisher;

    @Spy
    private ModelMapper modelMapper = new ModelMapper();

    @InjectMocks
    private OrderServiceImpl orderService;

    private static CartItem line(long productId, String name, double unitPrice, int quantity) {
        CartItem item = new CartItem();
        item.setQuantity(quantity);
        item.setProductPrice(unitPrice);
        item.setDiscount(10.0);
        item.setProductSnapshot(new ProductSnapshot(productId, name, "default.png", "A laptop used in tests",
                unitPrice * 1.1, 10.0, unitPrice, 3L, "seller@techzone.test"));
        return item;
    }

    private static Cart cartWith(CartItem... items) {
        Cart cart = new Cart();
        cart.setCartId(1L);
        cart.setUserEmail(BUYER);
        cart.setCartItems(new ArrayList<>(List.of(items)));
        double total = 0.0;
        for (CartItem item : items) {
            item.setCart(cart);
            total += item.getProductPrice() * item.getQuantity();
        }
        cart.setTotalPrice(total);
        return cart;
    }

    private void stubHappyPathPersistence() {
        when(orderRepository.save(any(Order.class))).thenAnswer(call -> {
            Order order = call.getArgument(0);
            order.setOrderId(500L);
            return order;
        });
        when(orderItemRepository.saveAll(any())).thenAnswer(call -> {
            List<OrderItem> items = call.getArgument(0);
            long id = 1;
            for (OrderItem item : items) {
                item.setOrderItemId(id++);
            }
            return items;
        });
        when(cartService.deleteProductFromCart(anyLong(), anyLong())).thenReturn("removed");
    }

    @Nested
    @DisplayName("placeOrder")
    class PlaceOrder {

        @Test
        @DisplayName("writes an accepted order for today with the cart total")
        void writesAcceptedOrder() {
            when(cartRepository.findCartByEmail(BUYER)).thenReturn(cartWith(line(7L, "MSI Katana 15", 900.0, 2)));
            stubHappyPathPersistence();

            OrderDTO order = orderService.placeOrder(BUYER, 11L, "card", "Stripe", "pi_123", "succeeded", "ok");

            assertThat(order.getOrderId()).isEqualTo(500L);
            assertThat(order.getOrderStatus()).isEqualTo("Accepted");
            assertThat(order.getTotalAmount()).isEqualTo(1800.0);
            assertThat(order.getOrderDate()).isEqualTo(LocalDate.now());
            assertThat(order.getAddressId()).isEqualTo(11L);
        }

        @Test
        @DisplayName("copies every cart line into the order with its snapshot and price")
        void copiesCartLines() {
            when(cartRepository.findCartByEmail(BUYER)).thenReturn(cartWith(
                    line(7L, "MSI Katana 15", 900.0, 2),
                    line(8L, "Dell XPS 13", 1500.0, 1)));
            stubHappyPathPersistence();

            OrderDTO order = orderService.placeOrder(BUYER, 11L, "card", "Stripe", "pi_123", "succeeded", "ok");

            assertThat(order.getOrderItems()).hasSize(2);
            assertThat(order.getOrderItems()).extracting(item -> item.getProduct().getProductName())
                    .containsExactly("MSI Katana 15", "Dell XPS 13");
            assertThat(order.getOrderItems().get(0).getOrderedProductPrice()).isEqualTo(900.0);
            assertThat(order.getOrderItems().get(0).getQuantity()).isEqualTo(2);
        }

        @Test
        @DisplayName("records the payment details supplied by the checkout call")
        void recordsPayment() {
            when(cartRepository.findCartByEmail(BUYER)).thenReturn(cartWith(line(7L, "MSI Katana 15", 900.0, 1)));
            stubHappyPathPersistence();

            orderService.placeOrder(BUYER, 11L, "card", "Stripe", "pi_123", "succeeded", "Payment complete");

            ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
            verify(orderRepository).save(captor.capture());
            assertThat(captor.getValue().getPayment().getPaymentMethod()).isEqualTo("card");
            assertThat(captor.getValue().getPayment().getPgPaymentId()).isEqualTo("pi_123");
            assertThat(captor.getValue().getPayment().getPgStatus()).isEqualTo("succeeded");
        }

        @Test
        @DisplayName("takes the stock for every line from the catalogue service")
        void reducesStockForEveryLine() {
            when(cartRepository.findCartByEmail(BUYER)).thenReturn(cartWith(
                    line(7L, "MSI Katana 15", 900.0, 2),
                    line(8L, "Dell XPS 13", 1500.0, 1)));
            stubHappyPathPersistence();

            orderService.placeOrder(BUYER, 11L, "card", "Stripe", "pi_123", "succeeded", "ok");

            verify(productServiceClient).reduceProductQuantity(7L, 2);
            verify(productServiceClient).reduceProductQuantity(8L, 1);
        }

        @Test
        @DisplayName("empties the cart line by line once the order is written")
        void emptiesTheCart() {
            when(cartRepository.findCartByEmail(BUYER)).thenReturn(cartWith(
                    line(7L, "MSI Katana 15", 900.0, 2),
                    line(8L, "Dell XPS 13", 1500.0, 1)));
            stubHappyPathPersistence();

            orderService.placeOrder(BUYER, 11L, "card", "Stripe", "pi_123", "succeeded", "ok");

            verify(cartService).deleteProductFromCart(1L, 7L);
            verify(cartService).deleteProductFromCart(1L, 8L);
        }

        @Test
        @DisplayName("publishes an order-confirmation email naming the order and the amount")
        void publishesConfirmationEmail() {
            when(cartRepository.findCartByEmail(BUYER)).thenReturn(cartWith(line(7L, "MSI Katana 15", 900.0, 2)));
            stubHappyPathPersistence();

            orderService.placeOrder(BUYER, 11L, "card", "Stripe", "pi_123", "succeeded", "ok");

            ArgumentCaptor<EmailDetails> captor = ArgumentCaptor.forClass(EmailDetails.class);
            verify(notificationPublisher).sendEmailNotification(captor.capture());
            assertThat(captor.getValue().getRecipient()).isEqualTo(BUYER);
            assertThat(captor.getValue().getSubject()).contains("500");
            assertThat(captor.getValue().getMsgBody()).contains("1800.0");
        }

        @Test
        @DisplayName("refuses to check out an empty cart")
        void refusesEmptyCart() {
            when(cartRepository.findCartByEmail(BUYER)).thenReturn(cartWith());

            assertThatThrownBy(() -> orderService.placeOrder(BUYER, 11L, "card", "Stripe", "pi", "ok", "ok"))
                    .isInstanceOf(APIException.class)
                    .hasMessage("Cart is empty");

            verify(orderRepository, never()).save(any(Order.class));
            verify(productServiceClient, never()).reduceProductQuantity(anyLong(), anyInt());
        }

        @Test
        @DisplayName("fails when the buyer has no cart at all")
        void failsWithoutCart() {
            when(cartRepository.findCartByEmail(BUYER)).thenReturn(null);

            assertThatThrownBy(() -> orderService.placeOrder(BUYER, 11L, "card", "Stripe", "pi", "ok", "ok"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Cart not found with email");
        }

        @Test
        @DisplayName("BUG-01 characterisation: a line that fails halfway leaves the earlier lines' stock taken")
        void partialFailureLeavesStockTaken() {
            // Stock is decremented through a remote HTTP call inside the local transaction.
            // When the second call fails, the database work rolls back but the first
            // decrement has already been committed in product-service - the classic
            // dual-write problem, documented as BUG-01 in docs/backend/known-defects.md.
            when(cartRepository.findCartByEmail(BUYER)).thenReturn(cartWith(
                    line(7L, "MSI Katana 15", 900.0, 2),
                    line(8L, "Dell XPS 13", 1500.0, 1)));
            stubHappyPathPersistence();
            doThrow(new APIException("Insufficient product quantity"))
                    .when(productServiceClient).reduceProductQuantity(8L, 1);

            assertThatThrownBy(() -> orderService.placeOrder(BUYER, 11L, "card", "Stripe", "pi", "ok", "ok"))
                    .isInstanceOf(APIException.class);

            // The first line was already taken and nothing puts it back.
            verify(productServiceClient).reduceProductQuantity(7L, 2);
            verify(notificationPublisher, never()).sendEmailNotification(any(EmailDetails.class));
        }
    }

    @Nested
    @DisplayName("updateOrder")
    class UpdateOrder {

        @Test
        @DisplayName("stores the new status on an existing order")
        void storesNewStatus() {
            Order order = new Order();
            order.setOrderId(500L);
            order.setEmail(BUYER);
            order.setOrderDate(LocalDate.now());
            order.setTotalAmount(1800.0);
            order.setOrderStatus("Accepted");
            order.setOrderItems(new ArrayList<>());
            when(orderRepository.findById(500L)).thenReturn(Optional.of(order));

            OrderDTO updated = orderService.updateOrder(500L, "Shipped");

            assertThat(updated.getOrderStatus()).isEqualTo("Shipped");
            verify(orderRepository).save(order);
        }

        @Test
        @DisplayName("BUG-18 characterisation: any free-text status is accepted")
        void acceptsAnyStatusText() {
            // There is no state machine and no allow-list behind the status field, so a
            // typo or an arbitrary string is stored as-is. Documented as BUG-18.
            Order order = new Order();
            order.setOrderId(500L);
            order.setOrderStatus("Accepted");
            order.setOrderItems(new ArrayList<>());
            when(orderRepository.findById(500L)).thenReturn(Optional.of(order));

            OrderDTO updated = orderService.updateOrder(500L, "banana");

            assertThat(updated.getOrderStatus()).isEqualTo("banana");
        }

        @Test
        @DisplayName("fails when the order does not exist")
        void failsForUnknownOrder() {
            when(orderRepository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orderService.updateOrder(404L, "Shipped"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Order not found with orderId: 404");
        }
    }
}
