package com.ecommerce.product_service.integration;

import com.ecommerce.product_service.controller.ProductController;
import com.ecommerce.product_service.exceptions.APIException;
import com.ecommerce.product_service.exceptions.ResourceNotFoundException;
import com.ecommerce.product_service.payload.ProductDTO;
import com.ecommerce.product_service.payload.ProductResponse;
import com.ecommerce.product_service.service.AnalyticsService;
import com.ecommerce.product_service.service.ProductService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the product-service web layer.
 *
 * <p>The service layer is a test double, but everything above it is real: URL mapping,
 * query-parameter binding and defaulting, JSON serialisation, and the
 * {@code @RestControllerAdvice} that turns domain exceptions into HTTP responses. Those
 * are the pieces a unit test of the service can never cover.</p>
 */
// ADR-0012 made this service an OIDC resource server, so @WebMvcTest now loads the
// SecurityFilterChain too and every request below would answer 401 or 403. The filters are
// switched off here on purpose: what this class covers is the web contract - URL mapping,
// query-parameter binding and defaulting, JSON serialisation, and the @RestControllerAdvice
// that turns domain exceptions into HTTP responses. Who may call these paths is a separate
// question with its own test, {@code ProductServiceSecurityTest} , which asserts it against the real chain.
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(ProductController.class)
@DisplayName("Integration - ProductController HTTP contract")
class ProductControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private AnalyticsService analyticsService;

    private static ProductDTO sampleProduct() {
        ProductDTO dto = new ProductDTO();
        dto.setProductId(7L);
        dto.setProductName("MSI Katana 15");
        dto.setDescription("A gaming laptop");
        dto.setBrand("MSI");
        dto.setPrice(1200.0);
        dto.setDiscount(10.0);
        dto.setSpecialPrice(1080.0);
        dto.setQuantity(5);
        dto.setImage("http://localhost:8081/images/default.png");
        dto.setSku("GAM-MSI-KATAN-000001");
        return dto;
    }

    private static ProductResponse sampleResponse() {
        ProductResponse response = new ProductResponse();
        response.setContent(List.of(sampleProduct()));
        response.setPageNumber(0);
        response.setPageSize(6);
        response.setTotalElements(1L);
        response.setTotalPages(1);
        response.setLastPage(true);
        return response;
    }

    @Test
    @DisplayName("GET /api/public/products returns 200 with the serialised page")
    void listsProducts() throws Exception {
        when(productService.getAllProducts(anyInt(), anyInt(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(sampleResponse());

        mockMvc.perform(get("/api/public/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].productName").value("MSI Katana 15"))
                .andExpect(jsonPath("$.content[0].specialPrice").value(1080.0))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.lastPage").value(true));
    }

    @Test
    @DisplayName("GET /api/public/products applies the documented paging and sorting defaults")
    void appliesPagingDefaults() throws Exception {
        when(productService.getAllProducts(anyInt(), anyInt(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(sampleResponse());

        mockMvc.perform(get("/api/public/products")).andExpect(status().isOk());

        verify(productService).getAllProducts(eq(0), eq(6), eq("productId"), eq("asc"),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("GET /api/public/products passes the facet filters straight through")
    void forwardsFacetFilters() throws Exception {
        when(productService.getAllProducts(anyInt(), anyInt(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(sampleResponse());

        mockMvc.perform(get("/api/public/products")
                        .param("keyword", "katana")
                        .param("category", "Gaming Laptops")
                        .param("minPrice", "500")
                        .param("maxPrice", "2000")
                        .param("brands", "MSI,Acer")
                        .param("ram", "16GB")
                        .param("pageSize", "24")
                        .param("sortOrder", "desc"))
                .andExpect(status().isOk());

        verify(productService).getAllProducts(eq(0), eq(24), eq("productId"), eq("desc"),
                eq("katana"), eq("Gaming Laptops"), eq(500.0), eq(2000.0),
                eq("MSI,Acer"), any(), eq("16GB"), any());
    }

    @Test
    @DisplayName("BUG-12 characterisation: keyword search answers 302 FOUND for a successful read")
    void keywordSearchAnswers302() throws Exception {
        // Documented as BUG-12 in docs/backend/known-defects.md: HttpStatus.FOUND is a
        // redirect status, not a success status. Pinned here so a fix to 200 OK shows up
        // as a failing test rather than silently changing the API.
        when(productService.searchProductByKeyword(anyString(), anyInt(), anyInt(), anyString(), anyString()))
                .thenReturn(sampleResponse());

        mockMvc.perform(get("/api/public/products/keyword/katana"))
                .andExpect(status().isFound());
    }

    @Test
    @DisplayName("POST /api/admin/categories/{id}/product returns 201 with the created product")
    void createsProduct() throws Exception {
        when(productService.addProduct(eq(1L), any(ProductDTO.class))).thenReturn(sampleProduct());

        mockMvc.perform(post("/api/admin/categories/1/product")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleProduct())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("GAM-MSI-KATAN-000001"));
    }

    @Test
    @DisplayName("an APIException from the service becomes a 400 with the failure envelope")
    void mapsApiExceptionToBadRequest() throws Exception {
        when(productService.addProduct(eq(1L), any(ProductDTO.class)))
                .thenThrow(new APIException("Product already exist!!"));

        mockMvc.perform(post("/api/admin/categories/1/product")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleProduct())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Product already exist!!"))
                .andExpect(jsonPath("$.status").value(false));
    }

    @Test
    @DisplayName("a ResourceNotFoundException from the service becomes a 404 with the failure envelope")
    void mapsResourceNotFoundToNotFound() throws Exception {
        when(productService.getProduct(404L))
                .thenThrow(new ResourceNotFoundException("Product", "productId", 404L));

        mockMvc.perform(get("/api/internal/products/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Product not found with productId: 404"))
                .andExpect(jsonPath("$.status").value(false));
    }

    @Test
    @DisplayName("GET /api/internal/products/{id} serves the cross-service read used by order-service")
    void servesInternalProductRead() throws Exception {
        when(productService.getProduct(7L)).thenReturn(sampleProduct());

        mockMvc.perform(get("/api/internal/products/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(7))
                .andExpect(jsonPath("$.quantity").value(5));
    }

    @Test
    @DisplayName("POST /api/internal/products/{id}/reduce-stock answers 202 and forwards the quantity")
    void acceptsStockReduction() throws Exception {
        mockMvc.perform(post("/api/internal/products/7/reduce-stock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":3}"))
                .andExpect(status().isAccepted());

        verify(productService).reduceProductQuantity(7L, 3);
    }

    @Test
    @DisplayName("a stock reduction beyond the quantity on hand is refused with 400")
    void refusesOversellOverHttp() throws Exception {
        doThrow(new APIException("Insufficient product quantity"))
                .when(productService).reduceProductQuantity(anyLong(), anyInt());

        mockMvc.perform(post("/api/internal/products/7/reduce-stock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":9999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Insufficient product quantity"));
    }
}
