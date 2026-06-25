# Laptop E-Commerce Platform — Microservices Architecture

A full-stack laptop e-commerce platform built with **Spring Boot 3.5**, **Spring Cloud 2025**, and **React**, following a microservices architecture pattern. The system supports multi-role users (Customer, Seller, Admin), product catalog with technical specifications, shopping cart, Stripe-powered checkout, and asynchronous email notifications.

---

## Request Flow

```
Browser (:5173)
   │
   ▼
API Gateway (:8080)  ──── JWT cookie validation
   │                       Route rewriting (/product-manager/** → /**)
   │                       CORS + role-based access control
   │
   ├──► User Service (:8082)          Auth, addresses, user management
   ├──► Product Service (:8081)       Catalog, categories, specs, images
   └──► Order Service (:8083)         Cart, checkout, Stripe payments
           │
           ├──► Product Service       (REST: stock validation & reduction)
           ├──► Stripe API            (PaymentIntent creation)
           └──► RabbitMQ              (Async: order confirmation email)
                   │
                   ▼
               Notification Service (:8084)  ──► Gmail SMTP
```

### Step-by-step: what happens when a customer places an order

1. **React frontend** sends `POST /order-manager/api/order/users/payments/stripe` with a JWT cookie.
2. **API Gateway** intercepts the request. The `AuthenticationFilter` checks whether the path is public; since it's not, it extracts the JWT from the `springBootEcom` cookie, validates the signature against the shared HMAC secret, and extracts roles. The path doesn't match any `role-mappings` entry requiring ADMIN or SELLER, so the request passes through.
3. The gateway rewrites `/order-manager/**` → `/**` and forwards to `ORDER-SERVICE` resolved via **Eureka**.
4. **Order Service** receives the request. `AuthUtil` re-parses the JWT cookie to extract the user's email (used as the identity key — there is no session or SecurityContext in downstream services).
5. The service loads the user's cart from MySQL, then for each cart item calls **Product Service** at `http://product-service:8081/api/internal/products/{id}` to validate stock, and `POST .../reduce-stock` to decrement inventory.
6. A `Payment` entity is persisted alongside the `Order`. If Stripe was selected, the frontend already obtained a `clientSecret` via a prior call to `/order-manager/api/order/stripe-client-secret`, which created a Stripe `PaymentIntent`.
7. Order confirmation is published to RabbitMQ (`notification-exchange` / `notification-routing-key`).
8. **Notification Service** consumes the message and sends an email through Gmail SMTP.

---

## Services Overview

| Service | Port | Responsibility | Database |
|---|---|---|---|
| **API Gateway** | 8080 | Routing, JWT validation, CORS, role-based filtering | — |
| **Config Server** | 8888 | Centralized configuration (native profile, classpath) | — |
| **Discovery Service** | 8761 | Eureka service registry | — |
| **User Service** | 8082 | Registration, login (BCrypt), JWT generation, addresses, user CRUD | MySQL `ecommerce` |
| **Product Service** | 8081 | Categories, products, specifications, image upload, brand/filter queries | MySQL `ecommerce_product` |
| **Order Service** | 8083 | Cart (CRUD), order placement, Stripe payments, order status, analytics | MySQL `ecommerce_order` |
| **Notification Service** | 8084 | Consumes RabbitMQ messages, sends transactional emails via SMTP | — |

---

## Technology Choices & Rationale

### Why Spring Cloud Gateway (WebFlux)?

The gateway uses `spring-cloud-starter-gateway-server-webflux` — the reactive, non-blocking variant. For a gateway that mostly proxies requests, reactive I/O means a single thread can handle thousands of in-flight connections without blocking. The alternative (servlet-based gateway) would allocate one thread per request, which under load testing at 1000 concurrent users would exhaust the thread pool far earlier.

**Trade-off:** Reactive code (Mono/Flux) is harder to debug and reason about. The `AuthenticationFilter` returns `Mono<Void>` chains, which makes exception handling less intuitive than a servlet filter's synchronous `doFilter()`. For a team unfamiliar with Project Reactor, the learning curve is non-trivial.

