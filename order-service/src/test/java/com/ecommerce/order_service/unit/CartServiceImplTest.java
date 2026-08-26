package com.ecommerce.order_service.unit;

import com.ecommerce.order_service.client.ProductServiceClient;
import com.ecommerce.order_service.clientpayload.ProductDTO;
import com.ecommerce.order_service.exceptions.APIException;
import com.ecommerce.order_service.exceptions.ResourceNotFoundException;
import com.ecommerce.order_service.model.Cart;
import com.ecommerce.order_service.model.CartItem;
import com.ecommerce.order_service.model.ProductSnapshot;
import com.ecommerce.order_service.payload.CartDTO;
import com.ecommerce.order_service.payload.CartItemDTO;
import com.ecommerce.order_service.repositories.CartItemRepository;
import com.ecommerce.order_service.repositories.CartRepository;
import com.ecommerce.order_service.service.CartServiceImpl;
import com.ecommerce.order_service.util.AuthUtil;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CartServiceImpl}, the module that holds the shopping-cart rules:
 * stock validation before a line is accepted, quantity arithmetic, and the running total.
 *
 * <p>The product catalogue lives in another service, so {@link ProductServiceClient} is a
 * test double here. That is deliberate: this file tests the cart logic in isolation, while
 * the client itself is covered by an integration test against a stubbed HTTP server.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Unit - CartServiceImpl")
class CartServiceImplTest {

    private static final String BUYER = "buyer@techzone.test";

    @Mock
    private CartRepository cartRepository;

    @Mock
    private CartItemRepository cartItemRepository;

    @Mock
    private AuthUtil authUtil;

    @Mock
    private ProductServiceClient productServiceClient;

    @Spy
    private ModelMapper modelMapper = new ModelMapper();

    @InjectMocks
    private CartServiceImpl cartService;

    @BeforeEach
    void signIn() {
        when(authUtil.loggedInEmail()).thenReturn(BUYER);
        when(cartItemRepository.save(any(CartItem.class))).thenAnswer(call -> call.getArgument(0));
        when(cartRepository.save(any(Cart.class))).thenAnswer(call -> {
            Cart cart = call.getArgument(0);
            if (cart.getCartId() == null) {
                cart.setCartId(1L);
            }
            return cart;
        });
    }

    private static ProductDTO catalogueProduct(long id, String name, double specialPrice, int stock) {
        ProductDTO product = new ProductDTO();
        product.setProductId(id);
        product.setProductName(name);
        product.setDescription("A laptop used in tests");
        product.setImage("default.png");
        product.setPrice(specialPrice * 1.1);
        product.setDiscount(10.0);
        product.setSpecialPrice(specialPrice);
        product.setQuantity(stock);
        product.setSellerId(3L);
        product.setSellerEmail("seller@techzone.test");
        return product;
    }

