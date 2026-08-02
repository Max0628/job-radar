# PROJECT ARCHITECTURE PATTERNS
### (Java 21 / Spring Boot 3.x / Gradle Edition)

This document outlines the architecture patterns used in this project, primarily following a **Clean Architecture** approach while leveraging key **Spring Boot 3.x** features and annotations for development convenience. (Do Not Follow the DDD patterns)

> **Migration Notes (vs. previous Spring MVC / Java 8-11 version)**
> - `javax.*` → `jakarta.*` namespace (mandatory, Spring Boot 3 baseline is Jakarta EE 9+)
> - `RestTemplate` → `RestClient` (Spring Boot 3.2+) for new REST integrations
> - Added guidance on Virtual Threads (Java 21 / Project Loom)
> - DTO/Entity strategy stays on **Lombok** (`@Value` / `@Data`) — Java `record` intentionally *not* adopted yet, see §4 note
> - Project structure updated to Gradle multi-project convention (was Maven-flavored)
> - Minor: SQL constants may use text blocks for multi-line readability
> - Added **§6 Performance & Observability** — GC selection, connection pool sizing under Virtual Threads, and OpenTelemetry/Pyroscope integration defaults, since performance is this project's top priority

---

### ## 0. Fundamental (Java Code Layer)

- Must Support Backward Compatible design
- Must Follow the Immutable Functional Design (Avoid the Call by Reference)
- Avoid the common bad smell, e.g., Feature Envy, Shotgun Surgery, God Object
- Avoid the Magic Number, arrange the variable into static Constants or Enum
- Use Combination first, then use **Inheritance**
- Avoid the Null Point Exception with Optional
- Avoid to use the Static Class and Inner Class
- Use Lombok library functions by default (`@Data`, `@Value`, `@Builder`, `@RequiredArgsConstructor`)
- Target Java 21 language level (`sourceCompatibility = JavaVersion.VERSION_21` in Gradle). `var`, text blocks, and pattern matching for `switch` are permitted for readability, but are **style tools only** — they must not replace the Lombok/immutability conventions in this document.

---

### ## 1. Controller Pattern (Infrastructure Layer)

- **Responsibility**
    - The controller should be **thin**. It only deals with the **input/output transforming job**, delegating all business logic to a service (Use Case) class.
    - Responsible for **API request validation** and **authentication/authorization** checks.
- **Input/Output**
    - The response should be a `ResponseEntity<ApiResponse<T>>` object, where `ApiResponse` is a generic wrapper class for all API responses, ensuring consistent structure (e.g., status, data, message).
    - Use dedicated **Request DTOs** and **Response DTOs** for all API communication. **Never expose Domain Entities** directly via the controller.
    - API Pattern Must follow the **Google AIP Principles** with **Versioning** pattern, e.g., `/api/${version}/${category}/${resource}/${subType}:${action}`
- **Spring Annotations**
    - Use `@RestController` to define the controller.
    - Use `@RequiredArgsConstructor` to automatically generate a constructor for final fields (dependency injection), promoting **Immutability**.
    - Use `@PostMapping`, `@GetMapping`, `@PutMapping`, etc., to map HTTP methods and paths.
    - Use `@PathVariable` to extract variables from the URL path.
    - Use `@RequestBody` to bind the request body to a Java object.
    - Use `@Valid` or `@Validated` (from `jakarta.validation`) to enable validation on the request body object.
- **Namespace (Breaking Change)**
    - All validation and persistence annotations now come from `jakarta.*`, not `javax.*`:
        - `javax.validation.Valid` → `jakarta.validation.Valid`
        - `javax.validation.constraints.NotNull` → `jakarta.validation.constraints.NotNull`
    - IDE auto-import will silently pick the wrong (javax) package if an old dependency is still on the classpath — check `build.gradle.kts` for stray `javax.validation:validation-api` artifacts and remove them.

---

### ## 2. Service Pattern (Application / Use Case Layer)

*(unchanged from previous version — logic below is identical, annotations still resolve to `org.springframework.transaction.annotation.Transactional`, which is unaffected by the jakarta migration)*

- **Responsibility**
    - The Service layer (often called a **Use Case Interactor** in Clean Architecture) implements the **core business logic (Create, Update, Delete, Single Query, Collection Query functions)**.
    - Coordinates the flow of data to and from the domain layer (Aggregate/Entity) and external resources (Repository, API Client, MQ… etc.)
    - **Transaction boundary**: Service layer is the ONLY place to define transaction boundaries using `@Transactional`.
- **Dependencies**
    - Services should only depend on **interfaces** (e.g., Repository interfaces, API Gateway interfaces) as per the **Dependency Inversion Principle (DIP)**.
    - Use constructor injection with `@RequiredArgsConstructor` (Lombok) for immutability.
- **Spring Annotations**
    - Use `@Service` to annotate Service classes
    - Use `@Transactional(value = "tx0")` for write operations
    - Use `@Transactional(value = "tx0", readOnly = true)` for read operations
    - Set `rollbackFor = Exception.class`
    - Set appropriate `propagation` level (default: `Propagation.REQUIRED`)
- **Error Handling**
    - Throw specific, business-meaning exceptions (defined in the Domain layer) instead of generic runtime exceptions.
    - The Controller Advice will handle the mapping to HTTP status codes. Spring Boot 3 supports **RFC 7807 Problem Details** natively (`ProblemDetail` return type on `@ExceptionHandler`) — prefer this over hand-rolled error DTOs for new endpoints.
    - All exceptions in `rollbackFor` will trigger transaction rollback.
- **Transaction Management (Critical)**
    - **MUST** use `@Transactional` at the Service method level to manage database transactions.
    - **FORBIDDEN** to use `@Transactional` in DAO/Repository layer.
    - Write: `@Transactional(value = "tx0", rollbackFor = Exception.class)`
    - Read: `@Transactional(value = "tx0", readOnly = true, rollbackFor = Exception.class)`

- **Transaction Best Practices**

  ```java
  @Service
  @RequiredArgsConstructor
  @Slf4j
  public class GoodsService {

    private final GoodsRepository goodsRepository;

    @Transactional(value = "tx0", readOnly = true, propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public List<GoodsDTO> getGoodsByCodeList(List<String> goodsCodes) {
      log.info("Finding goods by codes: {}", goodsCodes);
      List<GoodsEntity> entities = goodsRepository.findByGoodsCodes(goodsCodes);
      return entities.stream()
          .map(this::convertToDTO)
          .collect(Collectors.toList());
    }

    @Transactional(value = "tx0", propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void updateGoods(GoodsDTO dto) {
      log.info("Updating goods: {}", dto.getGoodsCode());
      GoodsEntity entity = goodsRepository.findById(dto.getGoodsCode())
          .orElseThrow(() -> new GoodsNotFoundException(dto.getGoodsCode()));
      entity.setGoodsName(dto.getGoodsName());
      entity.setSalePrice(dto.getSalePrice());
      goodsRepository.update(entity);
    }

    private GoodsDTO convertToDTO(GoodsEntity entity) {
      return GoodsDTO.builder()
          .goodsCode(entity.getGoodsCode())
          .goodsName(entity.getGoodsName())
          .salePrice(entity.getSalePrice())
          .build();
    }
  }
  ```