### Why Eureka instead of Kubernetes service discovery?

Eureka is the simplest option when deploying with Docker Compose on a single host or small cluster. It requires no infrastructure beyond a Spring Boot JAR. Kubernetes service discovery (via DNS) would be more appropriate in production but introduces operational complexity (K8s cluster management, helm charts, ingress controllers) that isn't justified for a graduation project.

**Trade-off:** Eureka adds a network hop for service resolution, introduces a single point of failure (no Eureka peer replication is configured), and doesn't support advanced traffic management (canary, circuit breaking at the infrastructure level). For production, consider adding Spring Cloud LoadBalancer with retry or migrating to K8s.

### Why a shared JWT secret across all services?

All services (gateway, user-service, product-service, order-service) share the same HMAC-SHA secret key (`spring.app.jwtSecret`). This avoids the complexity of an OAuth2 authorization server or asymmetric key distribution. The user-service *creates* tokens; the gateway and downstream services *validate* them independently.

**Trade-off:** A symmetric secret means every service that can validate tokens can also forge them. If any service is compromised, the entire auth system is compromised. A more secure approach would use RS256 (asymmetric) where only the user-service holds the private key and other services verify with the public key. Additionally, token revocation isn't supported — a logged-out user's token remains valid until expiry.

### Why cookie-based JWT instead of Authorization header?

The JWT is stored in a cookie named `springBootEcom` with `httpOnly=false`. This allows the React frontend to read the token (for display purposes like user info) and automatically sends it with every request (no manual header management in the frontend).

**Trade-off:** `httpOnly=false` means JavaScript can read the cookie, which opens the door to XSS-based token theft. For a production system, `httpOnly=true` with `Secure=true` and `SameSite=Strict` would be more appropriate, but the frontend would need an alternative way to access user info (e.g., a `/me` endpoint).

### Why RabbitMQ for notifications?

Email sending is inherently slow (SMTP handshake, DNS lookup, potential retry). Synchronous email during checkout would add 1–3 seconds of latency. RabbitMQ decouples the order flow from email delivery — the order response returns immediately, and the notification service processes the message asynchronously with `concurrentConsumers=3`.

**Trade-off:** Adds an infrastructure dependency (RabbitMQ container). Messages can be lost if RabbitMQ crashes before consumption (durable queues mitigate this but don't eliminate it). For critical emails (e.g., password reset), a retry mechanism with dead-letter queue would be advisable.

### Why Config Server with native profile?

The config server uses `spring.profiles.active=native` with configurations stored in `classpath:/config`. This means configs are baked into the config-server's JAR at build time.

**Trade-off:** Changing a config requires rebuilding and redeploying the config-server container. A Git-backed config server would allow runtime config changes without redeploy, which is the typical production pattern. The native profile was chosen for simplicity — no Git repo to manage.

### Why ProductSnapshot (embedded) instead of foreign key to Product?

The `OrderItem` and `CartItem` entities embed a `ProductSnapshot` (product name, price, image, etc.) rather than holding a foreign key to the Product table in another database.

**Trade-off:** This is a deliberate denormalization required by the microservices boundary — Order Service and Product Service use different databases. A foreign key across databases isn't possible. The snapshot captures the product state *at the time of order*, which is semantically correct (the price at checkout shouldn't change if the seller later updates it). The cost is storage duplication and no cascading updates.

### Why a single MySQL instance with multiple databases?

Docker Compose runs one MySQL 8.0 container. Each service connects to a different logical database (`ecommerce`, `ecommerce_product`, `ecommerce_order`) within that instance via `?createDatabaseIfNotExist=true`.

**Trade-off:** This is acceptable for development and testing but violates the microservices principle of independent data stores. A shared MySQL instance means a schema migration in one service could lock tables and affect others. Connection pool exhaustion in one service could starve others. For production, each service should have its own database instance.

### Why RestTemplate instead of WebClient or Feign?

The order-service calls product-service via `RestTemplate` (synchronous HTTP). This is the simplest option and matches the servlet-based nature of the downstream services.

