## About project

The Audit Log Service application is an internal service that receives audit events from other company services and
stores them immutably. Once saved, event can't be changed or deleted anymore. It is required for compliance, security,
and observability. It is used by compliance officers, SREs, and security analysts.

## Project Map

* `api`: REST layer. Defines HTTP endpoints, request/response DTOs, and validation. No business logic.
* `domain`: core domain model, contains entities.
* `service`: application layer. Orchestrates use cases, coordinates domain logic, and handles transactions.
* `repository`: data access layer. Responsible for persistence and retrieval of entities from PostgreSQL.

## Invariants

* events append-only: no updates, no deletes
* event timestamp is set only by the server
* actor is required for event

## Architectural Rules

* Dependency rules:
    * `domain` just represents domain model, so it doesn't depend on other modules
    * `api` may depend on `domain` and `service`
    * `service` may depend on `domain` and `repository`
    * `repository` may depend only on `domain`

* All logic related to REST should be placed in `api` (conversion of request/response DTOs to domain entities,
  validation of request parameters, etc.)
* All business logic should be placed in `service` (business logic validations, processing of entities, applying
  business rules, etc.)
* All logic related to storing should be placed in `repository` (SQL queries building, interaction with database, etc.)
* All code should be covered with tests (unit or integration)
* Do not add new libraries without strong necessity

## Feedback Loop

* After any code change, the agent must run relevant unit and integration tests.
* If changes affect persistence or database schema (including Flyway migrations), the agent must verify that migrations
  apply successfully on a clean database and do not break existing data access.

## Working Principles

Agents in this workspace should follow next principles when they are working on this project:

* The agent must not introduce changes that break existing functionality, data integrity, or system stability,
  either through action or inaction.
* The agent must follow human instructions and project conventions, unless they conflict with the first principle.
* The agent should preserve and improve the codebase: keep changes minimal, maintainable, and consistent with
  the existing architecture, unless this conflicts with the first or second principles.
