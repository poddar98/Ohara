# Ohara --- Claude Repository Analysis & Architecture Study Prompt

## Purpose

Use this prompt with Claude **after giving Claude access to the complete
Ohara repository**.

The goal is not merely to review the code. The goal is to turn the
repository into a rigorous, implementation-grounded understanding of:

1.  what has actually been built,
2.  how the system works end-to-end,
3.  why the architecture is shaped the way it is,
4.  what is incomplete or potentially problematic,
5.  how the system should evolve as the EdTech platform grows, and
6.  how I should explain and defend the design in SDE-2/SDE-3 backend
    interviews.

------------------------------------------------------------------------

# 1. Critical instruction: inspect the repository before forming conclusions

You have access to the Ohara repository.

**Read the repository first. Do not infer the architecture from this
prompt alone.**

Inspect, as applicable:

-   complete directory/module structure
-   `pom.xml`
-   application configuration
-   Java source code
-   entities
-   repositories
-   services
-   controllers
-   DTOs
-   configuration classes
-   security configuration
-   exception handling
-   database migrations
-   tests
-   Docker configuration
-   documentation
-   build configuration
-   dependency versions
-   any scripts
-   any infrastructure/configuration files

Search the entire repository where necessary.

For every important architectural conclusion, identify the concrete
implementation evidence:

-   file path
-   class/interface
-   method
-   configuration property
-   annotation
-   dependency
-   database migration
-   test

Do **not** invent services, databases, queues, consumers, producers,
APIs, or workflows that do not exist.

If something is only suggested by a dependency but is not actually used
in code, explicitly classify it as:

> Dependency present, implementation usage not verified.

Likewise, if something appears in architectural discussions but not in
the repository, classify it as:

> Discussed/planned, but not implemented in the supplied repository.

------------------------------------------------------------------------

# 2. Current repository baseline

The supplied repository metadata indicates the following baseline.
**Verify all of these against the complete repository before treating
them as implementation facts.**

### Java

-   Java 21

### Spring

-   Spring Boot 4.0.8
-   Spring Framework 7.x
-   Spring Security 7.x
-   Spring MVC
-   Spring Data JPA
-   Spring Data Redis
-   Spring Validation
-   Spring Actuator

### Persistence

-   PostgreSQL 16
-   Flyway
-   JPA/Hibernate

### Caching / data infrastructure

-   Redis 7

### Resilience

-   Spring Cloud CircuitBreaker
-   Resilience4j 2.3.0

### Modular architecture

-   Spring Modulith 2.0.8
-   Spring Modulith actuator
-   Spring Modulith observability
-   jMolecules events/stereotypes
-   ArchUnit appears transitively through Modulith

### Security

-   Spring Security
-   JJWT 0.12.6

### Observability

-   Actuator
-   Micrometer
-   Micrometer tracing
-   Spring Modulith observability

### Local infrastructure

The supplied Docker Compose configuration defines:

-   PostgreSQL 16 Alpine
-   Redis 7 Alpine
-   persistent Docker volumes
-   PostgreSQL healthcheck
-   Redis healthcheck
-   Redis AOF enabled

The supplied compose configuration does **not** show Kafka, Debezium,
object storage, a video-processing stack, or a separate microservice
deployment. Verify the full repository before concluding that these
components do not exist elsewhere.

------------------------------------------------------------------------

# 3. First deliverable: repository inventory

Start with a precise inventory.

Produce:

## 3.1 Repository tree

Show the important repository structure in a compact tree.

Explain the purpose of each significant package/module.

## 3.2 Technology inventory

Create a table:

  Area            Technology              Version Actually used?   Evidence   Purpose
  --------------- --------------------- --------- ---------------- ---------- ---------
  Language        Java                         21                             
  Framework       Spring Boot               4.0.8                             
  Web             Spring MVC                                                  
  Database        PostgreSQL                   16                             
  ORM             JPA/Hibernate                                               
  Cache           Redis                         7                             
  Migration       Flyway                                                      
  Security        Spring Security             7.x                             
  Resilience      Resilience4j              2.3.0                             
  Architecture    Spring Modulith           2.0.8                             
  Observability   Actuator/Micrometer                                         

