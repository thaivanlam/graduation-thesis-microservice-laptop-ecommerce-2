package com.ecommerce.product_service.unit;

import com.ecommerce.product_service.exceptions.APIException;
import com.ecommerce.product_service.exceptions.ResourceNotFoundException;
import com.ecommerce.product_service.model.Category;
import com.ecommerce.product_service.model.Product;
import com.ecommerce.product_service.payload.ProductDTO;
import com.ecommerce.product_service.repositories.CategoryRepository;
import com.ecommerce.product_service.repositories.ProductRepository;
import com.ecommerce.product_service.service.FileService;
import com.ecommerce.product_service.service.ProductServiceImpl;
import com.ecommerce.product_service.util.AuthUtil;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProductServiceImpl}. Every collaborator (repositories, auth,
 * file storage) is a test double, so these tests isolate the catalogue business rules:
 * pricing, duplicate detection, SKU assignment and stock decrement.
 *
 * <p>A real {@link ModelMapper} is used rather than a mock, because the DTO/entity
 * mapping is part of the behaviour under test.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Unit - ProductServiceImpl")
class ProductServiceImplTest {

    private static final String IMAGE_BASE_URL = "http://localhost:8081/images";

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private AuthUtil authUtil;

    @Mock
    private FileService fileService;

    @Spy
    private ModelMapper modelMapper = new ModelMapper();

    @InjectMocks
    private ProductServiceImpl productService;

    @BeforeEach
    void injectConfigurationProperties() {
        ReflectionTestUtils.setField(productService, "path", "target/test-images/");
        ReflectionTestUtils.setField(productService, "imageBaseUrl", IMAGE_BASE_URL);
    }

    private static Category category(Long id, String name, Product... products) {
        Category category = new Category();
        category.setCategoryId(id);
        category.setCategoryName(name);
        category.setProducts(new ArrayList<>(List.of(products)));
        return category;
    }

    private static Product product(Long id, String name, double price, double discount, int quantity) {
        Product product = new Product();
        product.setProductId(id);
        product.setProductName(name);
        product.setDescription("A laptop used in tests");
        product.setPrice(price);
        product.setDiscount(discount);
        product.setSpecialPrice(price - (discount * 0.01) * price);
        product.setQuantity(quantity);
        product.setImage("default.png");
        product.setBrand("MSI");
        product.setSku("LAP-MSI-KATAN-000001");
        return product;
    }

    private static ProductDTO productDto(String name, double price, double discount, int quantity, String brand) {
        ProductDTO dto = new ProductDTO();
        dto.setProductName(name);
        dto.setDescription("A laptop used in tests");
        dto.setPrice(price);
        dto.setDiscount(discount);
        dto.setQuantity(quantity);
        dto.setBrand(brand);
        return dto;
    }

    @Nested
    @DisplayName("addProduct")
    class AddProduct {

        @Test
        @DisplayName("derives the special price from price and discount percentage")
        void derivesSpecialPrice() {
            Category laptops = category(1L, "Gaming Laptops");
            when(categoryRepository.findById(1L)).thenReturn(Optional.of(laptops));
            when(authUtil.loggedInEmail()).thenReturn("seller@techzone.test");
            when(authUtil.loggedInUserId()).thenReturn(9L);
            when(productRepository.save(any(Product.class))).thenAnswer(call -> call.getArgument(0));

            ProductDTO saved = productService.addProduct(1L, productDto("MSI Katana 15", 1000.0, 10.0, 5, "MSI"));

            assertThat(saved.getSpecialPrice()).isEqualTo(900.0);
        }

        @Test
        @DisplayName("a zero discount leaves the special price equal to the list price")
        void zeroDiscountKeepsListPrice() {
            when(categoryRepository.findById(1L)).thenReturn(Optional.of(category(1L, "Gaming Laptops")));
            when(authUtil.loggedInEmail()).thenReturn("seller@techzone.test");
            when(authUtil.loggedInUserId()).thenReturn(9L);
            when(productRepository.save(any(Product.class))).thenAnswer(call -> call.getArgument(0));

            ProductDTO saved = productService.addProduct(1L, productDto("MSI Katana 15", 1499.99, 0.0, 5, "MSI"));

            assertThat(saved.getSpecialPrice()).isEqualTo(1499.99);
        }