**Trade-off:** RestTemplate is in maintenance mode (Spring recommends WebClient for new projects). It blocks the calling thread during the HTTP call. For the internal `reduce-stock` call, this means the order placement thread is blocked until product-service responds. At high concurrency, this could exhaust the Tomcat thread pool. Alternatives: Spring Cloud OpenFeign (declarative, less boilerplate) or WebClient (non-blocking, but adds reactive complexity to a servlet application).

---

## Security Model

```
                     ┌─────────────────┐
  POST /signin ────► │  User Service   │ ──► BCrypt verify
                     │                 │ ──► Generate JWT (HS256)
                     │                 │ ──► Set-Cookie: springBootEcom=<token>
                     └─────────────────┘

  Subsequent requests:
  Cookie: springBootEcom=<jwt>
        │
        ▼
  ┌──────────────┐     Public path?  ──► Pass through
  │  API Gateway │     Valid JWT?    ──► Extract roles
  │  AuthFilter  │     Role match?   ──► Forward to service
  └──────────────┘     Otherwise     ──► 401/403 JSON error
        │
        ▼
  ┌──────────────┐
  │  Downstream  │     Re-parse JWT from cookie
  │   Service    │     Extract email as user identity
  └──────────────┘     No Spring Security context (except user-service)
```

**Role hierarchy:**
- `ROLE_USER` — browse, cart, place orders, manage addresses
- `ROLE_SELLER` — add/edit own products, view own orders
- `ROLE_ADMIN` — full access: manage all products, orders, users, analytics

Gateway enforces role checks via path pattern matching defined in `application.yaml`:
- `/product-manager/api/admin/**` → requires `ROLE_ADMIN`
- `/order-manager/api/seller/**` → requires `ROLE_ADMIN` or `ROLE_SELLER`
- `/user-manager/api/auth/**`, `/product-manager/api/public/**` → public (no auth)

---

## API Reference

All endpoints are reached through the **API Gateway** (`:8080`) using a service prefix that the gateway rewrites before forwarding:

- `/user-manager/**` → User Service (`:8082`) — prefix stripped to `/**`
- `/product-manager/**` → Product Service (`:8081`) — prefix stripped to `/**`
- `/order-manager/**` → Order Service (`:8083`) — prefix stripped to `/**`

The paths below show the **external gateway path**. To call a service directly (bypassing the gateway), drop the `/{service}-manager` prefix (e.g. `/user-manager/api/auth/signin` → `http://localhost:8082/api/auth/signin`).

**Access legend:** `Public` (no auth) · `USER` · `SELLER` · `ADMIN` · `Internal` (service-to-service).

> **Note on role enforcement:** The gateway only enforces roles on these patterns (see `role-mappings` in `api-gateway/.../application.yaml`): `/product-manager/api/admin/**`, `/user-manager/api/admin/**`, `/order-manager/api/admin/**` (all `ROLE_ADMIN`), and `/order-manager/api/seller/**` (`ROLE_ADMIN` or `ROLE_SELLER`). Endpoints whose access is marked by convention below (e.g. product `seller` paths, spec `admin`/`seller` paths under `/product-manager/api/products/...`) do not match a gateway role pattern and are therefore not role-checked at the gateway — they rely on the path naming convention. Any authenticated user can reach them through the gateway.

### User Service (`/user-manager`)

#### Authentication — `AuthController`

| Method | Path | Access | Description |
|---|---|---|---|
| POST | `/user-manager/api/auth/signin` | Public | Log in; returns user info and sets the `springBootEcom` JWT cookie |
| POST | `/user-manager/api/auth/signup` | Public | Register a new user |
| GET | `/user-manager/api/auth/username` | Public* | Current logged-in username (read from JWT cookie) |
| GET | `/user-manager/api/auth/user` | Public* | Current user details |
| POST | `/user-manager/api/auth/signout` | Public* | Log out; clears the JWT cookie |
| GET | `/user-manager/api/auth/sellers` | Public* | Paginated list of sellers (`pageNumber`) |
| GET | `/user-manager/api/auth/customers` | Public* | Paginated list of customers (`pageNumber`) |
| DELETE | `/user-manager/api/auth/customers/{userId}` | Public* | Delete a customer |
| DELETE | `/user-manager/api/auth/sellers/{userId}` | Public* | Delete a seller |