---

### ## 3. Repository Pattern (Persistence Adapter)

- **Responsibility**
    - **Interface Definition (Domain Layer):** The Repository interface should be defined in the Domain Layer, consisting only of methods required by the Use Cases (Service).
    - **Implementation (Infrastructure Layer):** The concrete implementation (e.g., using Spring Data JPA) resides in the Infrastructure layer.
    - **Entity Mapping:** Responsible for converting between the **Domain Aggregate/Entity** (the pure business object) and the **Persistence Entity** (the JPA/DB-specific object) before saving or after loading. **NEVER** let the Persistence Entity leak into the Service layer.
    - DAO (Data Access Object) is responsible for encapsulating all database access logic, including complex SQL queries, dynamic query construction, and pagination.
    - Use **JdbcTemplate** to handle Native SQL queries, suitable for complex scenarios such as JOIN, UNION, dynamic conditions, etc.
    - **SHOULD NOT contain business logic**, only responsible for data access and transformation.
- **Spring Annotations**
    - Use `@Repository` (`org.springframework.stereotype.Repository`) to define the domain repository.
    - Extend `CrudRepository` by default.
    - Support `JpaSpecificationExecutor` for criteria query.
- **Input/Output**
    - **FORBIDDEN to return** `List<Map<String, Object>>`, must use **RowMapper** to convert to strongly-typed **DTO**.
    - All query results must be returned via **DTO** to ensure type safety and clear API contracts.
    - If multi-datasource failover is required, it should be encapsulated in **DualDataSourceExecutor**.
- **SQL Management**
    - Complex SQL should use **SqlBuilder** pattern, avoiding direct string concatenation in DAO methods.
    - SQL constants should be defined as `private static final String`. For multi-line SQL (JOIN/UNION/CTE), prefer Java 21 **text blocks** (`"""..."""`) over string concatenation for readability — this is a formatting choice only, it does not change the SqlBuilder pattern or parameterization requirements below.
    - Dynamic conditions should use `StringBuilder` with parameterized queries to avoid SQL Injection.
- **Spring Annotations**
    - Use `@Repository` to annotate DAO classes.
    - Use `@Autowired` + `@Qualifier` to inject specific `JdbcTemplate` (e.g., `oseJdbcTemplate`).
    - **FORBIDDEN**: Do NOT use `@Transactional` in DAO/Repository layer.
    - Use Lombok `@Setter(onMethod_ = {@Autowired, @Qualifier("...")})` for cleaner dependency injection.

- **RowMapper Pattern**

  ```java
  private static final String FIND_LIMIT_BUY_SQL = """
      SELECT LBCODE, GOODS_CODE, SPECIAL_PRICE
      FROM LIMIT_BUY
      WHERE STATUS = ?
      """;

  private static final RowMapper<LimitBuyDTO> LIMIT_BUY_MAPPER = (rs, rowNum) -> {
      LimitBuyDTO dto = new LimitBuyDTO();
      dto.setLbcode(rs.getString("LBCODE"));
      dto.setGoodsCode(rs.getString("GOODS_CODE"));
      dto.setSpecialPrice(rs.getBigDecimal("SPECIAL_PRICE"));
      return dto;
  };

  public List<LimitBuyDTO> findLimitBuyList(LimitBuyVO vo) {
      String sql = sqlBuilder.buildQuery(vo);
      Object[] args = sqlBuilder.buildArgs(vo);
      return jdbcTemplate.query(sql, LIMIT_BUY_MAPPER, args);
  }
  ```

- **Dual DataSource Failover (if applicable)**

  ```java
  @Component
  public class DualDataSourceJdbcExecutor {

      @Autowired
      @Qualifier("oseJdbcTemplate")
      private JdbcTemplate oseTemplate;

      @Autowired
      @Qualifier("exaJdbcTemplate")
      private JdbcTemplate exaTemplate;

      public <T> List<T> queryWithFallback(String sql, RowMapper<T> mapper, Object... args) {
          try {
              return oseTemplate.query(sql, mapper, args);
          } catch (Exception e) {
              log.error("OSE query failed, switching to EXA", e);
              return exaTemplate.query(sql, mapper, args);
          }
      }
  }
  ```

- **Naming Conventions**
    - DAO class naming: `${DomainName}Dao`, e.g., `LimitBuyDao`
    - Method naming: `find${Entity}List`, `update${Entity}`, `delete${Entity}`
    - RowMapper constant naming: `${ENTITY}_MAPPER`, e.g., `LIMIT_BUY_MAPPER`

---

### ## 4. Domain Model (DTO / Entity)

> **Decision: stay on Lombok, do not adopt `record` for now.**
> Java 21's `record` type is a viable alternative to Lombok `@Value` for immutable DTOs, but it forces changes to builder usage (no native fluent builder), Bean Validation annotation placement (must move to the constructor), and prevents inheritance (`record` is implicitly `final`). Given the team's current familiarity with Lombok's `@Builder`/`@Data` style, we are **keeping Lombok for both Entity and DTO** to avoid a disruptive convention change. Revisit this if a new module wants to pilot `record` in isolation.

- **Responsibility**
    - This section defines the core of the **Domain Layer**, following **Single Responsibility Principle (SRP)**.
- **Design Principles**
    - **Behavioral Focus (SRP):** Entities should contain logic (behavior/commands) related to changing their own state, not just data (getters/setters).
    - **No I/O Operations:** It **SHOULD NOT** perform any I/O operations (e.g., database access, network calls to AI services). This ensures the Domain remains pure and testable.
    - Entity object handles data CRUD with database (must be placed in the `entity` package of the app module).
    - DTO object handles data aggregation and is the cross-module/cross-API communication interface (must be placed in the `dto` package of the common module).
    - Entity naming pattern: `${DomainObjectNaming}` (without "Entity" suffix).
    - DTO naming pattern: `${DomainObjectNaming}DTO` (with "DTO" suffix).
    - Arrange all Status of the Bean Lifecycle into `EntityTypeEnum`, added to the `constant` package of the common module.
    - **(Option) Optimistic Locking:** contains a **`version` field** (initial value 1) for optimistic locking.
    - **(Option) State Change:** Every command that changes the state of the aggregate/entity should be done via a **method call on the aggregate/entity itself**. Once the state is changed, the **`version` field should be incremented by 1**.
