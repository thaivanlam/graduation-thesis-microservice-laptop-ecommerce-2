package com.ecommerce.product_service.service;


import com.ecommerce.product_service.exceptions.APIException;
import com.ecommerce.product_service.exceptions.ResourceNotFoundException;
import com.ecommerce.product_service.model.Category;
import com.ecommerce.product_service.model.Product;
import com.ecommerce.product_service.payload.ProductDTO;
import com.ecommerce.product_service.payload.ProductResponse;
import com.ecommerce.product_service.repositories.CategoryRepository;
import com.ecommerce.product_service.repositories.ProductRepository;
import com.ecommerce.product_service.util.AuthUtil;
import com.ecommerce.product_service.util.SKUGenerator;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Service
public class ProductServiceImpl implements ProductService {
    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ModelMapper modelMapper;

    @Autowired
    private AuthUtil authUtil;

    @Autowired
    private FileService fileService;

    @Value("${project.image}")
    private String path;

    @Value("${image.base.url}")
    private String imageBaseUrl;

    @Override
    public ProductDTO addProduct(Long categoryId, ProductDTO productDTO) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category", "categoryId", categoryId));

        boolean isProductNotPresent = category.getProducts().stream()
                .noneMatch(product -> product.getProductName().equalsIgnoreCase(productDTO.getProductName()));

        if (!isProductNotPresent) {
            throw new APIException("Product already exist!!");
        }

        Product product = modelMapper.map(productDTO, Product.class);
        product.setImage("default.png");
        product.setCategory(category);
        product.setSpecialPrice(calculateSpecialPrice(product.getPrice(), product.getDiscount()));
        product.setSellerEmail(authUtil.loggedInEmail());
        // sellerId is deliberately not set: a Keycloak access token carries no numeric user
        // id, and the local user row that has one lives in another service's database.
        // sellerEmail is the identity key everything here actually reads. See ADR-0012 and
        // the note on Product.sellerId.

        // Generate SKU automatically
        String sku = SKUGenerator.generateSKU(
                category.getCategoryName(),
                product.getBrand() != null ? product.getBrand() : "GENERIC",
                product.getProductName()
        );
        product.setSku(sku);