Add any technologies actually discovered in the repository.

Do not mark a technology as "used" merely because the dependency exists.

------------------------------------------------------------------------

# 4. Classify implementation maturity

For every meaningful subsystem, classify it as one of:

### IMPLEMENTED

There is working code and supporting configuration/tests.

### PARTIALLY IMPLEMENTED

There is meaningful implementation but important pieces are missing.

### SCAFFOLDING

The dependency/configuration/package exists but functionality is mostly
absent.

### PLANNED / DISCUSSED

It is part of the intended architecture but not present in code.

### UNKNOWN

The supplied repository does not provide enough evidence.

This distinction is extremely important.

Create a table:

  Subsystem   Status   Evidence   Missing pieces
  ----------- -------- ---------- ----------------

------------------------------------------------------------------------

# 5. Determine the architectural style

Do not assume that Ohara is a microservices architecture.

Determine whether the repository currently represents:

-   monolith
-   modular monolith
-   distributed monolith
-   microservices
-   hybrid architecture

Pay particular attention to Spring Modulith.

Explain:

-   module boundaries
-   module dependencies
-   domain boundaries
-   internal vs external communication
-   events
-   transactional boundaries
-   whether modules are genuinely decoupled
-   whether the architecture is prepared for future extraction into
    services

Explain why Spring Modulith is being used, if it actually is.

Also explain what Spring Modulith **does not** provide.

------------------------------------------------------------------------

# 6. Build the actual architecture diagram

Create a Mermaid architecture diagram based only on the repository.

Show:

``` text
Client
  |
  v
HTTP/API layer
  |
  v
Application / domain modules
  |
  +---- PostgreSQL
  |
  +---- Redis
  |
  +---- External services
```

Expand this according to the actual implementation.

If Kafka, Debezium, object storage, video processing, payment providers,
or other systems are absent, do not add them to the "current
architecture" diagram.

Instead create a separate:

> Proposed future architecture

diagram only if justified.

------------------------------------------------------------------------

# 7. Trace important flows end-to-end

For every major business flow discovered in the repository, trace:

``` text
HTTP request
   ↓
Controller
   ↓
DTO / validation
   ↓
Service / application layer
   ↓
Domain logic
   ↓
Repository
   ↓
Database
   ↓
Events / cache / external systems
```

For each flow explain:

-   entry point
-   authentication/authorization
-   validation
-   transaction boundary
-   database operations
-   cache interaction
-   emitted events
-   external calls
-   failure handling
-   retry behavior
-   response
-   consistency model

Do not describe only classes. Explain the **runtime behavior**.

------------------------------------------------------------------------

# 8. Database analysis

Inspect every entity and migration.

Produce:

-   entity relationship overview
-   important tables
-   primary keys
-   foreign keys
-   unique constraints
-   indexes
-   nullable fields
-   enum/state fields
-   timestamps
-   soft-delete mechanisms if any
-   optimistic/pessimistic locking if any

For every major transaction, answer:

1.  What data changes atomically?
2.  Where is `@Transactional` applied?
3.  What isolation assumptions exist?
4.  What happens if an operation fails halfway?
5.  Are there race conditions?
6.  Is idempotency required?
7.  Are database constraints being used as correctness mechanisms?

Identify N+1 risks, inefficient queries, missing indexes, oversized
transactions, and accidental eager loading if present.

Do not recommend indexes without explaining the query pattern that
justifies them.

------------------------------------------------------------------------

# 9. Redis analysis

Determine exactly how Redis is used.

Possible roles include:

-   cache
-   session storage
-   token/blacklist storage
-   rate limiting
-   distributed lock
-   idempotency key store
-   temporary state
-   counters
-   queues
-   pub/sub

For every usage explain:

-   key structure
-   TTL
-   serialization
-   invalidation
-   consistency expectations
-   failure behavior
-   whether Redis is authoritative or merely an acceleration layer

Then answer:

> Is Redis currently sufficient for the repository's needs, or is a
> PostgreSQL read replica actually justified?

