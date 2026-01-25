# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a **Campus Intelligent Knowledge Base System** - a RAG (Retrieval-Augmented Generation) application for Chinese campus student services. It enables students to ask questions about campus policies, procedures, and regulations, with answers grounded in uploaded documents (PDFs, Word files, etc.).

**Technology Stack**: Spring Boot 3.x, Java 17, MySQL, Elasticsearch, MinIO object storage, DeepSeek LLM, Tongyi Qianwen embedding model.

## Build and Development Commands

```bash
# Run the application locally (default port: 8000)
mvn spring-boot:run

# Run tests
mvn test

# Build the runnable JAR
mvn clean package

# Build without running tests (faster)
mvn clean package -DskipTests
```

## Prerequisites

Before running the application, ensure the following services are running locally:
- MySQL (default: localhost:3306, database: `rag`)
- Elasticsearch (default: localhost:9200)
- MinIO object storage (default: localhost:9000)

The database schema is defined in `src/main/resources/database.sql`.

## Architecture Overview

### Package Structure

The codebase follows standard Spring Boot conventions under `com.campus.knowledge`:

- **`client/`**: External API integrations (LLM chat, vector embedding)
- **`config/`**: Spring configuration beans (Elasticsearch, MinIO, HTTP clients)
- **`controller/`**: REST API endpoints (`KnowledgeController`, `EvaluationController`)
- **`dto/`**: Data transfer objects for API requests/responses
- **`model/`**: JPA entity classes mapped to database tables
- **`repository/`**: Spring Data JPA repositories for database access
- **`service/`**: Core business logic services

### RAG Pipeline Flow

The system implements an **Agentic RAG** architecture with multiple enhancement strategies:

#### 1. Document Ingestion

```
File Upload → MD5 Deduplication → MinIO Storage →
Apache Tika Parsing → HanLP Chinese Segmentation →
Text Chunking (512 chars) → Vector Embedding (Tongyi text-embedding-v4, 2048-dim) →
Elasticsearch Indexing (index: knowledge)
```

**Key Services**:
- `DocumentProcessor`: File parsing and chunking using Apache Tika and HanLP for semantic-aware Chinese text splitting
- `EmbeddingIndexer`: Batch vectorization and Elasticsearch indexing

#### 2. Hybrid Retrieval

The core retrieval strategy combines **vector similarity + BM25 reranking**:

```
Query → Query Vectorization →
kNN Recall (k × 30, cosine similarity) →
Keyword Filter (Match Query) →
BM25 Rescore (weight: 1.0 vs vector: 0.2) →
Top-K Results
```

**Key Services**:
- `SmartRetrieverService`: Implements hybrid search with Elasticsearch kNN + BM25 rescore
- `QueryRefiner`: Multi-query rewriting for RAG-Fusion (when `rag.rewrite.enabled: true`)
- `QueryRoutingService`: Metadata-based routing (department, doc type, policy year, tags)
- `RerankerService`: LLM-based reranking of top candidates (when `rag.rerank.enabled: true`)
- `CragService`: CRAG (Corrective RAG) - evaluates retrieval quality and triggers fallback strategies or clarification questions

#### 3. Answer Generation

```
User Query + Retrieval Results + Conversation History →
DeepSeek LLM (deepseek-chat) →
Answer with Source Attribution
```

**Key Services**:
- `ConversationManager`: Manages multi-turn conversations, context construction, LLM interaction, and source tracking
- `LlmChatService` (in `client/`): DeepSeek API client with streaming support

### Advanced RAG Features

The codebase implements several research-backed RAG enhancement techniques:

- **RAG-Fusion**: Multi-query rewriting + reciprocal rank fusion (`rag.fusion.enabled`)
- **HyDE (Hypothetical Document Embeddings)**: Generate ideal answer for retrieval (`rag.hyde.enabled`)
- **CRAG (Corrective RAG)**: Quality assessment with fallback strategies (`rag.crag.enabled`)
- **LLM-based Reranking**: Score and reorder candidates (`rag.rerank.enabled`)
- **Query Routing**: Metadata-based filtering for department/doc type/year/tags (`rag.routing.enabled`)
- **Feedback Loop**: User feedback influences future retrieval scores (`rag.feedback.enabled`)

These are controlled via `application.yml` under the `rag:` section.

### Database Schema

Core tables (see `database.sql` for full schema):

