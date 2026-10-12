package com.ecommerce.order_service.service;


import com.ecommerce.order_service.client.ProductServiceClient;
import com.ecommerce.order_service.clientpayload.ProductDTO;
import com.ecommerce.order_service.clientpayload.StockLine;
import com.ecommerce.order_service.exceptions.APIException;
import com.ecommerce.order_service.exceptions.ResourceNotFoundException;
import com.ecommerce.order_service.model.*;
import com.ecommerce.order_service.payload.*;
import com.ecommerce.order_service.repositories.CartRepository;
import com.ecommerce.order_service.repositories.OrderItemRepository;
import com.ecommerce.order_service.repositories.OrderRepository;
import com.ecommerce.order_service.util.AuthUtil;
import jakarta.transaction.Transactional;
import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class OrderServiceImpl implements OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private CartService cartService;

    @Autowired
    private ModelMapper modelMapper;

    @Autowired
    private ProductServiceClient productServiceClient;

    @Autowired
    private AuthUtil authUtil;

    @Autowired
    private NotificationPublisher notificationPublisher;

    @Override
    @Transactional
    public OrderDTO placeOrder(String emailId, Long addressId, String paymentMethod, String pgName, String pgPaymentId, String pgStatus, String pgResponseMessage) {
        Cart cart = cartRepository.findCartByEmail(emailId);
        if (cart == null) {
            throw new ResourceNotFoundException("Cart", "email", emailId);
        }

        if (cart.getCartItems().isEmpty()) {
            throw new APIException("Cart is empty");
        }

        List<StockLine> stockLines = cart.getCartItems().stream()
                .map(item -> new StockLine(item.getProductSnapshot().getProductId(), item.getQuantity()))
                .toList();

        // Stock lives in product-service, outside this transaction. It is taken for the whole
        // cart in one call - all lines or none - before anything is written here, and given
        // back if the rest of the checkout fails, so a rolled-back order never keeps stock.
        productServiceClient.reduceProductQuantities(stockLines);

        try {
            Order order = new Order();
            order.setEmail(emailId);
            order.setOrderDate(LocalDate.now());
            order.setTotalAmount(cart.getTotalPrice());
            order.setOrderStatus("Accepted");
            order.setAddressId(addressId);

            Payment payment = new Payment(paymentMethod, pgPaymentId, pgStatus, pgResponseMessage, pgName);
            payment.setOrder(order);
            order.setPayment(payment);

            Order savedOrder = orderRepository.save(order);

            List<OrderItem> orderItems = new ArrayList<>();

            for (CartItem cartItem : cart.getCartItems()) {
                OrderItem orderItem = new OrderItem();
                orderItem.setProductSnapshot(cartItem.getProductSnapshot());
                orderItem.setQuantity(cartItem.getQuantity());
                orderItem.setDiscount(cartItem.getDiscount());
                orderItem.setOrderedProductPrice(cartItem.getProductPrice());
                orderItem.setOrder(savedOrder);
                orderItems.add(orderItem);
            }

            orderItems = orderItemRepository.saveAll(orderItems);

            cart.getCartItems().forEach(item ->
                    cartService.deleteProductFromCart(cart.getCartId(), item.getProductSnapshot().getProductId()));

            // Surface constraint failures here, while the stock can still be given back.
            orderRepository.flush();

            OrderDTO orderDTO = modelMapper.map(savedOrder, OrderDTO.class);
            orderDTO.setOrderItems(orderItems.stream().filter(Objects::nonNull).map(this::mapToOrderItemDTO).collect(Collectors.toList()));
            orderDTO.setAddressId(addressId);

            String subject = "Order Confirmation - Order " + savedOrder.getOrderId();
            String body = "Thank you for your purchase! Your order " + savedOrder.getOrderId()
                    + " has been placed successfully with total amount " + savedOrder.getTotalAmount() + ".";
            notificationPublisher.sendEmailNotification(new EmailDetails(emailId, body, subject));

            return orderDTO;
        } catch (RuntimeException e) {
            restoreStock(stockLines, e);
            throw e;
        }
    }

    private void restoreStock(List<StockLine> stockLines, RuntimeException cause) {
        try {
            productServiceClient.restoreProductQuantities(stockLines);
        } catch (RuntimeException restoreFailure) {
            // Nothing else will put these units back; the log line is the reconciliation record.
            log.error("Checkout failed and its stock could not be restored; restore by hand: {}", stockLines, restoreFailure);
            cause.addSuppressed(restoreFailure);
        }
    }

    @Override
    public OrderResponse getAllOrders(Integer pageNumber, Integer pageSize, String sortBy, String sortOrder) {
        Sort sortByAndOrder = sortOrder.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        Pageable pageDetails = PageRequest.of(pageNumber, pageSize, sortByAndOrder);
        Page<Order> pageOrders = orderRepository.findAll(pageDetails);
        List<Order> orders = pageOrders.getContent();
        List<OrderDTO> orderDTOs = orders.stream()
                .map(this::mapToOrderDTO)
                .toList();
        OrderResponse orderResponse = new OrderResponse();
        orderResponse.setContent(orderDTOs);
        orderResponse.setPageNumber(pageOrders.getNumber());
        orderResponse.setPageSize(pageOrders.getSize());
        orderResponse.setTotalElements(pageOrders.getTotalElements());
        orderResponse.setTotalPages(pageOrders.getTotalPages());
        orderResponse.setLastPage(pageOrders.isLast());
        return orderResponse;
    }

    @Override
    public OrderDTO updateOrder(Long orderId, String status) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "orderId", orderId));
        order.setOrderStatus(status);
        orderRepository.save(order);
        return mapToOrderDTO(order);
    }

    @Override
    public OrderResponse getAllSellerOrders(Integer pageNumber, Integer pageSize, String sortBy, String sortOrder) {
        Sort sortByAndOrder = sortOrder.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();
        Pageable pageDetails = PageRequest.of(pageNumber, pageSize, sortByAndOrder);

        String sellerEmail = authUtil.loggedInEmail();

        List<Order> sortedOrders = orderRepository.findAll(sortByAndOrder);

        List<Order> sellerOrders = sortedOrders.stream()
                .filter(order -> order.getOrderItems() != null)
                .filter(order -> order.getOrderItems().stream()
                        .anyMatch(orderItem -> {
                            var product = orderItem.getProductSnapshot();
                            if (product == null) {
                                return false;
                            }
                            return sellerEmail.equalsIgnoreCase(product.getSellerEmail());
                        }))
                .toList();

        int totalElements = sellerOrders.size();
        int fromIndex = Math.min(pageNumber * pageSize, totalElements);
        int toIndex = Math.min(fromIndex + pageSize, totalElements);
        List<Order> pagedOrders = sellerOrders.subList(fromIndex, toIndex);

        List<OrderDTO> orderDTOs = pagedOrders.stream()
                .map(this::mapToOrderDTO)
                .toList();

        OrderResponse orderResponse = new OrderResponse();
        orderResponse.setContent(orderDTOs);
        orderResponse.setPageNumber(pageNumber);
        orderResponse.setPageSize(pageSize);
        orderResponse.setTotalElements((long) totalElements);
        int totalPages = (int) Math.ceil((double) totalElements / pageSize);
        orderResponse.setTotalPages(totalPages);
        orderResponse.setLastPage(pageNumber >= totalPages - 1);
        return orderResponse;
    }

    @Override
    public OrderResponse getCustomerOrders(String emailId, Integer pageNumber, Integer pageSize, String sortBy, String sortOrder) {
        Sort sortByAndOrder = sortOrder.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        Pageable pageDetails = PageRequest.of(pageNumber, pageSize, sortByAndOrder);
        Page<Order> pageOrders = orderRepository.findByEmailIgnoreCase(emailId, pageDetails);
        List<OrderDTO> orderDTOs = pageOrders.getContent().stream()
                .map(this::mapToOrderDTO)
                .toList();

        OrderResponse orderResponse = new OrderResponse();
        orderResponse.setContent(orderDTOs);
        orderResponse.setPageNumber(pageOrders.getNumber());
        orderResponse.setPageSize(pageOrders.getSize());
        orderResponse.setTotalElements(pageOrders.getTotalElements());
        orderResponse.setTotalPages(pageOrders.getTotalPages());
        orderResponse.setLastPage(pageOrders.isLast());
        return orderResponse;
    }

    private OrderItemDTO mapToOrderItemDTO(OrderItem orderItem) {
        OrderItemDTO dto = new OrderItemDTO();
        dto.setOrderItemId(orderItem.getOrderItemId());
        dto.setQuantity(orderItem.getQuantity());
        dto.setDiscount(orderItem.getDiscount());
        dto.setOrderedProductPrice(orderItem.getOrderedProductPrice());

        ProductDTO productDTO = new ProductDTO();
        if (orderItem.getProductSnapshot() != null) {
            productDTO.setProductId(orderItem.getProductSnapshot().getProductId());
            productDTO.setProductName(orderItem.getProductSnapshot().getProductName());
            productDTO.setImage(orderItem.getProductSnapshot().getImage());
            productDTO.setDescription(orderItem.getProductSnapshot().getDescription());
            productDTO.setPrice(orderItem.getProductSnapshot().getPrice() == null ? 0.0 : orderItem.getProductSnapshot().getPrice());
            productDTO.setDiscount(orderItem.getProductSnapshot().getDiscount() == null ? 0.0 : orderItem.getProductSnapshot().getDiscount());
            productDTO.setSpecialPrice(orderItem.getProductSnapshot().getSpecialPrice() == null ? 0.0 : orderItem.getProductSnapshot().getSpecialPrice());
            productDTO.setQuantity(orderItem.getQuantity());
            productDTO.setSellerId(orderItem.getProductSnapshot().getSellerId());
            productDTO.setSellerEmail(orderItem.getProductSnapshot().getSellerEmail());
        }
        dto.setProduct(productDTO);
        return dto;
    }

    // Thêm method mới
    private OrderDTO mapToOrderDTO(Order order) {
        OrderDTO orderDTO = new OrderDTO();
        orderDTO.setOrderId(order.getOrderId());
        orderDTO.setEmail(order.getEmail());
        orderDTO.setOrderDate(order.getOrderDate());
        orderDTO.setTotalAmount(order.getTotalAmount());
        orderDTO.setOrderStatus(order.getOrderStatus());
        orderDTO.setAddressId(order.getAddressId());

        if (order.getPayment() != null) {
            orderDTO.setPayment(modelMapper.map(order.getPayment(), PaymentDTO.class));
        }

        orderDTO.setOrderItems(order.getOrderItems().stream()
                .filter(Objects::nonNull)
                .map(this::mapToOrderItemDTO)
                .collect(Collectors.toList()));

        return orderDTO;
    }
}
