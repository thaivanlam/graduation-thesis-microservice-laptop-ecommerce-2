package com.ecommerce.product_service.integration;

import com.ecommerce.product_service.model.Category;
import com.ecommerce.product_service.model.Product;
import com.ecommerce.product_service.repositories.CategoryRepository;
import com.ecommerce.product_service.repositories.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the catalogue persistence layer.
 *
 * <p>Unlike the unit tests, nothing here is mocked below the repository interface: Spring
 * Data builds the queries, Hibernate maps the entities and an in-memory H2 database
 * executes the SQL. That is exactly the seam these tests are meant to cover - derived
 * query names, the hand-written JPQL for the brand facet, paging, and the entity
 * relationship between Category and Product.</p>
 */
@DataJpaTest
@DisplayName("Integration - catalogue persistence")
class ProductRepositoryIntegrationTest {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Category gaming;
    private Category ultrabooks;

    @BeforeEach
    void seedCatalogue() {
        gaming = categoryRepository.save(category("Gaming Laptops"));
        ultrabooks = categoryRepository.save(category("Ultrabooks"));

        productRepository.save(product("MSI Katana 15", "MSI", 1200.0, 10.0, gaming, "seller-a@techzone.test"));
        productRepository.save(product("Acer Predator Helios", "Acer", 2400.0, 0.0, gaming, "seller-a@techzone.test"));
        productRepository.save(product("Dell XPS 13", "Dell", 1800.0, 5.0, ultrabooks, "seller-b@techzone.test"));
        productRepository.save(product("LG Gram 17", null, 1600.0, 0.0, ultrabooks, "seller-b@techzone.test"));
        productRepository.flush();
    }

    private static Category category(String name) {
        Category category = new Category();
        category.setCategoryName(name);
        category.setProducts(new ArrayList<>());
        return category;
    }

    private static Product product(String name, String brand, double price, double discount,
                                   Category category, String sellerEmail) {
        Product product = new Product();
        product.setProductName(name);
        product.setDescription("Seeded by the repository integration test");
        product.setBrand(brand);
        product.setPrice(price);
        product.setDiscount(discount);
        product.setSpecialPrice(price - (discount * 0.01) * price);
        product.setQuantity(5);
        product.setImage("default.png");
        product.setCategory(category);
        product.setSellerEmail(sellerEmail);
        product.setSellerId(1L);
        product.setSku("SKU-" + name.hashCode());
        return product;
    }

    @Test
    @DisplayName("persists a product and reads it back with its category association intact")
    void persistsProductWithCategory() {
        Product stored = productRepository.findAll().stream()
                .filter(p -> p.getProductName().equals("MSI Katana 15"))
                .findFirst()
                .orElseThrow();

        assertThat(stored.getProductId()).isNotNull();
        assertThat(stored.getCategory().getCategoryName()).isEqualTo("Gaming Laptops");
        assertThat(stored.getSpecialPrice()).isEqualTo(1080.0);
    }

    @Test
    @DisplayName("findByCategory pages the products of one category only")
    void pagesByCategory() {
        Page<Product> page = productRepository.findByCategory(gaming, PageRequest.of(0, 10, Sort.by("productId")));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(Product::getProductName)
                .containsExactlyInAnyOrder("MSI Katana 15", "Acer Predator Helios");
    }

    @Test
    @DisplayName("findByCategory reports the second page as the last one")
    void reportsLastPage() {
        Page<Product> firstPage = productRepository.findByCategory(gaming, PageRequest.of(0, 1, Sort.by("productId")));
        Page<Product> secondPage = productRepository.findByCategory(gaming, PageRequest.of(1, 1, Sort.by("productId")));

        assertThat(firstPage.isLast()).isFalse();
        assertThat(secondPage.isLast()).isTrue();
        assertThat(secondPage.getTotalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("findByProductNameLikeIgnoreCase matches regardless of case, with caller-supplied wildcards")
    void searchesByKeywordIgnoringCase() {
        Page<Product> page = productRepository.findByProductNameLikeIgnoreCase("%KATANA%",
                PageRequest.of(0, 10, Sort.by("productId")));

        assertThat(page.getContent()).extracting(Product::getProductName).containsExactly("MSI Katana 15");
    }

    @Test
    @DisplayName("findByProductNameLikeIgnoreCase returns an empty page rather than failing when nothing matches")
    void returnsEmptyPageForUnknownKeyword() {
        Page<Product> page = productRepository.findByProductNameLikeIgnoreCase("%chromebook%",
                PageRequest.of(0, 10, Sort.by("productId")));

        // The repository is well behaved here; it is the service layer above it that turns
        // this empty page into a 400 (documented as BUG-04).
        assertThat(page).isEmpty();
    }

    @Test
    @DisplayName("findBySellerEmail isolates one seller from another")
    void isolatesSellers() {
        Page<Product> sellerA = productRepository.findBySellerEmail("seller-a@techzone.test",
                PageRequest.of(0, 10, Sort.by("productId")));

        assertThat(sellerA.getTotalElements()).isEqualTo(2);
        assertThat(sellerA.getContent()).allMatch(p -> p.getSellerEmail().equals("seller-a@techzone.test"));
    }

    @Test
    @DisplayName("findAllDistinctBrands returns sorted distinct brands and skips products with no brand")
    void listsDistinctBrands() {
        List<String> brands = productRepository.findAllDistinctBrands();

        assertThat(brands).containsExactly("Acer", "Dell", "MSI");
        assertThat(brands).doesNotContainNull();
    }

    @Test
    @DisplayName("a JpaSpecificationExecutor price filter runs as real SQL against the schema")
    void filtersByPriceSpecification() {
        Specification<Product> under1500 = (root, query, cb) ->
                cb.lessThanOrEqualTo(root.get("specialPrice"), 1500.0);

        Page<Product> page = productRepository.findAll(under1500, PageRequest.of(0, 10, Sort.by("productId")));

        assertThat(page.getContent()).extracting(Product::getProductName)
                .containsExactly("MSI Katana 15");
    }

    @Test
    @DisplayName("deleting a category cascades to its products (BUG-13 characterisation)")
    void deletingCategoryCascadesToProducts() {
        // Category maps products with CascadeType.ALL, so removing a category removes the
        // catalogue entries under it. Documented as BUG-13 in docs/backend/known-defects.md;
        // this test exists so the blast radius is visible and any change to the cascade
        // configuration is caught immediately.
        long before = productRepository.count();

        // Drop the persistence context first so the category is re-read with its product
        // collection populated - otherwise the cascade would have nothing to act on.
        entityManager.flush();
        entityManager.clear();

        categoryRepository.delete(categoryRepository.findById(gaming.getCategoryId()).orElseThrow());
        productRepository.flush();

        assertThat(productRepository.count()).isEqualTo(before - 2);
    }
}