- **`document_records`**: File metadata (MD5, name, size, owner, visibility, doc_type, department, policy_year, tags)
- **`text_segments`**: Chunked text with fragment index
- **`conversation_messages`**: Chat history (session_id, role, content)
- **`conversation_message_sources`**: Source attribution for answers (message → document chunks)
- **`conversation_message_feedback`**: User ratings (1-5 score) for answer quality
- **`rag_evaluation_runs`** / **`rag_evaluation_results`**: Offline evaluation framework

### Key Configuration Files

- **`application.yml`**: All configuration (DB, MinIO, Elasticsearch, LLM/embedding API keys, RAG strategy toggles)
  - **Security Note**: The file contains placeholder API keys. Override sensitive values via environment variables (`DEEPSEEK_API_KEY`, `EMBEDDING_API_KEY`, `MINIO_ACCESSKEY`, etc.)
- **`database.sql`**: MySQL schema definition
- **`eval/benchmarks.json`**: Evaluation dataset for RAG quality metrics

### Evaluation Framework

The system includes a built-in evaluation suite:

- **Service**: `RagEvaluationService`
- **Endpoint**: `/api/eval/run` (POST)
- **Metrics**: Hit rate, average similarity (question-answer matching)
- **Dataset**: `src/main/resources/eval/benchmarks.json`

Use this to measure retrieval/generation quality after making changes.

## Coding Conventions

- **Java Style**: 4-space indentation, standard Java brace style
- **Naming**: Packages in `lowercase`, classes in `PascalCase`, methods/fields in `camelCase`, constants in `UPPER_SNAKE_CASE`
- **Lombok**: Heavily used for DTOs/models (`@Data`, `@Entity`, `@Builder`, etc.)
- **Logging**: Use SLF4J (`LoggerFactory.getLogger(...)`)
- **Error Handling**: Most exceptions are logged and returned as HTTP 500 with error messages in response body

## Important Design Patterns

### Service Layer Organization

Business logic is isolated in services, NOT controllers. Controllers only handle HTTP mapping and validation.

### Multi-Strategy Retrieval

The retrieval flow is modular - each strategy (rewrite, HyDE, fusion, rerank, CRAG) can be toggled independently via config. When adding new strategies, follow the existing pattern:
1. Add config keys in `application.yml` under `rag:`
2. Create/extend service class
3. Wire into `SmartRetrieverService` or `ConversationManager`

### Permission-Aware Retrieval

All retrieval methods accept an optional `userId` parameter. Document visibility is controlled by:
- `document_records.visibility` (PRIVATE/PUBLIC)
- Upload owner ID (owner can access their own private documents)

Filter logic is in `SmartRetrieverService.filterAndEnrichMatches()`.

## Common Tasks

### Adding a New RAG Strategy

1. Add configuration in `application.yml` under `rag:` section
2. Create a new service class (e.g., `MyStrategyService.java`) in `service/`
3. Inject into `SmartRetrieverService` or `ConversationManager`
4. Update retrieval pipeline to call new strategy when enabled

### Changing Chunking Strategy

Modify `DocumentProcessor.splitTextIntoChunksWithSemantics()`. The current implementation uses HanLP for Chinese word segmentation to preserve semantic boundaries.

### Adjusting Hybrid Search Weights

In `SmartRetrieverService.performHybridRetrieval()`, modify:
- `kNN` recall size: currently `topK × 30`
- `rescore` window: currently same as recall size
- Score weights: currently vector `0.2`, BM25 `1.0`

### Testing Changes

Place tests in `src/test/java` mirroring package structure. Use `*Test` suffix for unit tests, `*IT` for integration tests.

## External Dependencies

- **DeepSeek API**: LLM for answer generation (model: `deepseek-chat`)
- **Tongyi Qianwen API**: Text embeddings (model: `text-embedding-v4`, 2048-dim)
- Both require API keys configured in `application.yml` or environment variables

## Web UI

A basic web interface is included:
- `src/main/resources/static/index.html`: Chat interface
- `src/main/resources/static/docs.html`: Document management
- Access at `http://localhost:8000/` after starting the app

## Research Context

This codebase is part of a graduation project exploring **Agentic RAG for campus student services**. Key research papers referenced:
- ReAct (ICLR 2023): Reasoning + Acting paradigm
- Self-RAG (2023): Reflection tokens for on-demand retrieval
- CRAG (2024): Corrective RAG with fallback mechanisms
- RAG-Fusion, HyDE, Adaptive-RAG, GraphRAG

See `report.md` for detailed research notes and design rationale.