Do not answer this abstractly. Base it on the actual workload and access
patterns.

------------------------------------------------------------------------

# 10. Concurrency and Java 21

Inspect the repository for:

-   `ExecutorService`
-   `CompletableFuture`
-   `Future`
-   `@Async`
-   thread pools
-   synchronized blocks
-   locks
-   semaphores
-   virtual threads
-   structured concurrency
-   blocking I/O
-   reactive code
-   background jobs

Determine whether virtual threads are:

-   actually used,
-   configured,
-   indirectly enabled,
-   or merely available because Java 21 is used.

Explain:

## Platform threads

How conventional Java thread-per-request execution works.

## Virtual threads

When virtual threads are useful.

## Important limitation

Virtual threads improve concurrency for blocking workloads, but they do
not make CPU-heavy work magically faster and do not remove the need for
database connection-pool limits, downstream capacity limits,
backpressure, or proper concurrency control.

Determine whether virtual threads would benefit any actual workload in
this repository.

------------------------------------------------------------------------

# 11. Security analysis

Inspect the complete Spring Security configuration.

Explain:

-   authentication mechanism
-   JWT creation
-   JWT validation
-   access/refresh token strategy
-   authorization
-   roles/permissions
-   password hashing
-   filters
-   statelessness/statefulness
-   CORS
-   CSRF
-   session configuration
-   endpoint protection
-   secret management

Look for:

-   insecure defaults
-   hardcoded secrets
-   overly broad authorization
-   token leakage
-   missing validation
-   privilege escalation possibilities
-   insecure error responses

Do not merely list vulnerabilities. Explain the concrete attack/failure
scenario.

------------------------------------------------------------------------

# 12. Resilience4j analysis

Determine whether Resilience4j is merely included or actually configured
and used.

For each usage inspect:

-   circuit breaker
-   retry
-   rate limiter
-   time limiter
-   bulkhead
-   fallback

Explain:

> What failure is this mechanism designed to contain?

Also identify where resilience patterns would be harmful if applied
indiscriminately.

For example:

-   retrying non-idempotent payment operations
-   retry storms
-   retrying validation failures
-   circuit breakers around local database calls without a clear reason

------------------------------------------------------------------------

# 13. Observability

Inspect:

-   Actuator
-   health checks
-   metrics
-   tracing
-   logging
-   correlation IDs
-   structured logging
-   custom metrics
-   Spring Modulith observability

Explain what can currently be observed in production.

Identify missing:

-   business metrics
-   latency metrics
-   error-rate metrics
-   database metrics
-   cache metrics
-   queue metrics
-   distributed traces
-   alerting

Do not recommend observability components without tying them to
operational failure modes.

------------------------------------------------------------------------

# 14. Event-driven architecture

Search the repository for:

-   Spring application events
-   Spring Modulith events
-   domain events
-   transactional events
-   event publication
-   event listeners
-   asynchronous event listeners
-   persistent event externalization
-   Kafka
-   Debezium
-   CDC

Determine exactly what exists.

If Kafka/Debezium is absent, explicitly say so.

Then explain the distinction between:

### In-process domain events

and

### Durable external event streaming

and

### CDC

Explain when each is appropriate.

------------------------------------------------------------------------

# 15. Kafka / Debezium evaluation

This is especially important because the broader architecture discussion
includes Kafka and Debezium.

Do not assume they exist.

First verify the repository.

If absent, create a section:

> Kafka/Debezium: architectural consideration, not current
> implementation

Then explain where they could eventually fit, but do not pretend they
are already part of Ohara.

Discuss:

-   transactional outbox
-   CDC
-   Debezium
-   Kafka
-   consumers
-   partitions
-   ordering
-   consumer groups
-   at-least-once delivery
-   idempotent consumers
-   replayability
-   schema evolution

Also explain when Kafka would be unnecessary complexity for a
small/mid-sized EdTech platform.

------------------------------------------------------------------------

# 16. Async Excel processing

The broader project architecture includes a large Excel import workflow.

Search the repository for:

-   Excel/CSV upload
-   Apache POI
-   file processing
-   background jobs
-   job status
-   polling
-   failed-row handling
-   retry
-   batch processing

