package com.ecommerce.order_service.client;

import com.ecommerce.order_service.clientpayload.ProductDTO;
import com.ecommerce.order_service.clientpayload.StockLine;

import java.util.List;

public interface ProductServiceClient {
    ProductDTO getProductById(Long productId);

    void reduceProductQuantities(List<StockLine> lines);

    void restoreProductQuantities(List<StockLine> lines);
}