        @Test
        @DisplayName("stamps the logged-in seller onto the product")
        void stampsSeller() {
            when(categoryRepository.findById(1L)).thenReturn(Optional.of(category(1L, "Gaming Laptops")));
            when(authUtil.loggedInEmail()).thenReturn("seller@techzone.test");
            when(authUtil.loggedInUserId()).thenReturn(9L);
            when(productRepository.save(any(Product.class))).thenAnswer(call -> call.getArgument(0));

            productService.addProduct(1L, productDto("MSI Katana 15", 1000.0, 10.0, 5, "MSI"));

            ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
            verify(productRepository).save(captor.capture());
            assertThat(captor.getValue().getSellerEmail()).isEqualTo("seller@techzone.test");
            assertThat(captor.getValue().getSellerId()).isEqualTo(9L);
        }

        @Test
        @DisplayName("generates a SKU from the category, brand and product name")
        void generatesSku() {
            when(categoryRepository.findById(1L)).thenReturn(Optional.of(category(1L, "Gaming Laptops")));
            when(authUtil.loggedInEmail()).thenReturn("seller@techzone.test");
            when(authUtil.loggedInUserId()).thenReturn(9L);
            when(productRepository.save(any(Product.class))).thenAnswer(call -> call.getArgument(0));

            productService.addProduct(1L, productDto("Katana 15", 1000.0, 10.0, 5, "MSI"));

            ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
            verify(productRepository).save(captor.capture());
            assertThat(captor.getValue().getSku()).matches("^GAM-MSI-KATAN-\\d{6}$");
        }

        @Test
        @DisplayName("assigns the placeholder image and returns it as an absolute URL")
        void assignsPlaceholderImage() {
            when(categoryRepository.findById(1L)).thenReturn(Optional.of(category(1L, "Gaming Laptops")));
            when(authUtil.loggedInEmail()).thenReturn("seller@techzone.test");
            when(authUtil.loggedInUserId()).thenReturn(9L);
            when(productRepository.save(any(Product.class))).thenAnswer(call -> call.getArgument(0));

            ProductDTO saved = productService.addProduct(1L, productDto("Katana 15", 1000.0, 10.0, 5, "MSI"));

            assertThat(saved.getImage()).isEqualTo(IMAGE_BASE_URL + "/default.png");
        }

        @Test
        @DisplayName("rejects a product whose name already exists in the category, ignoring case")
        void rejectsDuplicateName() {
            Category laptops = category(1L, "Gaming Laptops", product(7L, "MSI Katana 15", 1000.0, 0.0, 3));
            when(categoryRepository.findById(1L)).thenReturn(Optional.of(laptops));

            assertThatThrownBy(() -> productService.addProduct(1L, productDto("msi katana 15", 1200.0, 0.0, 2, "MSI")))
                    .isInstanceOf(APIException.class)
                    .hasMessage("Product already exist!!");

            verify(productRepository, never()).save(any(Product.class));
        }