If present, trace it completely.

Explain:

``` text
Upload
  ↓
Create job
  ↓
Persist job state
  ↓
Background processing
  ↓
Batch validation
  ↓
Successful rows
  ↓
Failed rows
  ↓
Final status
  ↓
Client polling / notification
```

Determine exactly how failed rows are represented and exposed to users.

If absent, say so.

------------------------------------------------------------------------

# 17. Payment / cart architecture

Search for:

-   cart
-   order
-   payment
-   refund
-   cancellation
-   pending order
-   payment status
-   idempotency
-   webhook
-   transaction
-   inventory

If implemented, explain the lifecycle:

``` text
Cart
 ↓
Order creation
 ↓
Payment initiation
 ↓
Payment provider
 ↓
Webhook/callback
 ↓
Payment confirmation
 ↓
Order confirmation
```

Pay particular attention to:

-   duplicate requests
-   duplicate webhooks
-   retries
-   concurrent devices
-   stale carts
-   payment success but API timeout
-   API success but payment failure
-   refund consistency

If payment infrastructure is absent, keep this as future architecture
rather than current architecture.

------------------------------------------------------------------------

# 18. Video architecture

Search the repository for actual video-related code.

Determine whether the current repository handles:

-   video metadata
-   uploads
-   object storage
-   transcoding
-   HLS
-   DASH
-   MP4
-   byte-range requests
-   signed URLs
-   CDN
-   playback progress
-   resume position
-   live streaming
-   recorded lectures

Do not assume that Spring MVC should stream large video files directly.

Explain the distinction between:

### Application/API server

and

### Object storage

and

### Transcoding pipeline

and

### CDN

and

### Video player

If the video subsystem is not implemented, explicitly classify it as
future architecture.

------------------------------------------------------------------------

# 19. API design review

Inspect all controllers and APIs.

Evaluate:

-   URL structure
-   HTTP semantics
-   status codes
-   request/response DTOs
-   validation
-   pagination
-   filtering
-   sorting
-   error contracts
-   versioning
-   idempotency
-   authentication
-   authorization

Identify APIs that are likely to become difficult to evolve.

------------------------------------------------------------------------

# 20. Testing strategy

Inspect all tests.

Classify:

-   unit tests
-   integration tests
-   repository tests
-   controller tests
-   security tests
-   architecture tests
-   Modulith tests
-   end-to-end tests

Determine:

-   what is tested
-   what is not tested
-   whether H2 is used
-   whether PostgreSQL behavior may differ from H2
-   whether Redis is tested
-   whether concurrency is tested
-   whether transaction boundaries are tested

Recommend the smallest set of tests that would materially improve
confidence.

------------------------------------------------------------------------

# 21. Architecture risks

Create a prioritized risk register.

Use:

  Risk   Evidence   Impact   Likelihood   Why it matters   Mitigation
  ------ ---------- -------- ------------ ---------------- ------------

Do not manufacture risks.

Focus on concrete engineering concerns such as:

-   transaction races
-   missing indexes
-   inconsistent cache invalidation
-   lack of idempotency
-   oversized synchronous requests
-   inadequate failure handling
-   unbounded concurrency
-   connection-pool exhaustion
-   insufficient observability
-   security flaws
-   poor module boundaries
-   accidental coupling

------------------------------------------------------------------------

# 22. Architecture evolution

Explain how the current system could evolve from:

``` text
Single deployable application
        ↓
Modular monolith
        ↓
Selective asynchronous processing
        ↓
Event-driven integrations
        ↓
Selective service extraction
```

Do not advocate microservices simply because the system is expected to
grow.

For every potential extraction answer:

1.  What business boundary justifies the service?
2.  What independent scaling requirement exists?
3.  What deployment independence is required?
4.  What data ownership boundary exists?
5.  What operational complexity is introduced?
6.  Could Spring Modulith solve the problem without splitting the
    process?

------------------------------------------------------------------------

# 23. Read-replica analysis

Specifically evaluate the question:

> Do we need a PostgreSQL read replica, or is Redis enough?