\* The gateway marks all `/user-manager/api/auth/**` as public, so these are not role-checked at the gateway despite being admin-style operations.

#### Addresses — `AddressController`

| Method | Path | Access | Description |
|---|---|---|---|
| POST | `/user-manager/api/addresses` | USER | Create an address |
| GET | `/user-manager/api/addresses` | USER | List all addresses |
| GET | `/user-manager/api/addresses/{addressId}` | USER | Get an address by ID |
| GET | `/user-manager/api/users/addresses` | USER | Addresses of the logged-in user |
| PUT | `/user-manager/api/addresses/{addressId}` | USER | Update an address |
| DELETE | `/user-manager/api/addresses/{addressId}` | USER | Delete an address |

### Product Service (`/product-manager`)

#### Products — `ProductController`

| Method | Path | Access | Description |
|---|---|---|---|
| GET | `/product-manager/api/public/products` | Public | List/search products with filters (`keyword`, `category`, `minPrice`, `maxPrice`, `brands`, `processors`, `ram`, `storage`, pagination + sorting) |
| GET | `/product-manager/api/public/products/brands` | Public | List all distinct brands |
| GET | `/product-manager/api/public/categories/{categoryId}/products` | Public | Products in a category (paginated) |
| GET | `/product-manager/api/public/products/keyword/{keyword}` | Public | Search products by keyword |
| POST | `/product-manager/api/admin/categories/{categoryId}/product` | ADMIN | Add a product to a category |
| POST | `/product-manager/api/seller/categories/{categoryId}/product` | SELLER | Add a product to a category (seller) |
| PUT | `/product-manager/api/admin/products/{productId}` | ADMIN | Update a product |
| PUT | `/product-manager/api/seller/products/{productId}` | SELLER | Update a product (seller) |
| DELETE | `/product-manager/api/admin/products/{productId}` | ADMIN | Delete a product |
| DELETE | `/product-manager/api/seller/products/{productId}` | SELLER | Delete a product (seller) |
| PUT | `/product-manager/api/admin/products/{productId}/image` | ADMIN | Upload/replace product image (`image` multipart) |
| PUT | `/product-manager/api/seller/products/{productId}/image` | SELLER | Upload/replace product image (seller) |
| GET | `/product-manager/api/admin/products` | ADMIN | List all products (admin view, paginated) |
| GET | `/product-manager/api/seller/products` | SELLER | List products (seller view, paginated) |
| GET | `/product-manager/api/admin/app/analytics` | ADMIN | Product analytics data |
| GET | `/product-manager/api/internal/products/{productId}` | Internal | Get product (used by Order Service for stock validation) |
| POST | `/product-manager/api/internal/products/{productId}/reduce-stock` | Internal | Decrement product stock (body: `quantity`) |

#### Categories — `CategoryController`

| Method | Path | Access | Description |
|---|---|---|---|
| GET | `/product-manager/api/public/categories` | Public | List categories (paginated) |
| POST | `/product-manager/api/admin/categories` | ADMIN | Create a category |
| PUT | `/product-manager/api/admin/categories/{categoryId}` | ADMIN | Update a category |
| DELETE | `/product-manager/api/admin/categories/{categoryId}` | ADMIN | Delete a category |

#### Specifications — `ProductSpecificationController`

| Method | Path | Access | Description |
|---|---|---|---|
| GET | `/product-manager/api/products/public/{productId}/specifications` | Public | Get a product's technical specifications |
| POST | `/product-manager/api/products/admin/{productId}/specifications` | ADMIN | Create/update specifications |
| POST | `/product-manager/api/products/seller/{productId}/specifications` | SELLER | Create/update specifications (seller) |
| DELETE | `/product-manager/api/products/admin/{productId}/specifications` | ADMIN | Delete specifications |
| DELETE | `/product-manager/api/products/seller/{productId}/specifications` | SELLER | Delete specifications (seller) |