- **Spring and Lombok's Annotations for Entity**
    - Use `@Entity` (`jakarta.persistence.Entity`) to define the entity.
    - Use `@Table(name = "xxxx")` (`jakarta.persistence.Table`) to define the database table mapping.
    - Use `@Id` and `@GeneratedValue(strategy = GenerationType.IDENTITY)` (`jakarta.persistence.*`) to define the sequential id for the entity.
    - Use Lombok's `@Data` annotation to replace the getter/setter.
    - Use Lombok's `@ToString` and `@EqualsAndHashCode` annotations on each entity.
- **Namespace (Breaking Change)**
    - `javax.persistence.Entity` → `jakarta.persistence.Entity`
    - `javax.persistence.Id` → `jakarta.persistence.Id`
    - `javax.persistence.GeneratedValue` → `jakarta.persistence.GeneratedValue`
    - `javax.persistence.Table` → `jakarta.persistence.Table`
- **Responsibility**
    - **Entity:** JPA entity, corresponding to database tables, used for ORM operations.
    - **DTO:** Data transfer object, used for cross-layer/cross-module communication.
        - **Request DTO:** API input parameter encapsulation.
        - **Response DTO:** API return result encapsulation.
        - **Query DTO:** DAO query result mapping (converted from `RowMapper`).
- **Design Principles**
    - **Single Responsibility (SRP):** DTO is only responsible for data carrying, does not contain business logic.
    - **Immutability:** Use `@Value` (Lombok) to create immutable DTOs.
    - **No I/O Operations:** DTO should not perform any I/O operations.
    - **Serialization Friendly:** DTO must support JSON serialization (for REST API).
- **Naming Conventions**
    - Entity: `${DomainName}` (without "Entity" suffix), e.g., `LimitBuy`
    - Request DTO: `${DomainName}Request`, e.g., `LimitBuyRequest`
    - Response DTO: `${DomainName}DTO`, e.g., `LimitBuyDTO`
    - VO (View Object): `${DomainName}VO`, e.g., `LimitBuyVO` (internal use, not exposed to API)
- **DTO Conversion Pattern**

  ```java
  // ✅ Convert from Map (legacy code compatibility)
  public static LimitBuyDTO fromMap(Map<String, Object> map) {
      LimitBuyDTO dto = new LimitBuyDTO();
      dto.setLbcode((String) map.get("LBCODE"));
      dto.setGoodsCode((String) map.get("GOODS_CODE"));
      return dto;
  }
  ```

- **Spring Annotations**
    - Entity: `@Entity`, `@Table`, `@Id`, `@GeneratedValue` (all `jakarta.persistence`)
    - DTO: `@Data` / `@Value` (Lombok), `@Schema` (springdoc-openapi), `@JsonProperty` (Jackson)
    - Request DTO: `@Valid`, `@NotNull`, `@Size` (all `jakarta.validation`)

---

### ## 5. External Integration Adapter Pattern (Infrastructure Layer)

This pattern handles integration with external systems and resources, supporting multiple scenarios: **REST API**, **Message Queue (Kafka/NATS)**, **Cache (Redis)**, and **File I/O**. All implementations adhere to the **Open/Closed Principle (OCP)** and **Dependency Inversion Principle (DIP)**.

---

#### 5.1 Core Principles

- **Gateway Interface (Application Layer)**
    - Define a technology-agnostic **interface** in the Application/Service layer (e.g., `PaymentGateway`, `NotificationGateway`, `FileStorageGateway`).
    - Service layer depends on the interface, NOT the concrete implementation.
- **Adapter Implementation (Infrastructure Layer)**
    - Concrete implementation class (e.g., `RestApiAdapter`, `KafkaProducerAdapter`, `RedisAdapter`, `S3FileAdapter`).
    - **Encapsulation (SRP):** Responsible for all low-level details: protocol-specific logic, connection management, serialization/deserialization, error handling.
    - **Data Mapping:** Maps Domain objects to external system format and vice versa.
- **Error Handling Hierarchy (Critical)**

  All adapters must handle exceptions in the following order:
    1. **Business Exception** - Domain-specific errors (e.g., `PaymentDeclinedException`, `InsufficientInventoryException`)
    2. **I/O Exception** - Network, connection, read/write errors
    3. **Timeout Exception** - Request timeout, circuit breaker timeout
    4. **Protocol Exception** - HTTP 4xx/5xx, Kafka serialization errors, Redis connection errors
    5. **Unexpected Exception** - Unknown errors, fallback to degraded service

  > **Optional enhancement:** this hierarchy maps naturally onto a Java 21 `sealed interface` (`ExternalIntegrationException` permits `BusinessException, IOException, TimeoutException, ProtocolException, SystemException`) combined with pattern-matching `switch` in the Controller Advice. This gives compiler-checked exhaustiveness (no forgotten case). Not mandatory — adopt per-team preference, existing `catch` chains below remain valid.

- **Resilience Patterns (Mandatory)**
    - **Circuit Breaker** - Prevent cascading failures (using Resilience4j)
    - **Retry Mechanism** - Retry transient failures with exponential backoff
    - **Timeout** - Set explicit timeout for all external calls
    - **Fallback** - Graceful degradation when external system fails
    - **Bulkhead** - Isolate thread pools for different external systems (see §5.8 for Virtual Threads interaction)

---

#### 5.2 REST API Integration Pattern

**Use Cases:** External payment API, third-party data providers, microservice communication

**Technology Stack:**
- Spring 6.1 / Boot 3.2+ **`RestClient`** (preferred, synchronous, fluent API) — replaces `RestTemplate`, which has been in maintenance mode since Spring 5 and should not be used in new code.
- `WebClient` remains the choice only if genuinely reactive/non-blocking composition is needed.
- Resilience4j for circuit breaker, retry, timeout
- Jackson for JSON serialization

**Error Handling Strategy:**
```
Business Exception (4xx with business error code)
  ↓
I/O Exception (Network errors, connection refused)
  ↓
Timeout Exception (Read timeout, connection timeout)
  ↓
HTTP Protocol Exception (5xx server errors)
  ↓
Fallback (Circuit open, degraded mode)
```

**Best Practice Example:**

