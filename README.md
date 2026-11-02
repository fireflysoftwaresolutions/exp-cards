<a href="https://fireflysoftware.io/openplatform/">
<picture>
  <source media="(max-width: 640px) and (prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/firefly-oss/.github/main/profile/assets/banner-narrow-dark.svg">
  <source media="(max-width: 640px)" srcset="https://raw.githubusercontent.com/firefly-oss/.github/main/profile/assets/banner-narrow-light.svg">
  <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/firefly-oss/.github/main/profile/assets/banner-dark.svg">
  <img src="https://raw.githubusercontent.com/firefly-oss/.github/main/profile/assets/banner-light.svg" alt="Firefly OpenPlatform - Experience - Cards" width="100%">
</picture>
</a>

# Firefly OpenPlatform - Experience - Cards

Developed by the Firefly Software Foundation. [dev@fireflysoftware.io](mailto:dev@fireflysoftware.io)

[Website](https://fireflysoftware.io/) · [OpenPlatform](https://fireflysoftware.io/openplatform/) · [Module catalogue](https://fireflysoftware.io/openplatform/modules/)

> Experience layer service that provides card management journeys for frontend applications

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Module Structure](#module-structure)
- [API Endpoints](#api-endpoints)
- [Domain SDK Dependencies](#domain-sdk-dependencies)
- [Configuration](#configuration)
- [Running Locally](#running-locally)
- [Testing](#testing)

## Overview

`exp-cards` is the experience-layer (BFF) service that exposes card management capabilities to frontend applications. It provides a simplified API surface for common card operations including issuing cards, viewing card summaries, listing customer cards, activating, blocking, and canceling cards.

Unlike domain services that orchestrate complex multi-step sagas, `exp-cards` focuses on composing domain service calls into frontend-friendly responses. It delegates all business orchestration to `domain-banking-cards` and transforms responses into experience-layer DTOs optimized for UI consumption.

## Architecture

```
Frontend / Mobile App
         |
         v
exp-cards  (port 8101)
         |
         +---> CardExperienceService
         |             |
         |             v
         +---> domain-banking-cards-sdk
                       |
                       +---> CardsApi
                       +---> CardBackofficeApi
```

## Module Structure

| Module | Purpose |
|--------|---------|
| `exp-cards-interfaces` | Experience-layer DTOs: `IssueCardRequest`, `IssueCardResponse`, `CardSummaryResponse` |
| `exp-cards-core` | Service interfaces and implementations: `CardExperienceService`, `CardExperienceServiceImpl` |
| `exp-cards-infra` | `DomainBankingCardsClientFactory` (CardsApi, CardBackofficeApi) and `@ConfigurationProperties` |
| `exp-cards-web` | `CardsController`, Spring Boot application class, `application.yaml` |
| `exp-cards-sdk` | Auto-generated reactive SDK from the OpenAPI spec |

## API Endpoints

Base path: `/api/v1/cards`

| Method | Path | Description | Response |
|--------|------|-------------|----------|
| `POST` | `/api/v1/cards` | Issue a new card | `201 Created` with `IssueCardResponse` |
| `GET` | `/api/v1/cards/{cardId}` | Get card summary | `200 OK` with `CardSummaryResponse` |
| `GET` | `/api/v1/cards/customer/{customerId}` | Get all cards for a customer | `200 OK` with `Flux<CardSummaryResponse>` |
| `POST` | `/api/v1/cards/{cardId}/activate` | Activate a card | `204 No Content` |
| `POST` | `/api/v1/cards/{cardId}/block` | Block a card | `204 No Content` |
| `DELETE` | `/api/v1/cards/{cardId}` | Cancel a card | `204 No Content` |

### Request/Response DTOs

**IssueCardRequest:**
```json
{
  "customerId": "uuid",
  "accountId": "uuid",
  "cardProgramId": "uuid",
  "cardType": "DEBIT"
}
```

**IssueCardResponse:**
```json
{
  "cardId": "uuid",
  "executionId": "uuid",
  "status": "COMPLETED"
}
```

**CardSummaryResponse:**
```json
{
  "cardId": "uuid",
  "maskedCardNumber": "****1234",
  "cardType": "DEBIT",
  "status": "ACTIVE",
  "expiryDate": "2028-12"
}
```

## Domain SDK Dependencies

| SDK | ClientFactory | APIs Used | Purpose |
|-----|--------------|-----------|---------|
| `domain-banking-cards-sdk` | `DomainBankingCardsClientFactory` | `CardsApi`, `CardBackofficeApi` | Issue cards, query summaries, activate, block, cancel |

## Configuration

```yaml
server:
  port: ${SERVER_PORT:8101}

firefly:
  cqrs:
    enabled: true
    command.timeout: 30s
    query:
      timeout: 15s
      caching-enabled: true
      cache-ttl: 5m

api-configuration:
  domain-platform:
    banking-cards:
      base-path: ${BANKING_CARDS_URL:http://localhost:8091}
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8101` | HTTP server port |
| `BANKING_CARDS_URL` | `http://localhost:8091` | Base URL for `domain-banking-cards` |

## Running Locally

```bash
# Prerequisites: ensure domain-banking-cards is running
cd exp-cards
mvn spring-boot:run -pl exp-cards-web
```

Server starts on port `8101`. Swagger UI: [http://localhost:8101/swagger-ui.html](http://localhost:8101/swagger-ui.html)

Swagger UI is disabled in the `prod` profile.

## Testing

```bash
mvn clean verify
```

Tests cover `CardExperienceServiceImpl` (unit tests with mocked domain SDK) and `CardsController` (WebTestClient-based integration tests).

---

## Spring Profiles

| Profile | Logging | Swagger | Notes |
|---------|---------|---------|-------|
| `default` | INFO | Enabled | Standard development |
| `dev` | DEBUG | Enabled | Verbose debugging |
| `prod` | INFO | Disabled | Production |
| `openapi` | WARN | Enabled (port 18080) | OpenAPI spec generation |


## Identity, access, and decisions

Each journey command resolves the actor, then checks access, before it calls a domain port. A denial does not call the domain.

1. **IdentityPort** posts to `POST /api/v1/identity/subjects` on domain-core-security-center. The default username is `sample-journey`, which resolves to actor `11111111-1111-4111-8111-111111111111`. `denied-actor` resolves to `22222222-2222-4222-8222-222222222222`. `sample-employee` resolves to `33333333-3333-4333-8333-333333333333`. Experience code does not call Keycloak. The security center property `firefly.security-center.identity.adapter` is `sandbox` by default, or `keycloak`, `cognito`, or `internal-db`.
2. **AccessPort** posts to `POST /api/v1/access/checks` with the actor, action (`READ`, `CREATE`, `UPDATE`, `CLOSE`, or `DECIDE`), resource type, and resource id. The sample actor may READ and CREATE the sample resources. The denied actor receives `ACTOR_NOT_ENTITLED`.
3. Lending application create and onboarding initiation then call **DecisionPort** at `POST /api/v1/decisions` on domain-lending-credit-scoring. A `DECLINE` does not call the domain command.
4. The domain port runs only after access is allowed.

Set `firefly.experience.access.adapter=sandbox` only when the security-center process is not running. The default calls the security center. Set `api-configuration.domain-platform.security-center.base-path` (default `http://localhost:8085`).

## Caller-owned activation and native authority

`POST /api/v1/cards/{cardId}/activate` requires the caller's stable UUID `X-Idempotency-Key` and the actual issued `activationProof`. Experience delegates through the complete generated Domain SDK. It preserves the action, owner state and historical activation timestamp; it does not derive a proof from the card ID or manufacture an activation receipt.

Enable `firefly.cards.activation.delegation.enabled` only with `firefly.security.resource-server.strict-policy=true`. Configure the native receiver's issuer, JWKS, audience and tenant projection, and the delegation settings' exact incoming client, target audience, delegating client, registration, tenant and canonical actor/party claim mappings. The OAuth client registration must use token exchange with the same issuer and a deployment-provided client secret. The factory checks current native identity and scoped policy before and after exchange, verifies the exchanged token and rechecks authority before the SDK call. Never persist bearer tokens or replace the original action after an uncertain result.

A deployment can provide a `PolicyDecisionPort`, or explicitly configure the published OPA adapter through `firefly.cards.authorization.opa.enabled`, `endpoint`, `decision-path` and `evaluation-path`. HTTPS origins and exact document paths are required. Loopback HTTP requires explicit qualification opt-in. Missing or unavailable policy fails closed; the adapter has a two-second deadline and bounded response buffering. Core additionally requires engine-provided policy provenance for its current ownership evaluation.

Timeouts and transport I/O errors return HTTP 503 with an unknown outcome. Owner 5xx responses remain unknown; explicit business refusals remain distinct. No error creates a receipt, repeats the POST or chooses a new action. Read-only original-action recovery is currently available at Core; its Domain and Experience adoption remains open.

Issuance remains unavailable until the Core managed enrollment/provider transition actually issues and persists an instrument and its proof. The removed successful issuance/activation simulations are not replacements for that integration. The default external issuer adapter executes nothing. Controlled native transport qualification does not establish a live issuer or a completed issuance/activation journey.
