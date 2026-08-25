package com.ecommerce.product_service.controller;

import com.ecommerce.product_service.payload.ProductSpecificationDTO;
import com.ecommerce.product_service.service.ProductSpecificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@Tag(name = "Product Specifications", description = "APIs for managing product technical specifications")
public class ProductSpecificationController {

    @Autowired
    private ProductSpecificationService specificationService;

    @Operation(summary = "Add or update product specifications (Admin)")
    @PostMapping("/admin/products/{productId}/specifications")
    public ResponseEntity<ProductSpecificationDTO> createOrUpdateSpecificationAdmin(
            @PathVariable Long productId,
            @Valid @RequestBody ProductSpecificationDTO specDTO) {
        ProductSpecificationDTO savedSpec = specificationService.createOrUpdateSpecification(productId, specDTO);
        return new ResponseEntity<>(savedSpec, HttpStatus.OK);
    }

    @Operation(summary = "Add or update product specifications (Seller)")
    @PostMapping("/seller/products/{productId}/specifications")
    public ResponseEntity<ProductSpecificationDTO> createOrUpdateSpecificationSeller(
            @PathVariable Long productId,
            @Valid @RequestBody ProductSpecificationDTO specDTO) {
        ProductSpecificationDTO savedSpec = specificationService.createOrUpdateSpecification(productId, specDTO);
        return new ResponseEntity<>(savedSpec, HttpStatus.OK);
    }

    @Operation(summary = "Get product specifications")
    @GetMapping("/public/products/{productId}/specifications")
    public ResponseEntity<ProductSpecificationDTO> getSpecification(@PathVariable Long productId) {
        ProductSpecificationDTO spec = specificationService.getSpecificationByProductId(productId);
        return ResponseEntity.ok(spec);
    }

    @Operation(summary = "Delete product specifications (Admin)")
    @DeleteMapping("/admin/products/{productId}/specifications")
    public ResponseEntity<Void> deleteSpecificationAdmin(@PathVariable Long productId) {
        specificationService.deleteSpecification(productId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Delete product specifications (Seller)")
    @DeleteMapping("/seller/products/{productId}/specifications")
    public ResponseEntity<Void> deleteSpecificationSeller(@PathVariable Long productId) {
        specificationService.deleteSpecification(productId);
        return ResponseEntity.noContent().build();
    }
}