        @Test
        @DisplayName("fails when the category does not exist")
        void failsForUnknownCategory() {
            when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productService.addProduct(99L, productDto("Katana 15", 1000.0, 0.0, 2, "MSI")))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Category not found with categoryId: 99");
        }
    }

    @Nested
    @DisplayName("updateProduct")
    class UpdateProduct {

        @Test
        @DisplayName("recalculates the special price from the new price and discount")
        void recalculatesSpecialPrice() {
            Product existing = product(7L, "MSI Katana 15", 1000.0, 0.0, 3);
            existing.setCategory(category(1L, "Gaming Laptops"));
            when(productRepository.findById(7L)).thenReturn(Optional.of(existing));
            when(productRepository.save(any(Product.class))).thenAnswer(call -> call.getArgument(0));

            ProductDTO updated = productService.updateProduct(7L, productDto("MSI Katana 15", 2000.0, 25.0, 3, "MSI"));

            assertThat(updated.getSpecialPrice()).isEqualTo(1500.0);
        }

        @Test
        @DisplayName("regenerates the SKU when the product name changes")
        void regeneratesSkuOnRename() {
            Product existing = product(7L, "MSI Katana 15", 1000.0, 0.0, 3);
            existing.setCategory(category(1L, "Gaming Laptops"));
            String originalSku = existing.getSku();
            when(productRepository.findById(7L)).thenReturn(Optional.of(existing));
            when(productRepository.save(any(Product.class))).thenAnswer(call -> call.getArgument(0));

            ProductDTO updated = productService.updateProduct(7L, productDto("MSI Raider 18", 1000.0, 0.0, 3, "MSI"));

            assertThat(updated.getSku()).isNotEqualTo(originalSku);
            assertThat(updated.getSku()).matches("^GAM-MSI-MSI-\\d{6}$");
        }

        @Test
        @DisplayName("keeps the SKU when neither the name nor the brand changes")
        void keepsSkuWhenIdentityUnchanged() {
            Product existing = product(7L, "MSI Katana 15", 1000.0, 0.0, 3);
            existing.setCategory(category(1L, "Gaming Laptops"));
            String originalSku = existing.getSku();
            when(productRepository.findById(7L)).thenReturn(Optional.of(existing));
            when(productRepository.save(any(Product.class))).thenAnswer(call -> call.getArgument(0));

            ProductDTO updated = productService.updateProduct(7L, productDto("MSI Katana 15", 1100.0, 5.0, 4, "MSI"));

            assertThat(updated.getSku()).isEqualTo(originalSku);
        }

        @Test
        @DisplayName("fails when the product does not exist")
        void failsForUnknownProduct() {
            when(productRepository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productService.updateProduct(404L, productDto("X", 1.0, 0.0, 1, "MSI")))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Product not found with productId: 404");
        }
    }

    @Nested
    @DisplayName("reduceProductQuantity - the internal stock API used at checkout")
    class ReduceProductQuantity {

        @Test
        @DisplayName("decrements the stock on hand")
        void decrementsStock() {
            Product existing = product(7L, "MSI Katana 15", 1000.0, 0.0, 10);
            when(productRepository.findById(7L)).thenReturn(Optional.of(existing));

            productService.reduceProductQuantity(7L, 3);

            ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
            verify(productRepository).save(captor.capture());
            assertThat(captor.getValue().getQuantity()).isEqualTo(7);
        }

        @Test
        @DisplayName("allows the order that takes the last unit")
        void allowsExactStock() {
            Product existing = product(7L, "MSI Katana 15", 1000.0, 0.0, 2);
            when(productRepository.findById(7L)).thenReturn(Optional.of(existing));

            productService.reduceProductQuantity(7L, 2);

            assertThat(existing.getQuantity()).isZero();
        }

        @Test
        @DisplayName("refuses to go below zero")
        void refusesOversell() {
            Product existing = product(7L, "MSI Katana 15", 1000.0, 0.0, 2);
            when(productRepository.findById(7L)).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> productService.reduceProductQuantity(7L, 3))
                    .isInstanceOf(APIException.class)
                    .hasMessage("Insufficient product quantity");

            verify(productRepository, never()).save(any(Product.class));
        }

        @Test
        @DisplayName("refuses a negative quantity, which would silently add stock")
        void refusesNegativeQuantity() {
            Product existing = product(7L, "MSI Katana 15", 1000.0, 0.0, 2);
            when(productRepository.findById(7L)).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> productService.reduceProductQuantity(7L, -5))
                    .isInstanceOf(APIException.class)
                    .hasMessage("Quantity must be positive");
        }

        @Test
        @DisplayName("fails when the product does not exist")
        void failsForUnknownProduct() {
            when(productRepository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productService.reduceProductQuantity(404L, 1))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("catalogue reads")
    class CatalogueReads {

        @Test
        @DisplayName("maps the stored image name onto the public image URL")
        void mapsImageUrl() {
            Product existing = product(7L, "MSI Katana 15", 1000.0, 0.0, 10);
            existing.setImage("katana.png");
            when(productRepository.findById(7L)).thenReturn(Optional.of(existing));

            assertThat(productService.getProduct(7L).getImage()).isEqualTo(IMAGE_BASE_URL + "/katana.png");
        }

        @Test
        @DisplayName("returns a page of products when the filter matches")
        void returnsMatchingPage() {
            Page<Product> page = new PageImpl<>(List.of(product(7L, "MSI Katana 15", 1000.0, 0.0, 10)));
            when(productRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            var response = productService.getAllProducts(0, 10, "productId", "asc",
                    "katana", null, null, null, null, null, null, null);

            assertThat(response.getContent()).hasSize(1);
            assertThat(response.getTotalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("BUG-04 characterisation: an empty result is raised as an error, not an empty page")
        void emptyResultIsAnError() {
            // Documented defect BUG-04 in docs/backend/known-defects.md. The assertion below
            // pins the CURRENT behaviour so that fixing the defect makes this test fail and
            // forces the expectation to be rewritten as "returns an empty page".
            when(productRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(Page.empty());

            assertThatThrownBy(() -> productService.getAllProducts(0, 10, "productId", "asc",
                    "no-such-laptop", null, null, null, null, null, null, null))
                    .isInstanceOf(APIException.class)
                    .hasMessage("No Products Exist!!!");
        }
    }
}