        Product savedProduct = productRepository.save(product);
        ProductDTO savedDto = modelMapper.map(savedProduct, ProductDTO.class);
        savedDto.setImage(constructImageUrl(savedProduct.getImage()));
        return savedDto;
    }


    private double calculateSpecialPrice(double price, double discount) {
        return price - (discount * 0.01) * price;
    }

    @Override
    public ProductResponse getAllProducts(Integer pageNumber, Integer pageSize, String sortBy, String sortOrder,
                                          String keyword, String category, Double minPrice, Double maxPrice,
                                          String brands, String processors, String ram, String storage) {
        Sort sortByAndOrder = sortOrder.equalsIgnoreCase("asc") ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();

        Pageable pageable = PageRequest.of(pageNumber, pageSize, sortByAndOrder);

        Specification<Product> spec = (root, query, criteriaBuilder) -> null;

        // Filter by keyword
        if (keyword != null && !keyword.isEmpty()) {
            spec = spec.and((root, query, criteriaBuilder) ->
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("productName")), "%" + keyword.toLowerCase() + "%"));
        }

        // Filter by category
        if (category != null && !category.isEmpty()) {
            spec = spec.and((root, query, criteriaBuilder) ->
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("category").get("categoryName")), category.toLowerCase()));
        }

        // Filter by min price
        if (minPrice != null && minPrice >= 0) {
            spec = spec.and((root, query, criteriaBuilder) ->
                    criteriaBuilder.greaterThanOrEqualTo(root.get("specialPrice"), minPrice)
            );
        }

        // Filter by max price
        if (maxPrice != null && maxPrice >= 0) {
            spec = spec.and((root, query, criteriaBuilder) ->
                    criteriaBuilder.lessThanOrEqualTo(root.get("specialPrice"), maxPrice)
            );
        }

        // Filter by brands
        if (brands != null && !brands.isEmpty()) {
            List<String> brandList = Arrays.asList(brands.split(","));
            spec = spec.and((root, query, criteriaBuilder) ->
                    root.get("brand").in(brandList)
            );
        }

        // Filter by processors (via ProductSpecification)
        if (processors != null && !processors.isEmpty()) {
            List<String> processorList = Arrays.asList(processors.split(","));
            spec = spec.and((root, query, criteriaBuilder) -> {
                var predicates = processorList.stream()
                        .map(processor -> criteriaBuilder.like(
                                criteriaBuilder.lower(root.join("specification").get("processor")),
                                "%" + processor.toLowerCase() + "%"
                        ))
                        .toArray(jakarta.persistence.criteria.Predicate[]::new);
                return criteriaBuilder.or(predicates);
            });
        }

        // Filter by RAM
        if (ram != null && !ram.isEmpty()) {
            List<String> ramList = Arrays.asList(ram.split(","));
            spec = spec.and((root, query, criteriaBuilder) -> {
                var predicates = ramList.stream()
                        .map(ramSize -> criteriaBuilder.like(
                                criteriaBuilder.lower(root.join("specification").get("ram")),
                                "%" + ramSize.toLowerCase() + "%"
                        ))
                        .toArray(jakarta.persistence.criteria.Predicate[]::new);
                return criteriaBuilder.or(predicates);
            });
        }

        // Filter by storage
        if (storage != null && !storage.isEmpty()) {
            List<String> storageList = Arrays.asList(storage.split(","));
            spec = spec.and((root, query, criteriaBuilder) -> {
                var predicates = storageList.stream()
                        .map(storageSize -> criteriaBuilder.like(
                                criteriaBuilder.lower(root.join("specification").get("storage")),
                                "%" + storageSize.toLowerCase() + "%"
                        ))
                        .toArray(jakarta.persistence.criteria.Predicate[]::new);
                return criteriaBuilder.or(predicates);
            });
        }

        Page<Product> productPage = productRepository.findAll(spec, pageable);

        List<Product> products = productPage.getContent();

        if (products.isEmpty()) {
            throw new APIException("No Products Exist!!!");
        }

        List<ProductDTO> productDTOS = products.stream().map(this::mapToDtoWithImage).toList();

        ProductResponse productResponse = new ProductResponse();
        productResponse.setContent(productDTOS);
        productResponse.setPageNumber(pageNumber);
        productResponse.setPageSize(pageSize);
        productResponse.setTotalElements(productPage.getTotalElements());
        productResponse.setTotalPages(productPage.getTotalPages());
        productResponse.setLastPage(productPage.isLast());
        return productResponse;
    }

    private ProductDTO mapToDtoWithImage(Product product) {
        ProductDTO productDTO = modelMapper.map(product, ProductDTO.class);
        productDTO.setImage(constructImageUrl(product.getImage()));
        return productDTO;
    }

    private String constructImageUrl(String imageName) {
        if (imageName == null || imageName.isBlank()) {
            return null;
        }
        return imageBaseUrl.endsWith("/") ? imageBaseUrl + imageName : imageBaseUrl + "/" + imageName;
    }

    @Override
    public ProductResponse searchByCategory(Long categoryId, Integer pageNumber, Integer pageSize, String sortBy, String sortOrder) {
        //check if product size is 0
        Category category = categoryRepository.findById(categoryId).orElseThrow(() -> new ResourceNotFoundException("Category", "categoryId", categoryId));

        Sort sortByAndOrder = sortOrder.equalsIgnoreCase("asc") ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();

        Pageable pageable = PageRequest.of(pageNumber, pageSize, sortByAndOrder);
        Page<Product> productPage = productRepository.findByCategory(category, pageable);

        List<Product> products = productPage.getContent();

        if (products.isEmpty()) {
            throw new APIException(category.getCategoryName() + " category does not have any products");
        }
        List<ProductDTO> productDTOS = products.stream().map(this::mapToDtoWithImage).toList();

        ProductResponse productResponse = new ProductResponse();
        productResponse.setContent(productDTOS);
        productResponse.setPageNumber(pageNumber);
        productResponse.setPageSize(pageSize);
        productResponse.setTotalElements(productPage.getTotalElements());
        productResponse.setTotalPages(productPage.getTotalPages());
        productResponse.setLastPage(productPage.isLast());
        return productResponse;
    }

    @Override
    public ProductResponse searchProductByKeyword(String keyword, Integer pageNumber, Integer pageSize, String sortBy, String sortOrder) {
        //check if product size is 0

        Sort sortByAndOrder = sortOrder.equalsIgnoreCase("asc") ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();

        Pageable pageable = PageRequest.of(pageNumber, pageSize, sortByAndOrder);

        Page<Product> productPage = productRepository.findByProductNameLikeIgnoreCase('%' + keyword + '%', pageable);


        List<Product> products = productPage.getContent();

        if (products.isEmpty()) {
            throw new APIException("Product not found with keyword: " + keyword);
        }

        List<ProductDTO> productDTOS = products.stream().map(this::mapToDtoWithImage).toList();
        ProductResponse productResponse = new ProductResponse();
        productResponse.setContent(productDTOS);
        productResponse.setPageNumber(pageNumber);
        productResponse.setPageSize(pageSize);
        productResponse.setTotalElements(productPage.getTotalElements());
        productResponse.setTotalPages(productPage.getTotalPages());
        productResponse.setLastPage(productPage.isLast());
        return productResponse;
    }

    @Override
    public ProductDTO updateProduct(Long productId, ProductDTO productDTO) {
        Product productFromDB = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", "productId", productId));

        // Store old values to check if SKU needs regeneration
        String oldProductName = productFromDB.getProductName();
        String oldBrand = productFromDB.getBrand();

        // Update basic fields
        productFromDB.setProductName(productDTO.getProductName());
        productFromDB.setDescription(productDTO.getDescription());
        productFromDB.setQuantity(productDTO.getQuantity());
        productFromDB.setDiscount(productDTO.getDiscount());
        productFromDB.setPrice(productDTO.getPrice());
        productFromDB.setSpecialPrice(calculateSpecialPrice(productDTO.getPrice(), productDTO.getDiscount()));

        // Update brand - QUAN TRỌNG: Phải update brand
        if (productDTO.getBrand() != null && !productDTO.getBrand().trim().isEmpty()) {
            productFromDB.setBrand(productDTO.getBrand());
        }

        // Regenerate SKU if product name or brand changed
        boolean shouldRegenerateSKU = false;

        // Check if product name changed
        if (!oldProductName.equals(productDTO.getProductName())) {
            shouldRegenerateSKU = true;
        }

        // Check if brand changed
        if (productDTO.getBrand() != null && !productDTO.getBrand().equals(oldBrand)) {
            shouldRegenerateSKU = true;
        }

        // Generate new SKU if needed
        if (shouldRegenerateSKU) {
            String newSKU = SKUGenerator.generateSKU(
                    productFromDB.getCategory().getCategoryName(),
                    productFromDB.getBrand() != null ? productFromDB.getBrand() : "GENERIC",
                    productFromDB.getProductName()
            );
            productFromDB.setSku(newSKU);
        }

        Product savedProduct = productRepository.save(productFromDB);
        return mapToDtoWithImage(savedProduct);
    }


    @Override
    public ProductDTO deleteProduct(Long productId) {
        Product product = productRepository.findById(productId).orElseThrow(() -> new ResourceNotFoundException("Product", "productId", productId));

        productRepository.delete(product);
        return mapToDtoWithImage(product);
    }

    @Override
    public ProductDTO updateProductImage(Long productId, MultipartFile image) throws IOException {
        Product productFromDB = productRepository.findById(productId).orElseThrow(() -> new ResourceNotFoundException("Product", "productId", productId));

        String fileName = fileService.uploadImage(path, image);
        productFromDB.setImage(fileName);

        Product updatedProduct = productRepository.save(productFromDB);
        return mapToDtoWithImage(updatedProduct);
    }

    @Override
    public ProductDTO getProduct(Long productId) {
        Product product = productRepository.findById(productId).orElseThrow(() -> new ResourceNotFoundException("Product", "productId", productId));
        return mapToDtoWithImage(product);
    }

    @Override
    public void reduceProductQuantity(Long productId, int quantity) {
        Product product = productRepository.findById(productId).orElseThrow(() -> new ResourceNotFoundException("Product", "productId", productId));
        if (quantity < 0) {
            throw new APIException("Quantity must be positive");
        }
        if (product.getQuantity() < quantity) {
            throw new APIException("Insufficient product quantity");
        }
        product.setQuantity(product.getQuantity() - quantity);
        productRepository.save(product);
    }

    @Override
    public ProductResponse getAllProductsForAdmin(Integer pageNumber, Integer pageSize, String sortBy, String sortOrder) {
        Sort sortByAndOrder = sortOrder.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        Pageable pageDetails = PageRequest.of(pageNumber, pageSize, sortByAndOrder);
        Page<Product> pageProducts = productRepository.findAll(pageDetails);

        List<Product> products = pageProducts.getContent();

        List<ProductDTO> productDTOS = products.stream().map(this::mapToDtoWithImage).toList();

        ProductResponse productResponse = new ProductResponse();
        productResponse.setContent(productDTOS);
        productResponse.setPageNumber(pageProducts.getNumber());
        productResponse.setPageSize(pageProducts.getSize());
        productResponse.setTotalElements(pageProducts.getTotalElements());
        productResponse.setTotalPages(pageProducts.getTotalPages());
        productResponse.setLastPage(pageProducts.isLast());
        return productResponse;
    }

    @Override
    public ProductResponse getAllProductsForSeller(Integer pageNumber, Integer pageSize, String sortBy, String sortOrder) {
        Sort sortByAndOrder = sortOrder.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        Pageable pageDetails = PageRequest.of(pageNumber, pageSize, sortByAndOrder);

        String sellerEmail = authUtil.loggedInEmail();
        Page<Product> pageProducts = productRepository.findBySellerEmail(sellerEmail, pageDetails);

        List<Product> products = pageProducts.getContent();

        List<ProductDTO> productDTOS = products.stream()
                .map(this::mapToDtoWithImage)
                .toList();

        ProductResponse productResponse = new ProductResponse();
        productResponse.setContent(productDTOS);
        productResponse.setPageNumber(pageProducts.getNumber());
        productResponse.setPageSize(pageProducts.getSize());
        productResponse.setTotalElements(pageProducts.getTotalElements());
        productResponse.setTotalPages(pageProducts.getTotalPages());
        productResponse.setLastPage(pageProducts.isLast());
        return productResponse;
    }

    @Override
    public List<String> getAllBrands() {
        return productRepository.findAllDistinctBrands();
    }
}