### Order Service (`/order-manager`)

#### Cart — `CartController`

| Method | Path | Access | Description |
|---|---|---|---|
| POST | `/order-manager/api/cart/create` | USER | Create/update cart from a list of items |
| POST | `/order-manager/api/carts/products/{productId}/quantity/{quantity}` | USER | Add a product to the cart |
| GET | `/order-manager/api/carts` | USER | List all carts |
| GET | `/order-manager/api/carts/users/cart` | USER | Get the logged-in user's cart |
| PUT | `/order-manager/api/cart/products/{productId}/quantity/{operation}` | USER | Increment/decrement cart item (`operation` = anything vs `delete`) |
| DELETE | `/order-manager/api/carts/{cartId}/product/{productId}` | USER | Remove a product from the cart |

#### Orders & Payments — `OrderController`

| Method | Path | Access | Description |
|---|---|---|---|
| POST | `/order-manager/api/order/users/payments/{paymentMethod}` | USER | Place an order with the given payment method |
| POST | `/order-manager/api/order/stripe-client-secret` | USER | Create a Stripe PaymentIntent and return its `clientSecret` |
| GET | `/order-manager/api/order/users/orders` | USER | Logged-in user's orders (paginated) |
| PUT | `/order-manager/api/order/users/orders/{orderId}/status` | USER | Update order status (customer, e.g. cancel) |
| GET | `/order-manager/api/admin/orders` | ADMIN | All orders (paginated) |
| GET | `/order-manager/api/admin/app/analytics` | ADMIN | Order analytics data |
| PUT | `/order-manager/api/admin/orders/{orderId}/status` | ADMIN | Update order status |
| GET | `/order-manager/api/seller/orders` | SELLER | Seller's orders (paginated) |
| PUT | `/order-manager/api/seller/orders/{orderId}/status` | SELLER | Update order status (seller) |

### Notification Service (`:8084`, not exposed via gateway)

| Method | Path | Access | Description |
|---|---|---|---|
| POST | `/api/v1/notifications/sendMail` | Internal | Send a transactional email (`NotificationController`). Also a RabbitMQ `@RabbitListener` — normally triggered asynchronously by order confirmation messages, not called directly by the frontend. |

---

## Running the Platform

### Prerequisites

- Docker & Docker Compose
- (Optional) JDK 21, Maven 3.9+ for local development

### Quick Start

```bash
# 1. Create .env from example
cp .env.example .env
# Edit .env with your Stripe test key and Gmail app password

# 2. Start everything
docker-compose up --build

# 3. Access
# Frontend:       http://localhost:5173
# API Gateway:    http://localhost:8080
# Eureka:         http://localhost:8761
# RabbitMQ UI:    http://localhost:15672 (guest/guest)
```

### Default Users (seeded on startup)

| Username | Password | Role |
|---|---|---|
| `admin` | `adminPass` | ADMIN + SELLER + USER |
| `seller1` | `password2` | SELLER |
| `user1` | `password1` | USER |
| `user2` | `password1` | USER |

---

## Known Limitations & Future Improvements

**Current limitations:**
- No circuit breaker — if product-service is down, order placement fails with an unhandled exception rather than a graceful fallback
- No distributed tracing — debugging cross-service issues requires correlating logs manually across containers
- No rate limiting at the gateway level
- Config server uses native profile (configs baked at build time)
- Single Eureka instance (no peer replication)
- `httpOnly=false` on JWT cookie
- No token revocation mechanism
- Seller order queries load all orders into memory before filtering (`getAllSellerOrders` uses in-memory pagination)

**On the roadmap:**
- Resilience4j circuit breaker for inter-service calls
- Prometheus + Grafana for observability
- Centralized logging (ELK or Loki)
- Redis for session/cache layer
- Kubernetes deployment manifests
- Load testing with JMeter (Smoke → Load → Stress → Spike strategy)