```java
// Gateway Interface (Application Layer) — unchanged
public interface PaymentGateway {
    PaymentResult processPayment(PaymentRequest request);
}

// Adapter Implementation (Infrastructure Layer) — using RestClient
@Component
@Slf4j
public class ThirdPartyPaymentAdapter implements PaymentGateway {

    private final RestClient restClient;
    private final PaymentProperties properties;

    @Autowired
    public ThirdPartyPaymentAdapter(
            @Qualifier("paymentRestClient") RestClient restClient,
            PaymentProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    @CircuitBreaker(name = "paymentService", fallbackMethod = "paymentFallback")
    @Retry(name = "paymentService", fallbackMethod = "paymentFallback")
    @TimeLimiter(name = "paymentService")
    public PaymentResult processPayment(PaymentRequest request) {
        log.info("Processing payment for order: {}", request.getOrderNo());

        try {
            ThirdPartyPaymentApiRequest apiRequest = mapToApiRequest(request);

            ThirdPartyPaymentApiResponse response = restClient.post()
                .uri(properties.getPaymentUrl())
                .header("Authorization", "Bearer " + properties.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(apiRequest)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                    ThirdPartyPaymentApiResponse errorBody = readErrorBody(res);
                    throw new PaymentBusinessException(errorBody.getErrorCode(), errorBody.getErrorMessage());
                })
                .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                    throw new PaymentServerException("Payment service unavailable");
                })
                .body(ThirdPartyPaymentApiResponse.class);

            return mapToPaymentResult(response);

        } catch (PaymentBusinessException e) {
            log.error("Payment business error: {}", e.getMessage());
            throw e;

        } catch (ResourceAccessException e) {
            log.error("Payment I/O error: {}", e.getMessage(), e);
            throw new PaymentIOException("Network error during payment", e);

        } catch (Exception e) {
            log.error("Unexpected payment error", e);
            throw new PaymentSystemException("Payment system error", e);
        }
    }

    private PaymentResult paymentFallback(PaymentRequest request, Exception e) {
        log.error("Payment fallback triggered for order: {}, reason: {}",
            request.getOrderNo(), e.getMessage());

        return PaymentResult.builder()
            .orderNo(request.getOrderNo())
            .status(PaymentStatus.PENDING)
            .message("Payment queued for processing")
            .requiresManualReview(true)
            .build();
    }

    private ThirdPartyPaymentApiRequest mapToApiRequest(PaymentRequest request) {
        return ThirdPartyPaymentApiRequest.builder()
            .merchantId(properties.getMerchantId())
            .orderNo(request.getOrderNo())
            .amount(request.getAmount())
            .currency(request.getCurrency())
            .build();
    }

    private PaymentResult mapToPaymentResult(ThirdPartyPaymentApiResponse response) {
        return PaymentResult.builder()
            .transactionId(response.getTransactionId())
            .status(mapPaymentStatus(response.getStatus()))
            .message(response.getMessage())
            .build();
    }
}
```

**`RestClient` Bean Configuration:**

```java
@Configuration
public class RestClientConfig {

    @Bean
    @Qualifier("paymentRestClient")
    public RestClient paymentRestClient(PaymentProperties properties) {
        return RestClient.builder()
            .baseUrl(properties.getPaymentUrl())
            .requestFactory(clientHttpRequestFactory())
            .build();
    }

    private ClientHttpRequestFactory clientHttpRequestFactory() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }
}
```

**Configuration (application.yml) — unchanged from Resilience4j perspective:**

```yaml
resilience4j:
  circuitbreaker:
    instances:
      paymentService:
        registerHealthIndicator: true
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
        failureRateThreshold: 50
        waitDurationInOpenState: 30000
        permittedNumberOfCallsInHalfOpenState: 3
        automaticTransitionFromOpenToHalfOpenEnabled: true
  retry:
    instances:
      paymentService:
        maxAttempts: 3
        waitDuration: 1000
        exponentialBackoffMultiplier: 2
        retryExceptions:
          - org.springframework.web.client.ResourceAccessException
          - com.example.exception.PaymentServerException
        ignoreExceptions:
          - com.example.exception.PaymentBusinessException
  timelimiter:
    instances:
      paymentService:
        timeoutDuration: 10s
```

---

#### 5.3 Message Queue (Kafka/NATS) Integration Pattern

*(unchanged — Spring Kafka / Spring Cloud Stream APIs are unaffected by the Java 21 / Jakarta migration)*

**Use Cases:** Event publishing, asynchronous communication, event-driven architecture

**Error Handling Strategy:**
```
Business Exception (Invalid message format, validation errors)
  ↓
Serialization Exception (JSON parsing errors)
  ↓
Broker Connection Exception (Kafka broker unavailable)
  ↓
Timeout Exception (Producer/Consumer timeout)
  ↓
Fallback (Store to DB for retry, DLQ)
```

```java
public interface OrderEventPublisher {
    void publishOrderCreated(OrderCreatedEvent event);
}

@Component
@Slf4j
public class KafkaOrderEventPublisher implements OrderEventPublisher {

    private final KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate;
    private final OrderEventRepository eventRepository;

    @Value("${kafka.topic.order-created}")
    private String orderCreatedTopic;

    @Autowired
    public KafkaOrderEventPublisher(
            KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate,
            OrderEventRepository eventRepository) {
        this.kafkaTemplate = kafkaTemplate;
        this.eventRepository = eventRepository;
    }

    @Override
    @CircuitBreaker(name = "kafkaPublisher", fallbackMethod = "publishOrderCreatedFallback")
    @Retry(name = "kafkaPublisher")
    public void publishOrderCreated(OrderCreatedEvent event) {
        log.info("Publishing order created event: {}", event.getOrderNo());

        try {
            validateEvent(event);

            CompletableFuture<SendResult<String, OrderCreatedEvent>> future =
                kafkaTemplate.send(orderCreatedTopic, event.getOrderNo(), event);

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    log.info("Order event published successfully: {} to partition: {}",
                        event.getOrderNo(), result.getRecordMetadata().partition());
                } else {
                    handlePublishFailure(event, ex);
                }
            });

        } catch (IllegalArgumentException e) {
            log.error("Invalid order event: {}", e.getMessage());
            throw new OrderEventValidationException("Invalid event data", e);

        } catch (Exception e) {
            log.error("Failed to publish order event", e);
            throw new OrderEventPublishException("Event publish failed", e);
        }
    }

    private void publishOrderCreatedFallback(OrderCreatedEvent event, Exception e) {
        log.error("Kafka publish fallback triggered for order: {}, storing to DB",
            event.getOrderNo(), e);

        try {
            OrderEventOutbox outbox = OrderEventOutbox.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("ORDER_CREATED")
                .aggregateId(event.getOrderNo())
                .payload(toJson(event))
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .build();

            eventRepository.save(outbox);
            log.info("Order event stored to outbox: {}", event.getOrderNo());

        } catch (Exception dbException) {
            log.error("Failed to store event to outbox, data loss risk!", dbException);
            throw new CriticalEventLossException("Event may be lost", dbException);
        }
    }

    private void validateEvent(OrderCreatedEvent event) {
        if (event.getOrderNo() == null || event.getOrderNo().isEmpty()) {
            throw new IllegalArgumentException("Order number is required");
        }
        if (event.getCustomerId() == null) {
            throw new IllegalArgumentException("Customer ID is required");
        }
    }

    private void handlePublishFailure(OrderCreatedEvent event, Throwable ex) {
        log.error("Async publish failed for order: {}", event.getOrderNo(), ex);
        if (isRetriable(ex)) {
            publishOrderCreatedFallback(event, (Exception) ex);
        } else {
            log.error("Non-retriable publish error, manual intervention required");
        }
    }

    private boolean isRetriable(Throwable ex) {
        return ex instanceof TimeoutException
            || ex instanceof org.apache.kafka.common.errors.TimeoutException
            || ex instanceof org.apache.kafka.common.errors.NetworkException;
    }

    private String toJson(Object obj) {
        try {
            return new ObjectMapper().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new OrderEventSerializationException("JSON conversion failed", e);
        }
    }
}
```

