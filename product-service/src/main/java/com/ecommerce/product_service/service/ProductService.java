package com.ecommerce.product_service.service;


import com.ecommerce.product_service.payload.InventoryLine;
import com.ecommerce.product_service.payload.ProductDTO;
import com.ecommerce.product_service.payload.ProductResponse;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

public interface ProductService {
    ProductDTO addProduct(Long categoryId, ProductDTO product);

    ProductResponse getAllProducts(Integer pageNumber, Integer pageSize, String sortBy, String sortOrder,
                                   String keyword, String category, Double minPrice, Double maxPrice,
                                   String brands, String processors, String ram, String storage);

    ProductResponse searchByCategory(Long categoryId, Integer pageNumber, Integer pageSize, String sortBy, String sortOrder);

    ProductResponse searchProductByKeyword(String keyword, Integer pageNumber, Integer pageSize, String sortBy, String sortOrder);

    ProductDTO updateProduct(Long productId, ProductDTO product);

    ProductDTO deleteProduct(Long productId);

    ProductDTO updateProductImage(Long productId, MultipartFile image) throws IOException;

    ProductDTO getProduct(Long productId);

    void reduceProductQuantity(Long productId, int quantity);

    void reduceProductQuantities(List<InventoryLine> lines);

    void restoreProductQuantities(List<InventoryLine> lines);

    ProductResponse getAllProductsForAdmin(Integer pageNumber, Integer pageSize, String sortBy, String sortOrder);

    ProductResponse getAllProductsForSeller(Integer pageNumber, Integer pageSize, String sortBy, String sortOrder);

    List<String> getAllBrands();
}