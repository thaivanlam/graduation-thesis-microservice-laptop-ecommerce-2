package com.ecommerce.order_service.integration;

import com.ecommerce.order_service.clientpayload.ProductDTO;
import com.ecommerce.order_service.controller.CartController;
import com.ecommerce.order_service.exceptions.APIException;
import com.ecommerce.order_service.exceptions.ResourceNotFoundException;
import com.ecommerce.order_service.payload.CartDTO;
import com.ecommerce.order_service.service.CartService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the cart web layer: routing, path-variable binding, JSON shape and
 * the exception advice. The cart service itself is stubbed.
 */
// ADR-0012 made this service an OIDC resource server, so @WebMvcTest now loads the
// SecurityFilterChain too and every request below would answer 401 or 403. The filters are
// switched off here on purpose: what this class covers is the web contract - URL mapping,
// query-parameter binding and defaulting, JSON serialisation, and the @RestControllerAdvice
// that turns domain exceptions into HTTP responses. Who may call these paths is a separate
// question with its own test, {@code OrderServiceSecurityTest} , which asserts it against the real chain.
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(CartController.class)
@DisplayName("Integration - CartController HTTP contract")
class CartControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CartService cartService;

    private static CartDTO sampleCart() {
        ProductDTO product = new ProductDTO();
        product.setProductId(7L);
        product.setProductName("MSI Katana 15");
        product.setSpecialPrice(900.0);
        product.setQuantity(2);

        CartDTO cart = new CartDTO();
        cart.setCartId(1L);
        cart.setTotalPrice(1800.0);
        cart.setProducts(List.of(product));
        return cart;
    }

    @Test
    @DisplayName("POST /api/carts/products/{productId}/quantity/{quantity} returns 201 with the updated cart")
    void addsProductToCart() throws Exception {
        when(cartService.addProductToCart(7L, 2)).thenReturn(sampleCart());

        mockMvc.perform(post("/api/carts/products/7/quantity/2"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cartId").value(1))
                .andExpect(jsonPath("$.totalPrice").value(1800.0))
                .andExpect(jsonPath("$.products[0].productName").value("MSI Katana 15"));
    }

    @Test
    @DisplayName("a stock violation on add becomes a 400 with the reason")
    void stockViolationBecomesBadRequest() throws Exception {
        when(cartService.addProductToCart(anyLong(), anyInt()))
                .thenThrow(new APIException("MSI Katana 15 is not available"));

        mockMvc.perform(post("/api/carts/products/7/quantity/2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("MSI Katana 15 is not available"))
                .andExpect(jsonPath("$.status").value(false));
    }

    @Test
    @DisplayName("an unknown product on add becomes a 404")
    void unknownProductBecomesNotFound() throws Exception {
        when(cartService.addProductToCart(anyLong(), anyInt()))
                .thenThrow(new ResourceNotFoundException("Product", "productId", 404L));

        mockMvc.perform(post("/api/carts/products/404/quantity/1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Product not found with productId: 404"));
    }

    @Test
    @DisplayName("GET /api/carts/users/cart returns 200 with the signed-in buyer's cart")
    void returnsOwnCart() throws Exception {
        when(cartService.getCart()).thenReturn(sampleCart());

        mockMvc.perform(get("/api/carts/users/cart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cartId").value(1));
    }

    @Test
    @DisplayName("BUG-12 characterisation: GET /api/carts answers 302 FOUND for a successful read")
    void listAllCartsAnswers302() throws Exception {
        // As with the product keyword search, a redirect status is used for a successful
        // read. Documented as BUG-12 in docs/backend/known-defects.md.
        when(cartService.getAllCarts()).thenReturn(List.of(sampleCart()));

        mockMvc.perform(get("/api/carts"))
                .andExpect(status().isFound());
    }

    @Test
    @DisplayName("PUT .../quantity/increase adds one unit")
    void increaseAddsOneUnit() throws Exception {
        when(cartService.updateProductQuantityInCart(7L, 1)).thenReturn(sampleCart());

        mockMvc.perform(put("/api/cart/products/7/quantity/increase"))
                .andExpect(status().isOk());

        verify(cartService).updateProductQuantityInCart(7L, 1);
    }

    @Test
    @DisplayName("PUT .../quantity/delete removes one unit")
    void deleteRemovesOneUnit() throws Exception {
        when(cartService.updateProductQuantityInCart(7L, -1)).thenReturn(sampleCart());

        mockMvc.perform(put("/api/cart/products/7/quantity/delete"))
                .andExpect(status().isOk());

        verify(cartService).updateProductQuantityInCart(7L, -1);
    }

    @Test
    @DisplayName("any operation other than 'delete' is treated as an increase")
    void unknownOperationIsTreatedAsIncrease() throws Exception {
        when(cartService.updateProductQuantityInCart(7L, 1)).thenReturn(sampleCart());

        mockMvc.perform(put("/api/cart/products/7/quantity/whatever"))
                .andExpect(status().isOk());

        verify(cartService).updateProductQuantityInCart(7L, 1);
    }

    @Test
    @DisplayName("DELETE /api/carts/{cartId}/product/{productId} returns 200 with a confirmation message")
    void deletesLine() throws Exception {
        when(cartService.deleteProductFromCart(1L, 7L))
                .thenReturn("Product MSI Katana 15 removed from the cart!!!");

        mockMvc.perform(delete("/api/carts/1/product/7"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/cart/create accepts the bulk cart sync and returns 201")
    void bulkSync() throws Exception {
        when(cartService.createOrUpdateCartWithItems(any()))
                .thenReturn("Cart created/updated with the new items successfully");

        mockMvc.perform(post("/api/cart/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"productId\":7,\"quantity\":2},{\"productId\":8,\"quantity\":1}]"))
                .andExpect(status().isCreated());
    }
}