> **Note:** `KafkaTemplate.send()` now returns `CompletableFuture` (since Spring Kafka 3.0), not `ListenableFuture` — the `ListenableFuture` API was removed in Spring Framework 6. Use `.whenComplete()` instead of `.addCallback()`.

```java
@Component
@Slf4j
public class OrderEventConsumer {

    private final OrderService orderService;

    @Autowired
    public OrderEventConsumer(OrderService orderService) {
        this.orderService = orderService;
    }

    @KafkaListener(
        topics = "${kafka.topic.order-created}",
        groupId = "${kafka.consumer.group-id}",
        containerFactory = "orderEventListenerContainerFactory"
    )
    @RetryableTopic(
        attempts = "3",
        backoff = @Backoff(delay = 1000, multiplier = 2.0),
        exclude = {OrderEventValidationException.class},
        dltTopicSuffix = "-dlt"
    )
    public void handleOrderCreatedEvent(
            @Payload OrderCreatedEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION_ID) int partition) {

        log.info("Received order created event: {} from partition: {}", event.getOrderNo(), partition);

        try {
            if (event.getOrderNo() == null) {
                throw new OrderEventValidationException("Order number is missing");
            }
            orderService.processOrderCreatedEvent(event);
            log.info("Order event processed successfully: {}", event.getOrderNo());

        } catch (OrderEventValidationException e) {
            log.error("Invalid order event, sending to DLT: {}", e.getMessage());
            throw e;

        } catch (DataAccessException e) {
            log.error("Database error processing event, will retry", e);
            throw new OrderEventProcessingException("DB error", e);

        } catch (Exception e) {
            log.error("Unexpected error processing event", e);
            throw new OrderEventProcessingException("Processing failed", e);
        }
    }

    @DltHandler
    public void handleDlt(
            @Payload OrderCreatedEvent event,
            @Header(KafkaHeaders.EXCEPTION_MESSAGE) String exceptionMessage) {

        log.error("Message sent to DLT: order={}, error={}",
            event.getOrderNo(), exceptionMessage);
    }
}
```

---

#### 5.4 Cache (Redis) Integration Pattern

*(unchanged — Spring Data Redis API surface is unaffected)*

**Error Handling Strategy:**
```
Business Exception (Invalid cache key format)
  ↓
Serialization Exception (Object serialization errors)
  ↓
Connection Exception (Redis unavailable)
  ↓
Timeout Exception (Command timeout)
  ↓
Fallback (Cache-aside, return null, query DB)
```

```java
public interface CacheGateway {
    <T> Optional<T> get(String key, Class<T> type);
    <T> void put(String key, T value, Duration ttl);
    void delete(String key);
}

@Component
@Slf4j
public class RedisCacheAdapter implements CacheGateway {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public RedisCacheAdapter(
            RedisTemplate<String, Object> redisTemplate,
            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @CircuitBreaker(name = "redisCache", fallbackMethod = "getFallback")
    @TimeLimiter(name = "redisCache")
    public <T> Optional<T> get(String key, Class<T> type) {
        log.debug("Getting cache value for key: {}", key);

        try {
            validateKey(key);
            Object value = redisTemplate.opsForValue().get(key);
            if (value == null) {
                return Optional.empty();
            }
            T result = objectMapper.convertValue(value, type);
            return Optional.of(result);

        } catch (IllegalArgumentException e) {
            throw new CacheKeyValidationException("Invalid key format", e);

        } catch (SerializationException e) {
            log.error("Cache deserialization error for key: {}", key, e);
            return Optional.empty();

        } catch (RedisConnectionFailureException e) {
            throw new CacheConnectionException("Redis unavailable", e);

        } catch (QueryTimeoutException e) {
            throw new CacheTimeoutException("Cache timeout", e);

        } catch (Exception e) {
            log.error("Unexpected cache error for key: {}", key, e);
            return Optional.empty();
        }
    }

    @Override
    @CircuitBreaker(name = "redisCache", fallbackMethod = "putFallback")
    public <T> void put(String key, T value, Duration ttl) {
        try {
            validateKey(key);
            if (value == null) {
                throw new IllegalArgumentException("Cache value cannot be null");
            }
            redisTemplate.opsForValue().set(key, value, ttl);

        } catch (IllegalArgumentException e) {
            throw new CacheKeyValidationException("Invalid cache put", e);

        } catch (SerializationException e) {
            throw new CacheSerializationException("Serialization failed", e);

        } catch (RedisConnectionFailureException e) {
            log.warn("Redis unavailable, cache put skipped for key: {}", key);

        } catch (Exception e) {
            log.error("Unexpected error putting cache for key: {}", key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            validateKey(key);
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.error("Error deleting cache for key: {}", key, e);
        }
    }

    private <T> Optional<T> getFallback(String key, Class<T> type, Exception e) {
        log.warn("Redis cache fallback triggered for key: {}, error: {}", key, e.getMessage());
        return Optional.empty();
    }

    private <T> void putFallback(String key, T value, Duration ttl, Exception e) {
        log.warn("Redis cache put fallback, skipping cache for key: {}", key);
    }

    private void validateKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Cache key cannot be null or empty");
        }
        if (key.length() > 512) {
            throw new IllegalArgumentException("Cache key too long");
        }
    }
}
```

**Service Usage with Cache-Aside Pattern:**

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class GoodsService {

    private final GoodsDao goodsDao;
    private final CacheGateway cacheGateway;

    private static final String CACHE_KEY_PREFIX = "goods:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    @Transactional(value = "tx0", readOnly = true, rollbackFor = Exception.class)
    public GoodsDTO getGoodsByCode(String goodsCode) {
        String cacheKey = CACHE_KEY_PREFIX + goodsCode;

        try {
            Optional<GoodsDTO> cached = cacheGateway.get(cacheKey, GoodsDTO.class);
            if (cached.isPresent()) {
                return cached.get();
            }

            GoodsDTO goods = goodsDao.findByCode(goodsCode)
                .orElseThrow(() -> new GoodsNotFoundException(goodsCode));

            CompletableFuture.runAsync(() -> {
                try {
                    cacheGateway.put(cacheKey, goods, CACHE_TTL);
                } catch (Exception e) {
                    log.error("Failed to cache goods: {}", goodsCode, e);
                }
            });

            return goods;

        } catch (CacheConnectionException e) {
            log.warn("Cache unavailable, querying DB directly: {}", goodsCode);
            return goodsDao.findByCode(goodsCode)
                .orElseThrow(() -> new GoodsNotFoundException(goodsCode));
        }
    }

    @Transactional(value = "tx0", rollbackFor = Exception.class)
    public void updateGoods(GoodsDTO goods) {
        goodsDao.update(goods);

        String cacheKey = CACHE_KEY_PREFIX + goods.getGoodsCode();
        try {
            cacheGateway.delete(cacheKey);
        } catch (Exception e) {
            log.error("Failed to invalidate cache for goods: {}", goods.getGoodsCode(), e);
        }
    }
}
```

---

#### 5.5 File I/O Integration Pattern

*(unchanged — AWS SDK v1/v2 usage is unaffected by the Java 21 migration; confirm your SDK version separately if migrating)*

```java
public interface FileStorageGateway {
    String uploadFile(String fileName, InputStream inputStream, long fileSize);
    InputStream downloadFile(String fileId);
    void deleteFile(String fileId);
}

