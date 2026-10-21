I want to build a production-style EdTech backend project for learning and SDE-2/SDE-3 interview preparation.

Tech stack:

- Java 21
- Spring Boot 3.x
- Maven
- Spring MVC
- Java 21 Virtual Threads where required like for I/O-bound workloads
- PostgreSQL
- Redis
- Apache Kafka
- Debezium CDC later
- AWS S3
- AWS MediaConvert
- AWS CloudFront
- Docker / Docker Compose
- JUnit 5
- Mockito

Architecture:

This MUST be a MODULAR MONOLITH, not microservices.

The application should be a single deployable Spring Boot application with clear module boundaries.

Planned modules:

1. auth
2. user
3. course
4. content
5. enrollment
6. video
7. video-progress
8. cart
9. order
10. payment
11. bulk-import
12. notification

Longer-term features I want to build:

1. User authentication and authorization
2. Course management
3. Course enrollment
4. Pre-recorded lecture management
5. Video upload to S3
6. Asynchronous AWS MediaConvert transcoding
7. HLS output in 480p / 720p / 1080p
8. CloudFront-based video delivery
9. Video resume position / progress tracking
10. Redis-backed shopping cart
11. Concurrency-safe checkout
12. Idempotent order creation
13. Razorpay and Cashfree payment integrations
14. Webhook-based payment reconciliation
15. Large Excel imports using asynchronous background processing
16. Job status polling
17. Failed-row report generation
18. Debezium CDC + Kafka event pipeline
19. Independent consumers for analytics, sales and rewards workflows
20. Virtual-thread-based execution for appropriate blocking I/O workloads

Important architecture rules:

- Do NOT convert this into microservices.
- Do NOT route video bytes through Spring Boot.
- Spring Boot should handle video metadata, authorization and signed playback URLs.
- Actual playback should eventually be Browser/Player -> CloudFront -> S3.
- PostgreSQL should be the primary transactional database.
- Redis should be used only where justified, such as carts, caching, temporary state or distributed coordination.
- Kafka should not be used for every operation.
- Core transactional workflows should remain synchronous unless asynchronous processing has a clear reason.
- Prefer clean domain/module boundaries.
- Avoid unnecessary abstraction and overengineering.
- Do not generate fake AWS/payment integrations pretending they are functional.
- External systems should initially have proper interfaces/adapters so real integrations can be added later.
- Use database migrations rather than Hibernate auto-generating production schemas.
- Make concurrency and transaction boundaries explicit.

Java 21 / concurrency requirements:

I specifically want to learn traditional concurrency and virtual threads.

Configure Spring Boot to support Java 21 virtual threads.

Use:
spring.threads.virtual.enabled=true

However:
- Do not use virtual threads blindly.
- Explain where virtual threads are useful.
- Explain where normal thread pools are still appropriate.
- CPU-heavy work should not be presented as becoming faster because of virtual threads.
- When we implement background processing later, help me compare platform-thread executors vs virtual-thread-per-task execution.

For NOW, do NOT implement all these features.

PHASE 1 ONLY:

Create the initial project foundation.

Please do the following:

1. Inspect the current project first.
2. Tell me what already exists before modifying anything.
3. Create a clean package structure for the modular monolith.
4. Add only the dependencies required for the initial foundation:
    - Spring Web
    - Spring Validation
    - Spring Data JPA
    - PostgreSQL
    - Spring Security
    - Redis
    - Flyway
    - Actuator
    - Lombok only if genuinely useful
    - JUnit / Mockito
5. Do NOT add Kafka, Debezium or AWS SDK yet unless already present.
6. Configure Java 21.
7. Configure virtual threads.
8. Create application-local.yml or equivalent local-development configuration.
9. Create Docker Compose for:
    - PostgreSQL
    - Redis
10. Create a simple health endpoint or use Actuator health to verify the application.
11. Create module package placeholders without adding unnecessary classes.
12. Add an initial Flyway migration if needed.
13. Ensure the project compiles and tests run.

Suggested package structure:

com.<projectname>
common
config

    auth
        api
        application
        domain
        infrastructure

    user
        api
        application
        domain
        infrastructure

    course
        api
        application
        domain
        infrastructure

    enrollment
        api
        application
        domain
        infrastructure

    content
        api
        application
        domain
        infrastructure

    video
        api
        application
        domain
        infrastructure

    progress
        api
        application
        domain
        infrastructure

    cart
        api
        application
        domain
        infrastructure

    order
        api
        application
        domain
        infrastructure

    payment
        api
        application
        domain
        infrastructure

    bulkimport
        api
        application
        domain
        infrastructure

    notification
        api
        application
        domain
        infrastructure

Do not mechanically create empty Java files for every package unless required.

Before making changes, show me:

A. Proposed folder/package structure
B. Dependencies you want to add
C. Configuration you want to create
D. Why each one is needed

Then make the changes.

After implementation, show me:

1. Files created
2. Files modified
3. Commands to start PostgreSQL and Redis
4. Command to run the Spring Boot application
5. How to verify health
6. How virtual threads are enabled
7. What we should implement next

Do not move to Phase 2 automatically.

Stop after Phase 1 and wait for my approval.

Phase 1
Project foundation

        ↓

Phase 2
User + Auth

        ↓

Phase 3
Course + Content + Enrollment

        ↓

Phase 4
Video metadata + progress tracking

        ↓

Phase 5
S3 upload
MediaConvert
CloudFront/HLS

        ↓

Phase 6
Cart + Redis

        ↓

Phase 7
Order + concurrency-safe checkout

        ↓

Phase 8
Payment integration + webhooks

        ↓

Phase 9
Async Excel imports

        ↓

Phase 10
Kafka + Debezium CDC

        ↓

Phase 11
Virtual-thread experiments + load testing

        ↓

Phase 12
Observability / production hardening

Not yet bought aws services but assume they have been bought and are available for integration 
for now use hardcoded values or mock implementations for AWS services and payment gateways.
covering every scenario so i can understand even with mock implementations.