Explain that Redis and a read replica solve different problems.

Compare:

  Concern                  Redis   PostgreSQL read replica
  ------------------------ ------- -------------------------
  Cache hot data                   
  Durable data                     
  Query flexibility                
  Read scaling                     
  Freshness                        
  Complex queries                  
  Failure mode                     
  Operational complexity           

Then determine what the actual Ohara workload warrants.

Do not recommend a replica merely because read traffic exists.

------------------------------------------------------------------------

# 24. Production scaling model

Estimate the architectural bottlenecks conceptually from the actual
implementation.

Consider:

``` text
Concurrent users
       ↓
HTTP threads / virtual threads
       ↓
DB connection pool
       ↓
PostgreSQL
       ↓
Redis
       ↓
External services
```

Explain which layer is likely to saturate first under different
workloads.

Important:

> More Java threads do not mean more database concurrency.

Discuss:

-   connection pool sizing
-   database CPU
-   database locks
-   query latency
-   Redis throughput
-   downstream rate limits
-   external API latency

------------------------------------------------------------------------

# 25. Failure-mode analysis

For every critical flow answer:

### What if the database is unavailable?

### What if Redis is unavailable?

### What if an external API times out?

### What if the request is retried?

### What if the same request arrives twice?

### What if the process crashes halfway through?

### What if two devices perform the operation concurrently?

### What if a background worker crashes?

### What if an event is delivered twice?

### What if an event is delivered out of order?

### What if a cache entry is stale?

### What if a transaction succeeds but the response is lost?

These are interview-critical questions.

------------------------------------------------------------------------

# 26. SDE-2 / SDE-3 interview preparation

Based on the **actual repository**, generate interview questions in
increasing difficulty.

## Level 1 --- Code understanding

Questions such as:

-   Why did you structure this package this way?
-   Why JPA?
-   Why Redis?
-   Why Flyway?
-   Why JWT?
-   Why Spring Modulith?

## Level 2 --- Backend fundamentals

-   transaction boundaries
-   isolation
-   indexing
-   caching
-   concurrency
-   connection pools
-   idempotency
-   retries
-   API design

## Level 3 --- Architecture

-   modular monolith vs microservices
-   event-driven architecture
-   asynchronous processing
-   scaling
-   consistency
-   failure handling
-   observability

## Level 4 --- Deep follow-ups

Generate adversarial interviewer questions.

Examples:

> Why do you need Redis if PostgreSQL can handle the query?

> Why not simply add a read replica?

> Why Kafka instead of a database-backed job queue?

> Why Debezium?

> Why not publish directly after the transaction?

> What happens if the event is published but the transaction rolls back?

> What happens if Kafka delivers the same message twice?

> What happens if two users modify the same resource concurrently?

> Why virtual threads instead of a bounded executor?

> What limits your system if you can create millions of virtual threads?

> Why not make this a microservice?

> What is your actual service boundary?

> What happens if Redis goes down?

> Is Redis authoritative?

> How do you guarantee payment idempotency?

> How do you resume a failed Excel job?

For each question provide:

1.  strong answer
2.  deeper explanation
3.  likely follow-up
4.  common mistake
5.  concise interview version

------------------------------------------------------------------------

# 27. "Explain this project in an interview"

Create three versions.

## 30-second version

A concise project summary.

## 2-minute version

Include:

-   business problem
-   architecture
-   important engineering decisions
-   scale
-   reliability
-   major technical challenges

## 5--10 minute deep dive

Explain one major subsystem end-to-end.

The answer must be grounded in actual repository implementation.

------------------------------------------------------------------------

# 28. Personal learning map

I am using this project to strengthen backend engineering and
system-design knowledge.

Create a learning roadmap based on what the repository actually uses.

Organize it into:

### Tier 1 --- Must understand

Things directly necessary to explain the existing code.

### Tier 2 --- Should understand

Things needed to make sound architectural decisions.

### Tier 3 --- Advanced

Things useful for SDE-3/system-design discussions.

### Tier 4 --- Future architecture

Technologies that are not currently implemented but are relevant to the
platform's evolution.

For each topic provide:

-   what to learn
-   why it matters
-   exact repository location where it appears
-   interview questions
-   practical exercise

------------------------------------------------------------------------

# 29. Do not over-engineer the recommendations

This is a critical requirement.

The target is a realistic small-to-mid-scale EdTech startup.

Do not recommend:

-   Kafka everywhere
-   microservices everywhere
-   multiple databases without justification
-   Redis for every read
-   read replicas prematurely
-   distributed locks without a real concurrency problem
-   event sourcing without a clear requirement
-   Kubernetes merely because it is common
-   complex workflow engines without workflow complexity
-   service meshes without operational need

For every proposed technology answer:

> What concrete problem does this solve that the current system cannot
> solve adequately?

If there is no strong answer, classify it as unnecessary complexity.

------------------------------------------------------------------------

# 30. Separate facts, inference, and recommendations

Every major section should distinguish:

### FACT

Directly supported by repository code/configuration.

### INFERENCE

Reasonable interpretation of the implementation.

### RECOMMENDATION

A proposed improvement.

### FUTURE OPTION

An architecture that may become useful at a different scale.

Do not blur these categories.

------------------------------------------------------------------------

# 31. Version-aware analysis

The repository uses a modern Spring stack.

When discussing framework behavior, verify version-sensitive details
against current official documentation where appropriate.

Pay particular attention to:

-   Spring Boot 4.x
-   Spring Framework 7.x
-   Spring Security 7.x
-   Spring Modulith 2.x
-   Java 21
-   PostgreSQL 16
-   Redis 7
-   Resilience4j 2.x

Do not silently apply advice that only applies to older Spring
Boot/Spring Security versions.

------------------------------------------------------------------------

# 32. Final output structure

Your final response should contain:

# Ohara Architecture & Engineering Dossier

## 1. Executive Summary

## 2. Repository Inventory

## 3. Technology Stack

## 4. Current Architecture

## 5. Module Boundaries

## 6. Database Architecture

## 7. Redis Architecture

## 8. Security Architecture

## 9. Resilience Architecture

## 10. Observability

## 11. Concurrency & Java 21

## 12. Event Architecture

## 13. Async Processing

## 14. Payment / Order Architecture

## 15. Video Architecture

## 16. API Design

## 17. Testing

## 18. Failure Modes

## 19. Scalability

## 20. Architecture Risks

## 21. Recommended Evolution

## 22. What Is Actually Implemented vs Planned

## 23. Interview Explanation

## 24. Interview Questions & Answers

## 25. Learning Roadmap

## 26. Final Architecture Summary

------------------------------------------------------------------------

# 33. Final rule

The most important principle is:

> **Understand the code before designing around it.**

Do not transform an architectural idea into an implementation fact.

Do not praise or criticize a component merely because it is fashionable.

Do not recommend distributed systems machinery without identifying the
concrete bottleneck or consistency/reliability requirement it addresses.

The objective is to understand **why this system exists, how it works,
where it will break, and how it should evolve**.

The resulting analysis should be useful both for:

1.  actually building Ohara, and
2.  defending the engineering decisions in a serious
    backend/system-design interview.

------------------------------------------------------------------------

# Appendix --- Initial repository evidence supplied with this prompt

The currently supplied repository artifacts indicate:

-   Java 21 is configured in Maven.
-   Spring Boot 4.0.8 is configured.
-   Spring Cloud dependency management is 2025.1.3.
-   Spring Modulith dependency management is 2.0.8.
-   Spring MVC, Security, Validation, JPA, Redis, Actuator, Flyway,
    PostgreSQL, Resilience4j CircuitBreaker, and Spring Modulith
    dependencies are present.
-   JJWT 0.12.6 is present.
-   Docker Compose defines PostgreSQL 16 and Redis 7.
-   Redis is configured with AOF persistence in the supplied Compose
    file.
-   The supplied dependency resolution includes Micrometer, tracing,
    Spring Modulith observability, Resilience4j, and Spring Security
    7.x.

**Important:** these artifacts alone are not sufficient to conclude that
every dependency represents an implemented feature. Validate actual
usage against the complete repository.