@Component
@Slf4j
public class S3FileStorageAdapter implements FileStorageGateway {

    private final AmazonS3 s3Client;
    private final FileStorageProperties properties;

    @Autowired
    public S3FileStorageAdapter(AmazonS3 s3Client, FileStorageProperties properties) {
        this.s3Client = s3Client;
        this.properties = properties;
    }

    @Override
    @CircuitBreaker(name = "s3Storage", fallbackMethod = "uploadFileFallback")
    @Retry(name = "s3Storage")
    @TimeLimiter(name = "s3Storage")
    public String uploadFile(String fileName, InputStream inputStream, long fileSize) {
        log.info("Uploading file: {}, size: {} bytes", fileName, fileSize);

        try {
            validateFile(fileName, fileSize);
            String fileId = generateFileId(fileName);
            String s3Key = buildS3Key(fileId);

            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(fileSize);
            metadata.setContentType(detectContentType(fileName));

            PutObjectRequest putRequest = new PutObjectRequest(
                properties.getBucketName(), s3Key, inputStream, metadata);

            s3Client.putObject(putRequest);
            return fileId;

        } catch (IllegalArgumentException e) {
            throw new FileValidationException("Invalid file", e);

        } catch (AmazonServiceException e) {
            if (e.getStatusCode() == 403) {
                throw new FileStoragePermissionException("Access denied", e);
            } else if (e.getStatusCode() >= 500) {
                throw new FileStorageServerException("S3 server error", e);
            }
            throw new FileStorageException("S3 error", e);

        } catch (AmazonClientException e) {
            throw new FileStorageConnectionException("S3 connection failed", e);

        } catch (IOException e) {
            throw new FileIOException("File read error", e);

        } catch (Exception e) {
            throw new FileStorageException("Upload failed", e);

        } finally {
            try {
                inputStream.close();
            } catch (IOException e) {
                log.warn("Failed to close input stream", e);
            }
        }
    }

    @Override
    @CircuitBreaker(name = "s3Storage", fallbackMethod = "downloadFileFallback")
    @TimeLimiter(name = "s3Storage")
    public InputStream downloadFile(String fileId) {
        try {
            validateFileId(fileId);
            String s3Key = buildS3Key(fileId);
            S3Object s3Object = s3Client.getObject(properties.getBucketName(), s3Key);
            return s3Object.getObjectContent();

        } catch (AmazonS3Exception e) {
            if (e.getStatusCode() == 404) {
                throw new FileNotFoundException("File not found: " + fileId);
            }
            throw new FileStorageException("Download failed", e);

        } catch (AmazonClientException e) {
            throw new FileStorageConnectionException("S3 unavailable", e);

        } catch (Exception e) {
            throw new FileStorageException("Download failed", e);
        }
    }

    @Override
    public void deleteFile(String fileId) {
        try {
            validateFileId(fileId);
            s3Client.deleteObject(properties.getBucketName(), buildS3Key(fileId));
        } catch (Exception e) {
            log.error("Error deleting file: {}", fileId, e);
        }
    }

    private String uploadFileFallback(String fileName, InputStream inputStream,
                                     long fileSize, Exception e) {
        log.error("S3 upload fallback triggered for file: {}, saving to local temp", fileName, e);
        try {
            String fileId = generateFileId(fileName);
            Path tempPath = Paths.get(properties.getTempDirectory(), fileId);
            Files.copy(inputStream, tempPath, StandardCopyOption.REPLACE_EXISTING);
            queueFileForRetry(fileId, tempPath.toString());
            return fileId;
        } catch (IOException ioException) {
            throw new CriticalFileStorageException("File storage failed completely", ioException);
        }
    }

    private InputStream downloadFileFallback(String fileId, Exception e) {
        try {
            Path tempPath = Paths.get(properties.getTempDirectory(), fileId);
            if (Files.exists(tempPath)) {
                return Files.newInputStream(tempPath);
            }
        } catch (IOException ioException) {
            log.error("Failed to read from temp storage", ioException);
        }
        throw new FileNotFoundException("File not available: " + fileId);
    }

    private void validateFile(String fileName, long fileSize) {
        if (fileName == null || fileName.isEmpty()) {
            throw new IllegalArgumentException("File name is required");
        }
        if (fileSize <= 0) {
            throw new IllegalArgumentException("File size must be positive");
        }
        if (fileSize > properties.getMaxFileSize()) {
            throw new IllegalArgumentException("File size exceeds limit: " + properties.getMaxFileSize());
        }
        if (!isAllowedFileType(fileName)) {
            throw new IllegalArgumentException("File type not allowed: " + fileName);
        }
    }

    private void validateFileId(String fileId) {
        if (fileId == null || fileId.isEmpty()) {
            throw new IllegalArgumentException("File ID is required");
        }
    }

    private String generateFileId(String fileName) {
        return UUID.randomUUID().toString() + "_" + fileName;
    }

    private String buildS3Key(String fileId) {
        return properties.getKeyPrefix() + "/" + fileId;
    }

    private String detectContentType(String fileName) {
        return URLConnection.guessContentTypeFromName(fileName);
    }

    private boolean isAllowedFileType(String fileName) {
        String extension = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
        return properties.getAllowedExtensions().contains(extension);
    }

