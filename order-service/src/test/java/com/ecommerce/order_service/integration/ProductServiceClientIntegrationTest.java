package com.ecommerce.order_service.integration;

import com.ecommerce.order_service.client.RestTemplateProductServiceClient;
import com.ecommerce.order_service.clientpayload.ProductDTO;
import com.ecommerce.order_service.clientpayload.StockLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Integration tests for the order-service side of the order/catalogue boundary.
 *
 * <p>These exercise the real {@link RestTemplate}, the real message converters and the real
 * URL assembly against a stubbed product-service. They are the tests that would catch a
 * renamed field, a changed path or a wrongly shaped request body - none of which a mocked
 * {@code ProductServiceClient} can detect.</p>
 */
@DisplayName("Integration - order-service to product-service HTTP contract")
class ProductServiceClientIntegrationTest {

    private static final String BASE_URL = "http://product-service-under-test/api";

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private RestTemplateProductServiceClient client;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        client = new RestTemplateProductServiceClient(restTemplate, BASE_URL);
    }

    private static String productJson() {
        return """
                {
                  "productId": 7,
                  "productName": "MSI Katana 15",
                  "image": "http://localhost:8081/images/katana.png",
                  "description": "A gaming laptop",
                  "quantity": 12,
                  "price": 1000.0,
                  "discount": 10.0,
                  "specialPrice": 900.0,
                  "sellerId": 3,
                  "sellerEmail": "seller@techzone.test"
                }
                """;
    }

    @Test
    @DisplayName("getProductById calls the internal read endpoint and deserialises every field the cart needs")
    void readsProduct() {
        server.expect(requestTo(BASE_URL + "/internal/products/7"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(productJson(), MediaType.APPLICATION_JSON));

        ProductDTO product = client.getProductById(7L);

        server.verify();
        assertThat(product.getProductId()).isEqualTo(7L);
        assertThat(product.getProductName()).isEqualTo("MSI Katana 15");
        assertThat(product.getSpecialPrice()).isEqualTo(900.0);
        assertThat(product.getQuantity()).isEqualTo(12);
        assertThat(product.getSellerEmail()).isEqualTo("seller@techzone.test");
    }

    @Test
    @DisplayName("a trailing slash on the configured base URL does not produce a doubled slash")
    void normalisesBaseUrl() {
        RestTemplate template = new RestTemplate();
        MockRestServiceServer strictServer = MockRestServiceServer.createServer(template);
        RestTemplateProductServiceClient trailingSlashClient =
                new RestTemplateProductServiceClient(template, BASE_URL + "/");

        strictServer.expect(requestTo(BASE_URL + "/internal/products/7"))
                .andRespond(withSuccess(productJson(), MediaType.APPLICATION_JSON));

        trailingSlashClient.getProductById(7L);

        strictServer.verify();
    }

    @Test
    @DisplayName("reduceProductQuantities posts every line of the order to the batch stock endpoint in one request")
    void reducesStock() {
        server.expect(requestTo(BASE_URL + "/internal/products/reduce-stock"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].productId").value(7))
                .andExpect(jsonPath("$.items[0].quantity").value(3))
                .andExpect(jsonPath("$.items[1].productId").value(8))
                .andExpect(jsonPath("$.items[1].quantity").value(1))
                .andRespond(withStatus(org.springframework.http.HttpStatus.ACCEPTED));

        client.reduceProductQuantities(List.of(new StockLine(7L, 3), new StockLine(8L, 1)));

        server.verify();
    }

    @Test
    @DisplayName("reduceProductQuantities sends a JSON body, not form data")
    void sendsJsonBody() {
        server.expect(requestTo(BASE_URL + "/internal/products/reduce-stock"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andRespond(withStatus(org.springframework.http.HttpStatus.ACCEPTED));

        client.reduceProductQuantities(List.of(new StockLine(7L, 1)));

        server.verify();
    }

    @Test
    @DisplayName("restoreProductQuantities posts the same lines to the compensating endpoint")
    void restoresStock() {
        server.expect(requestTo(BASE_URL + "/internal/products/restore-stock"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(7))
                .andExpect(jsonPath("$.items[0].quantity").value(3))
                .andRespond(withStatus(org.springframework.http.HttpStatus.ACCEPTED));

        client.restoreProductQuantities(List.of(new StockLine(7L, 3)));

        server.verify();
    }

    @Test
    @DisplayName("a reservation the catalogue refuses propagates, so checkout stops before writing an order")
    void refusedReservationPropagates() {
        server.expect(requestTo(BASE_URL + "/internal/products/reduce-stock"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> client.reduceProductQuantities(List.of(new StockLine(7L, 9999))))
                .isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    @Test
    @DisplayName("BUG-10 characterisation: a 404 from the catalogue surfaces as a raw RestTemplate exception")
    void notFoundSurfacesAsRestClientException() {
        // The client does not translate the remote status into a domain exception, so a
        // missing product reaches the cart service as HttpClientErrorException.NotFound and
        // leaves the caller with an untyped 500. Documented as BUG-10; when the client
        // starts mapping this to ResourceNotFoundException, this test must be rewritten.
        server.expect(requestTo(BASE_URL + "/internal/products/404"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.getProductById(404L))
                .isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    @Test
    @DisplayName("a catalogue outage propagates rather than being swallowed")
    void serverErrorPropagates() {
        server.expect(requestTo(BASE_URL + "/internal/products/7"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.getProductById(7L))
                .isInstanceOf(HttpServerErrorException.class);
    }
}
