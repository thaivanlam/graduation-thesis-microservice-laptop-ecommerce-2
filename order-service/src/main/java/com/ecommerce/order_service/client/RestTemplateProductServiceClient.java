package com.ecommerce.order_service.client;

import com.ecommerce.order_service.exceptions.ResourceNotFoundException;
import com.ecommerce.order_service.clientpayload.ProductDTO;
import com.ecommerce.order_service.clientpayload.StockLine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Component
public class RestTemplateProductServiceClient implements ProductServiceClient{
    private final RestTemplate restTemplate;
    private final String baseUrl;

    public RestTemplateProductServiceClient(RestTemplate restTemplate,
                                            @Value("${product.service.base-url:http://localhost:8081/api}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public ProductDTO getProductById(Long productId) {
        ResponseEntity<ProductDTO> response = restTemplate.getForEntity(baseUrl + "/internal/products/" + productId, ProductDTO.class);
        ProductDTO body = response.getBody();
        if (body == null) {
            throw new ResourceNotFoundException("Product", "productId", productId);
        }
        return body;
    }

    @Override
    public void reduceProductQuantities(List<StockLine> lines) {
        restTemplate.exchange(baseUrl + "/internal/products/reduce-stock", HttpMethod.POST, new HttpEntity<>(new InventoryBatchRequest(lines)), Void.class);
    }

    @Override
    public void restoreProductQuantities(List<StockLine> lines) {
        restTemplate.exchange(baseUrl + "/internal/products/restore-stock", HttpMethod.POST, new HttpEntity<>(new InventoryBatchRequest(lines)), Void.class);
    }

    private static class InventoryBatchRequest {
        private final List<StockLine> items;

        private InventoryBatchRequest(List<StockLine> items) {
            this.items = items;
        }

        public List<StockLine> getItems() {
            return items;
        }
    }
}