    private static Cart existingCart(CartItem... items) {
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

    private static CartItem line(long productId, String name, double unitPrice, int quantity) {
        CartItem item = new CartItem();
        item.setCartItemId(productId);
        item.setQuantity(quantity);
        item.setProductPrice(unitPrice);
        item.setDiscount(10.0);
        item.setProductSnapshot(new ProductSnapshot(productId, name, "default.png", "A laptop used in tests",
                unitPrice * 1.1, 10.0, unitPrice, 3L, "seller@techzone.test"));
        return item;
    }

    @Nested
    @DisplayName("addProductToCart")
    class AddProductToCart {

        @Test
        @DisplayName("creates the cart on first use and stores the line at the discounted price")
        void createsCartOnFirstUse() {
            when(cartRepository.findCartByEmail(BUYER)).thenReturn(null);
            when(productServiceClient.getProductById(7L)).thenReturn(catalogueProduct(7L, "MSI Katana 15", 900.0, 10));
            when(cartItemRepository.findCartItemByProductIdAndCartId(1L, 7L)).thenReturn(null);
            when(cartRepository.findById(1L)).thenAnswer(call -> Optional.empty());

            CartDTO cart = cartService.addProductToCart(7L, 2);

            assertThat(cart.getTotalPrice()).isEqualTo(1800.0);
            assertThat(cart.getProducts()).hasSize(1);
            assertThat(cart.getProducts().get(0).getProductName()).isEqualTo("MSI Katana 15");
            assertThat(cart.getProducts().get(0).getQuantity()).isEqualTo(2);
        }

        @Test
        @DisplayName("copies the catalogue values into a snapshot so later price changes do not rewrite history")
        void snapshotsTheProduct() {
            when(cartRepository.findCartByEmail(BUYER)).thenReturn(null);
            when(productServiceClient.getProductById(7L)).thenReturn(catalogueProduct(7L, "MSI Katana 15", 900.0, 10));
            when(cartItemRepository.findCartItemByProductIdAndCartId(1L, 7L)).thenReturn(null);

            cartService.addProductToCart(7L, 1);

            ArgumentCaptor<CartItem> captor = ArgumentCaptor.forClass(CartItem.class);
            verify(cartItemRepository).save(captor.capture());
            ProductSnapshot snapshot = captor.getValue().getProductSnapshot();
            assertThat(snapshot.getProductId()).isEqualTo(7L);
            assertThat(snapshot.getProductName()).isEqualTo("MSI Katana 15");
            assertThat(snapshot.getSpecialPrice()).isEqualTo(900.0);
            assertThat(snapshot.getSellerEmail()).isEqualTo("seller@techzone.test");
        }

        @Test
        @DisplayName("adds to the quantity already in the cart instead of duplicating the line")
        void accumulatesExistingLine() {
            CartItem existing = line(7L, "MSI Katana 15", 900.0, 2);
            Cart cart = existingCart(existing);
            when(cartRepository.findCartByEmail(BUYER)).thenReturn(cart);
            when(cartRepository.findById(1L)).thenReturn(Optional.of(cart));
            when(productServiceClient.getProductById(7L)).thenReturn(catalogueProduct(7L, "MSI Katana 15", 900.0, 10));
            when(cartItemRepository.findCartItemByProductIdAndCartId(1L, 7L)).thenReturn(existing);

            CartDTO result = cartService.addProductToCart(7L, 3);

            assertThat(existing.getQuantity()).isEqualTo(5);
            assertThat(result.getProducts()).hasSize(1);
            assertThat(result.getTotalPrice()).isEqualTo(4500.0);
        }

        @Test
        @DisplayName("refuses a product that is out of stock")
        void refusesOutOfStockProduct() {
            when(cartRepository.findCartByEmail(BUYER)).thenReturn(existingCart());
            when(productServiceClient.getProductById(7L)).thenReturn(catalogueProduct(7L, "MSI Katana 15", 900.0, 0));

            assertThatThrownBy(() -> cartService.addProductToCart(7L, 1))
                    .isInstanceOf(APIException.class)
                    .hasMessage("MSI Katana 15 is not available");

            verify(cartItemRepository, never()).save(any(CartItem.class));
        }

        @Test
        @DisplayName("refuses a quantity larger than the stock on hand")
        void refusesQuantityAboveStock() {
            when(cartRepository.findCartByEmail(BUYER)).thenReturn(existingCart());
            when(productServiceClient.getProductById(7L)).thenReturn(catalogueProduct(7L, "MSI Katana 15", 900.0, 3));
            when(cartItemRepository.findCartItemByProductIdAndCartId(1L, 7L)).thenReturn(null);

            assertThatThrownBy(() -> cartService.addProductToCart(7L, 4))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("less than or equal to the quantity 3");
        }

        @Test
        @DisplayName("counts what is already in the cart when checking the stock limit")
        void countsExistingQuantityAgainstStock() {
            CartItem existing = line(7L, "MSI Katana 15", 900.0, 2);
            Cart cart = existingCart(existing);
            when(cartRepository.findCartByEmail(BUYER)).thenReturn(cart);
            when(productServiceClient.getProductById(7L)).thenReturn(catalogueProduct(7L, "MSI Katana 15", 900.0, 3));
            when(cartItemRepository.findCartItemByProductIdAndCartId(1L, 7L)).thenReturn(existing);

            assertThatThrownBy(() -> cartService.addProductToCart(7L, 2))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("less than or equal to the quantity 3");

            assertThat(existing.getQuantity()).isEqualTo(2);
        }

        @Test
        @DisplayName("accepts the quantity that exactly exhausts the stock")
        void acceptsExactStock() {
            when(cartRepository.findCartByEmail(BUYER)).thenReturn(existingCart());
            when(productServiceClient.getProductById(7L)).thenReturn(catalogueProduct(7L, "MSI Katana 15", 900.0, 3));
            when(cartItemRepository.findCartItemByProductIdAndCartId(1L, 7L)).thenReturn(null);

            CartDTO cart = cartService.addProductToCart(7L, 3);

            assertThat(cart.getTotalPrice()).isEqualTo(2700.0);
        }

        @Test
        @DisplayName("fails when the catalogue has no such product")
        void failsForUnknownProduct() {
            when(cartRepository.findCartByEmail(BUYER)).thenReturn(existingCart());
            when(productServiceClient.getProductById(404L)).thenReturn(null);

            assertThatThrownBy(() -> cartService.addProductToCart(404L, 1))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Product not found with productId: 404");
        }
    }

    @Nested
    @DisplayName("updateProductQuantityInCart")
    class UpdateQuantity {

        @Test
        @DisplayName("increments the line and the cart total")
        void incrementsLine() {
            CartItem existing = line(7L, "MSI Katana 15", 900.0, 2);
            Cart cart = existingCart(existing);
            when(cartRepository.findCartByEmail(BUYER)).thenReturn(cart);
            when(cartRepository.findById(1L)).thenReturn(Optional.of(cart));
            when(productServiceClient.getProductById(7L)).thenReturn(catalogueProduct(7L, "MSI Katana 15", 900.0, 10));
            when(cartItemRepository.findCartItemByProductIdAndCartId(1L, 7L)).thenReturn(existing);

            CartDTO result = cartService.updateProductQuantityInCart(7L, 1);

            assertThat(existing.getQuantity()).isEqualTo(3);
            assertThat(result.getTotalPrice()).isEqualTo(2700.0);
        }

        @Test
        @DisplayName("decrements the line and the cart total")
        void decrementsLine() {
            CartItem existing = line(7L, "MSI Katana 15", 900.0, 3);
            Cart cart = existingCart(existing);
            when(cartRepository.findCartByEmail(BUYER)).thenReturn(cart);
            when(cartRepository.findById(1L)).thenReturn(Optional.of(cart));
            when(productServiceClient.getProductById(7L)).thenReturn(catalogueProduct(7L, "MSI Katana 15", 900.0, 10));
            when(cartItemRepository.findCartItemByProductIdAndCartId(1L, 7L)).thenReturn(existing);

            CartDTO result = cartService.updateProductQuantityInCart(7L, -1);

            assertThat(existing.getQuantity()).isEqualTo(2);
            assertThat(result.getTotalPrice()).isEqualTo(1800.0);
        }

        @Test
        @DisplayName("removes the line entirely when the quantity reaches zero")
        void removesLineAtZero() {
            CartItem existing = line(7L, "MSI Katana 15", 900.0, 1);
            Cart cart = existingCart(existing);
            when(cartRepository.findCartByEmail(BUYER)).thenReturn(cart);
            when(cartRepository.findById(1L)).thenReturn(Optional.of(cart));
            when(productServiceClient.getProductById(7L)).thenReturn(catalogueProduct(7L, "MSI Katana 15", 900.0, 10));
            when(cartItemRepository.findCartItemByProductIdAndCartId(1L, 7L)).thenReturn(existing);

            cartService.updateProductQuantityInCart(7L, -1);

            verify(cartItemRepository).deleteCartItemByProductIdAndCartId(1L, 7L);
        }

        @Test
        @DisplayName("refuses an update that would drive the quantity negative")
        void refusesNegativeResult() {
            CartItem existing = line(7L, "MSI Katana 15", 900.0, 1);
            Cart cart = existingCart(existing);
            when(cartRepository.findCartByEmail(BUYER)).thenReturn(cart);
            when(productServiceClient.getProductById(7L)).thenReturn(catalogueProduct(7L, "MSI Katana 15", 900.0, 10));
            when(cartItemRepository.findCartItemByProductIdAndCartId(1L, 7L)).thenReturn(existing);

            assertThatThrownBy(() -> cartService.updateProductQuantityInCart(7L, -5))
                    .isInstanceOf(APIException.class)
                    .hasMessage("The resulting quantity cannot be negative.");
        }

        @Test
        @DisplayName("fails when the product is not in the cart")
        void failsWhenLineMissing() {
            Cart cart = existingCart();
            when(cartRepository.findCartByEmail(BUYER)).thenReturn(cart);
            when(productServiceClient.getProductById(7L)).thenReturn(catalogueProduct(7L, "MSI Katana 15", 900.0, 10));
            when(cartItemRepository.findCartItemByProductIdAndCartId(1L, 7L)).thenReturn(null);

            assertThatThrownBy(() -> cartService.updateProductQuantityInCart(7L, 1))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("not available in the cart");
        }

        @Test
        @DisplayName("BUG-07 characterisation: the stored total drifts when the catalogue price has moved")
        void totalDriftsAfterPriceChange() {
            // The line is repriced to the current catalogue price, but the cart total is
            // adjusted by newPrice * delta rather than being recomputed from the lines.
            // Cart had 2 units at 900 (total 1800); the price is now 800 and one unit is
            // added, so the honest total is 3 * 800 = 2400 while the stored total becomes
            // 1800 + 800 = 2600. Documented as BUG-07 in docs/backend/known-defects.md.
            CartItem existing = line(7L, "MSI Katana 15", 900.0, 2);
            Cart cart = existingCart(existing);
            when(cartRepository.findCartByEmail(BUYER)).thenReturn(cart);
            when(cartRepository.findById(1L)).thenReturn(Optional.of(cart));
            when(productServiceClient.getProductById(7L)).thenReturn(catalogueProduct(7L, "MSI Katana 15", 800.0, 10));
            when(cartItemRepository.findCartItemByProductIdAndCartId(1L, 7L)).thenReturn(existing);

            CartDTO result = cartService.updateProductQuantityInCart(7L, 1);

            double sumOfLines = existing.getProductPrice() * existing.getQuantity();
            assertThat(sumOfLines).isEqualTo(2400.0);
            assertThat(result.getTotalPrice()).isEqualTo(2600.0);
        }
    }

    @Nested
    @DisplayName("deleteProductFromCart")
    class DeleteFromCart {

        @Test
        @DisplayName("subtracts the line value from the total and deletes the row")
        void deletesLine() {
            CartItem existing = line(7L, "MSI Katana 15", 900.0, 2);
            Cart cart = existingCart(existing);
            when(cartRepository.findById(1L)).thenReturn(Optional.of(cart));
            when(cartItemRepository.findCartItemByProductIdAndCartId(1L, 7L)).thenReturn(existing);

            String message = cartService.deleteProductFromCart(1L, 7L);

            assertThat(message).contains("MSI Katana 15");
            assertThat(cart.getTotalPrice()).isZero();
            verify(cartItemRepository).deleteCartItemByProductIdAndCartId(1L, 7L);
        }

        @Test
        @DisplayName("fails when the cart does not exist")
        void failsForUnknownCart() {
            when(cartRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cartService.deleteProductFromCart(99L, 7L))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Cart not found with cartId: 99");
        }

        @Test
        @DisplayName("fails when the product is not a line of that cart")
        void failsForUnknownLine() {
            when(cartRepository.findById(1L)).thenReturn(Optional.of(existingCart()));
            when(cartItemRepository.findCartItemByProductIdAndCartId(1L, 7L)).thenReturn(null);

            assertThatThrownBy(() -> cartService.deleteProductFromCart(1L, 7L))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Product not found with productId: 7");
        }
    }

    @Nested
    @DisplayName("createOrUpdateCartWithItems - the bulk sync the SPA performs after sign-in")
    class BulkSync {

        @Test
        @DisplayName("replaces the previous lines and totals the new ones")
        void replacesPreviousLines() {
            Cart cart = existingCart(line(9L, "Old Laptop", 500.0, 1));
            when(cartRepository.findCartByEmail(BUYER)).thenReturn(cart);
            when(productServiceClient.getProductById(7L)).thenReturn(catalogueProduct(7L, "MSI Katana 15", 900.0, 10));
            when(productServiceClient.getProductById(8L)).thenReturn(catalogueProduct(8L, "Dell XPS 13", 1500.0, 4));

            String message = cartService.createOrUpdateCartWithItems(List.of(
                    new CartItemDTO(7L, 2),
                    new CartItemDTO(8L, 1)));

            assertThat(message).isEqualTo("Cart created/updated with the new items successfully");
            verify(cartItemRepository).deleteAllByCartId(1L);
            assertThat(cart.getTotalPrice()).isEqualTo(2 * 900.0 + 1500.0);
        }

        @Test
        @DisplayName("creates a cart for a buyer who has never had one")
        void createsCartWhenAbsent() {
            when(cartRepository.findCartByEmail(BUYER)).thenReturn(null);
            when(productServiceClient.getProductById(7L)).thenReturn(catalogueProduct(7L, "MSI Katana 15", 900.0, 10));

            cartService.createOrUpdateCartWithItems(List.of(new CartItemDTO(7L, 1)));

            ArgumentCaptor<Cart> captor = ArgumentCaptor.forClass(Cart.class);
            verify(cartRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
            assertThat(captor.getValue().getUserEmail()).isEqualTo(BUYER);
            assertThat(captor.getValue().getTotalPrice()).isEqualTo(900.0);
        }

        @Test
        @DisplayName("rejects the whole sync when one line exceeds the stock on hand")
        void rejectsSyncOnStockViolation() {
            when(cartRepository.findCartByEmail(BUYER)).thenReturn(existingCart());
            when(productServiceClient.getProductById(7L)).thenReturn(catalogueProduct(7L, "MSI Katana 15", 900.0, 1));

            assertThatThrownBy(() -> cartService.createOrUpdateCartWithItems(List.of(new CartItemDTO(7L, 5))))
                    .isInstanceOf(APIException.class);
        }
    }

    @Nested
    @DisplayName("cart reads")
    class CartReads {

        @Test
        @DisplayName("SEC-07 characterisation: getAllCarts returns every cart in the system")
        void getAllCartsReturnsEveryCart() {
            // The endpoint behind this method has no role check (SEC-07). The test records
            // that the service itself applies no filtering either, so any authorisation fix
            // has to happen at a layer that is visible from here.
            Cart mine = existingCart(line(7L, "MSI Katana 15", 900.0, 1));
            Cart someoneElse = existingCart(line(8L, "Dell XPS 13", 1500.0, 1));
            someoneElse.setUserEmail("other@techzone.test");
            when(cartRepository.findAll()).thenReturn(List.of(mine, someoneElse));

            List<CartDTO> carts = cartService.getAllCarts();

            assertThat(carts).hasSize(2);
        }

        @Test
        @DisplayName("getAllCarts raises an error when no cart exists at all")
        void getAllCartsFailsWhenEmpty() {
            when(cartRepository.findAll()).thenReturn(List.of());

            assertThatThrownBy(() -> cartService.getAllCarts())
                    .isInstanceOf(APIException.class)
                    .hasMessage("No cart exist");
        }

        @Test
        @DisplayName("getCart returns the signed-in buyer's cart with its lines")
        void getCartReturnsOwnCart() {
            Cart cart = existingCart(line(7L, "MSI Katana 15", 900.0, 2));
            when(cartRepository.findCartByEmail(BUYER)).thenReturn(cart);
            when(cartRepository.findCartByEmailAndCartId(BUYER, 1L)).thenReturn(cart);

            CartDTO result = cartService.getCart();

            assertThat(result.getCartId()).isEqualTo(1L);
            assertThat(result.getProducts()).hasSize(1);
            assertThat(result.getTotalPrice()).isEqualTo(1800.0);
        }

        @Test
        @DisplayName("BUG-20 characterisation: getCart throws a NullPointerException when the buyer has no cart yet")
        void getCartThrowsWhenBuyerHasNoCart() {
            // findCartByEmail returns null for a customer who has never added anything, and
            // getCart calls getCartId() on it without a null check. The first time a newly
            // registered customer opens the cart page, the platform answers 500.
            //
            // Found by the system suite on its first run against a live stack; documented as
            // BUG-20 in docs/backend/known-defects.md. Once getCart returns an empty cart,
            // rewrite this to assert that instead.
            when(cartRepository.findCartByEmail(BUYER)).thenReturn(null);

            assertThatThrownBy(() -> cartService.getCart())
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("getCart fails when the cart row disappeared between the two reads")
        void getCartFailsWhenSecondReadMisses() {
            Cart cart = existingCart();
            when(cartRepository.findCartByEmail(BUYER)).thenReturn(cart);
            when(cartRepository.findCartByEmailAndCartId(BUYER, 1L)).thenReturn(null);

            assertThatThrownBy(() -> cartService.getCart())
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("updateProductInCarts - repricing a cart after a catalogue edit")
    class RepriceCart {

        @Test
        @DisplayName("reprices the line and rebuilds the total around it")
        void repricesLine() {
            CartItem existing = line(7L, "MSI Katana 15", 900.0, 2);
            Cart cart = existingCart(existing);
            when(cartRepository.findById(1L)).thenReturn(Optional.of(cart));
            when(cartItemRepository.findCartItemByProductIdAndCartId(1L, 7L)).thenReturn(existing);
            when(productServiceClient.getProductById(7L)).thenReturn(catalogueProduct(7L, "MSI Katana 15", 800.0, 10));

            cartService.updateProductInCarts(1L, 7L);

            assertThat(existing.getProductPrice()).isEqualTo(800.0);
            assertThat(cart.getTotalPrice()).isEqualTo(1600.0);
        }

        @Test
        @DisplayName("fails when the product is not in that cart")
        void failsWhenLineMissing() {
            when(cartRepository.findById(1L)).thenReturn(Optional.of(existingCart()));
            when(cartItemRepository.findCartItemByProductIdAndCartId(anyLong(), anyLong())).thenReturn(null);

            assertThatThrownBy(() -> cartService.updateProductInCarts(1L, 7L))
                    .isInstanceOf(APIException.class)
                    .hasMessage("Product not available in the cart");
        }
    }
}
