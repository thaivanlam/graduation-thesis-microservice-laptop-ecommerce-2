package com.ecommerce.product_service.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "product")
@ToString
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long productId;
    @NotBlank
    @Size(min = 3, message = "Product name must contain at least 3 characters")
    private String productName;
    private String image;
    @NotBlank
    @Size(min = 6, message = "Product description must contain at least 6 characters")
    private String description;
    private Integer quantity;
    private double price;
    private double discount;
    private double specialPrice;

    /**
     * Historic. Populated by the seed data and by products created before ADR-0012; null
     * for anything created since, because an OIDC access token has no numeric user id and
     * the local user row that does have one belongs to user-service's database.
     *
     * <p>Nothing reads it — {@code sellerEmail} below is the key every query and every
     * ownership comparison uses. It is kept so that seeded rows and the DTO contract are
     * unchanged, and it should be dropped by whichever ADR finally moves the identity key
     * off email and onto {@code sub}.</p>
     */
    private Long sellerId;

    private String sellerEmail;

    @ManyToOne
    @JoinColumn(name = "category_id")
    private Category category;

    @OneToOne(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    private ProductSpecification specification;

    private String sku;
    private String brand;
}