    private void queueFileForRetry(String fileId, String localPath) {
        log.info("Queuing file for retry: {}", fileId);
    }
}
```

---

#### 5.6 Resilience Configuration

*(unchanged)*

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        registerHealthIndicator: true
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
        permittedNumberOfCallsInHalfOpenState: 3
        automaticTransitionFromOpenToHalfOpenEnabled: true
        waitDurationInOpenState: 30s
        failureRateThreshold: 50
        slowCallRateThreshold: 100
        slowCallDurationThreshold: 60s
    instances:
      paymentService:
        baseConfig: default
        failureRateThreshold: 60
      kafkaPublisher:
        baseConfig: default
        failureRateThreshold: 70
      redisCache:
        baseConfig: default
        failureRateThreshold: 80
        recordExceptions:
          - com.example.exception.CacheConnectionException
      s3Storage:
        baseConfig: default
        failureRateThreshold: 50

  retry:
    configs:
      default:
        maxAttempts: 3
        waitDuration: 1s
        exponentialBackoffMultiplier: 2
        enableExponentialBackoff: true
    instances:
      paymentService:
        baseConfig: default
        retryExceptions:
          - org.springframework.web.client.ResourceAccessException
          - com.example.exception.PaymentServerException
        ignoreExceptions:
          - com.example.exception.PaymentBusinessException
      kafkaPublisher:
        baseConfig: default
        maxAttempts: 5
      s3Storage:
        baseConfig: default
        maxAttempts: 3

  timelimiter:
    configs:
      default:
        timeoutDuration: 10s
    instances:
      paymentService:
        timeoutDuration: 15s
      kafkaPublisher:
        timeoutDuration: 5s
      redisCache:
        timeoutDuration: 2s
      s3Storage:
        timeoutDuration: 30s

  bulkhead:
    instances:
      paymentService:
        maxConcurrentCalls: 10
        maxWaitDuration: 5s
      kafkaPublisher:
        maxConcurrentCalls: 20
      s3Storage:
        maxConcurrentCalls: 5
```

---

#### 5.7 Monitoring and Alerting

*(unchanged)*

**Key Metrics to Monitor:**
1. **Circuit Breaker State**: Open/Closed/Half-Open
2. **Retry Count**: Number of retries per operation
3. **Timeout Rate**: Percentage of operations timing out
4. **Fallback Trigger Rate**: How often fallback is executed
5. **Error Rate by Type**: Business/IO/Timeout/Protocol exceptions
6. **Response Time**: P50, P95, P99 latencies

**Spring Boot Actuator Endpoints:**
```
GET /actuator/health
GET /actuator/metrics/resilience4j.circuitbreaker.calls
GET /actuator/metrics/resilience4j.retry.calls
```

**Exception Hierarchy Summary:**

```
ExternalIntegrationException (base)
├── BusinessException (4xx, validation errors) - DON'T RETRY
├── IOException (network, connection) - RETRY
├── TimeoutException (request timeout) - RETRY
├── ProtocolException (HTTP 5xx, Kafka errors) - RETRY
└── SystemException (unexpected errors) - FALLBACK
```

---

#### 5.8 Virtual Threads (Java 21 / Project Loom)

**When to consider:**
- Spring Boot 3.2+ supports enabling Virtual Threads globally via `spring.threads.virtual.enabled=true`, which switches Tomcat's request-handling thread pool (and `@Async`'s default executor) to virtual threads.
- This project's stack is **blocking by design** (`JdbcTemplate`, `RestClient`, synchronous Kafka calls) — this is exactly the profile Virtual Threads were built for: high thread-count, I/O-bound, blocking workloads, without needing to rewrite to reactive (`WebClient`/`Mono`/`Flux`).

**Where it helps:**
- Controller → Service → DAO request threads: enabling `spring.threads.virtual.enabled=true` lets the platform spin up a virtual thread per request instead of pulling from Tomcat's fixed platform-thread pool. Under high concurrency with slow downstream calls (e.g., an Oracle query or a flaky payment API), this avoids platform-thread pool exhaustion.
- `@Async` methods backed by `SimpleAsyncTaskExecutor` (Spring Boot 3.2 auto-configures this to use virtual threads when the flag is on).

**Where to be careful — Pinning:**
- A virtual thread gets **pinned** to its underlying platform (carrier) thread when it executes inside a `synchronized` block or method while blocking (e.g., blocking I/O inside a `synchronized` block). Pinning defeats the purpose of virtual threads (the carrier thread cannot be reused while pinned) and under sustained pinning can exhaust the carrier thread pool just like the old model.
- **Audit before enabling:** any `synchronized` usage around JDBC calls, Redis calls, or file I/O in this codebase is a pinning risk. Common sources here:
    - Legacy DAO code using `synchronized` methods for "thread safety" around a shared `JdbcTemplate` (usually unnecessary — `JdbcTemplate` is already thread-safe).
    - `synchronized` blocks in cache adapters guarding local state.
- **Fix:** replace `synchronized` with `java.util.concurrent.locks.ReentrantLock` where mutual exclusion is genuinely needed — `ReentrantLock` does not pin virtual threads.
- Run with `-Djdk.tracePinnedThreads=full` in a staging environment before rollout to surface pinning locations in the logs.

**Where it does NOT help / do not force it:**
- CPU-bound work (e.g., in-memory aggregation, large JSON transforms) sees no benefit from virtual threads — they help with *blocking I/O wait*, not CPU throughput.
- Do not blindly increase Resilience4j `bulkhead.maxConcurrentCalls` just because virtual threads are cheap — the bulkhead's job is to protect the *downstream* system (payment gateway, S3, Kafka broker) from being overwhelmed, not to protect this application's own threads. Bulkhead limits stay governed by downstream capacity, independent of the threading model.

**Rollout recommendation:**
- Enable `spring.threads.virtual.enabled=true` per-service after auditing for `synchronized` + blocking I/O combinations.
- Treat this as an infrastructure-level toggle, not an architecture pattern change — no code in §1–§5 needs to change to benefit from it, aside from the `synchronized` → `ReentrantLock` fix above where applicable.

---

### ## 6. Performance & Observability

> This section exists because performance is the top priority for this project. Every item below is a **default**, not an optional nice-to-have — apply unless there's a documented reason not to.

#### 6.1 JVM / GC Selection

- **Default: Generational ZGC** (`-XX:+UseZGC -XX:+ZGenerational`, GA since JDK 21). It has sub-millisecond pause times regardless of heap size, which matters for a request-serving service where p99 latency is the concern — much better fit than G1 for this workload profile.
- If running in Kubernetes with tight memory limits (check the pod's `resources.limits.memory`), always set `-XX:MaxRAMPercentage` (e.g. `75.0`) instead of a fixed `-Xmx` — this lets the JVM correctly detect the **cgroup limit**, not the node's total memory. This is directly relevant given the ongoing `goods` service K8s migration — get this wrong and the JVM will think it has the whole node's memory and get OOM-killed by the kubelet instead.
- Enable JFR by default in production (`-XX:StartFlightRecording=disk=true,maxsize=250MB,maxage=24h`) — near-zero overhead, and gives you continuous profiling data for free that pairs directly with **Pyroscope** (JFR events can be scraped/converted for continuous profiling — check the Pyroscope Java integration docs for the current recommended agent, since this may have changed).

#### 6.2 Virtual Threads Interacts With Connection Pool Sizing

- With platform threads, the classic HikariCP sizing formula is `connections = ((core_count * 2) + effective_spindle_count)` — pool size stays small because thread count is the bottleneck.
- With **Virtual Threads enabled (§5.8)**, the application can issue far more *concurrent* blocking JDBC calls than before, since thread count is no longer the limiting factor. This means **the database connection pool becomes the real bottleneck** — HikariCP pool size must be sized against what Oracle/PostgreSQL/MongoDB can actually sustain, not against CPU core count. Re-benchmark `maximumPoolSize` per datasource after enabling virtual threads; don't carry over the old platform-thread-era pool size assumption.
- Same logic applies to the Redis connection pool (Lettuce) and MongoDB driver's connection pool — check `maxPoolSize` on all four datasources (Oracle, PostgreSQL, MongoDB, Redis) together, since they now compete for the same downstream capacity concurrently rather than being throttled by a shared small thread pool.

#### 6.3 Method-Level APM (OpenTelemetry)

- Attach the **OpenTelemetry Java Agent** (`-javaagent:opentelemetry-javaagent.jar`) for zero-code-change method-level tracing into Tempo — this should be a Dockerfile/deployment-manifest concern, not something individual services hand-roll with manual `@Timed`/`@Traced` annotations. Keeps this document's Adapter pattern (§5) clean of observability boilerplate.
- For spans that need custom business context (e.g., `orderNo`, `goodsCode` as span attributes for filtering in Tempo), inject `io.opentelemetry.api.trace.Span.current().setAttribute(...)` at the **Service layer boundary only** — not in Controller or DAO — to keep a consistent one-attribute-per-business-operation convention across the team.
- Circuit breaker state changes (§5.6 Resilience4j) should emit to Micrometer (`management.metrics.enable.resilience4j=true`) so they show up in Grafana alongside the trace/profile data, not just in logs.

#### 6.4 Startup & Cold-Start (relevant for multi-pod horizontal scaling)

- Enable **Spring AOT processing** (`org.springframework.boot.gradle.plugin` supports this via the `bootBuildImage`/AOT tasks) if the team plans to scale pods horizontally on demand — reduces cold-start time meaningfully, which matters when HPA (Horizontal Pod Autoscaler) is spinning up new pods under load.
- Consider **AppCDS** (`-XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=app.jsa`, simplified in JDK 19+) as a lower-effort alternative to full AOT/native-image if native-image isn't on the roadmap — cuts JVM class-loading time on every pod start with minimal build pipeline change.
- Both items are **optional** unless cold-start-under-scaling turns out to be a measured problem — don't add build complexity preemptively; note them here so the option is documented when it's needed.

---

### ## 7. Test

- **Responsible**
    - Based on JUnit 5 and Spring Test Frameworks (`spring-boot-starter-test`), perform test cases to ensure the quality of all user scenarios.
    - The test mechanism should be idempotent.
- **Test Cases Pattern**
    - **Naming of Test Cases:** `test${MethodName}_${Scenario}`, e.g., `testCreateOrder_success`, `testCreateOrder_noSuchProductFail`
    - **Naming format:** Camel Case
    - **Unit Test**
        - Test the single unit with the Mockito framework.
        - Do not test the DAO / repository layer in unit tests.
        - Provide basic happy and failure cases.
    - **API Integration Test**
        - Test the API with Spring MVC Test (`@WebMvcTest` / `MockMvc`) or `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `RestClient`/`TestRestTemplate` for full-stack tests.
        - Test data should be idempotent.
        - For tests that touch Oracle/PostgreSQL/MongoDB/Redis directly, prefer **Testcontainers** over shared dev-environment databases to keep tests hermetic and parallelizable — evaluate for this project given the multi-datasource setup (`goods` service migration context).
    - Follow the Gherkin Format to arrange the test methods.

---

### ## 8. Project Structure (Gradle Multi-Project)

- **Root Project Structure**

  ```
  root/
    settings.gradle.kts
    build.gradle.kts              # shared plugin/version config, if using convention plugins
    gradle/
      libs.versions.toml          # version catalog (recommended for dependency alignment)
    module1-api/
      build.gradle.kts
    module1-service/
      build.gradle.kts
    module1-dao/
      build.gradle.kts
    module1-common/
      build.gradle.kts
    module2-api/
      build.gradle.kts
    module2-service/
      build.gradle.kts
    module2-dao/
      build.gradle.kts
    module2-common/
      build.gradle.kts
  ```

- **`settings.gradle.kts` (root)**

  ```kotlin
  rootProject.name = "project-root"

  include(
      "module1-api", "module1-service", "module1-dao", "module1-common",
      "module2-api", "module2-service", "module2-dao", "module2-common"
  )
  ```

- **Module Structure (per module, e.g. `module1-api`)**

  ```
  module1-api/
    build.gradle.kts
    src/
      main/
        java/
          com/ooo/xxx/
            config/
            controller/
            interceptor/
            listener/
            scheduler/
            Application.java
      test/
        java/
          com/ooo/xxx/         (same package as main)
  ```

  ```
  module1-service/
    src/
      main/java/com/ooo/xxx/service/
      test/java/com/ooo/xxx/   (same package as main)

  module1-dao/
    src/
      main/java/com/ooo/xxx/
        repository/
        dao/
        entity/
        mapper/
      test/java/com/ooo/xxx/   (same package as main)

  module1-common/
    src/
      main/java/com/ooo/xxx/
        constant/
        dto/
        util/
      test/java/com/ooo/xxx/   (same package as main)
  ```

- **Inter-module dependency (example `module1-service/build.gradle.kts`)**

  ```kotlin
  plugins {
      id("java-library")
      id("io.spring.dependency-management")
  }

  java {
      sourceCompatibility = JavaVersion.VERSION_21
      targetCompatibility = JavaVersion.VERSION_21
  }

  dependencies {
      implementation(project(":module1-common"))
      implementation(project(":module1-dao"))

      implementation("org.springframework.boot:spring-boot-starter")
      compileOnly("org.projectlombok:lombok")
      annotationProcessor("org.projectlombok:lombok")

      testImplementation("org.springframework.boot:spring-boot-starter-test")
  }
  ```

- **Version Catalog (recommended, `gradle/libs.versions.toml`)** — keeps Spring Boot / Resilience4j / Lombok versions aligned across all modules instead of hardcoding per `build.gradle.kts`:

  ```toml
  [versions]
  spring-boot = "3.3.0"
  resilience4j = "2.2.0"
  lombok = "1.18.32"

  [libraries]
  spring-boot-starter-web = { module = "org.springframework.boot:spring-boot-starter-web", version.ref = "spring-boot" }
  resilience4j-spring-boot3 = { module = "io.github.resilience4j:resilience4j-spring-boot3", version.ref = "resilience4j" }
  lombok = { module = "org.projectlombok:lombok", version.ref = "lombok" }
  ```