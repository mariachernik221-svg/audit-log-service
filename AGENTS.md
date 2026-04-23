## Project map

Приложение Audit log service - это внутренний сервис, который принимает аудит-события от других сервисов компании и хранит их immutable. Нужен для compliance, security, observability. Читают его compliance-офицеры, SRE, security-аналитики.

Функциональность:
- приём одного события: POST /audit-events
- поиск событий по actor / resource / time range
- retention policy (хранение N дней, потом archival)
- (опционально, стрейч) tamper-evidence через hash chain

Поля события:
- timestamp — когда произошло (ставится сервером)
- actor — кто инициировал (user id / service account)
- action — что сделал (resource.updated, user.login и т.п.)
- resource — над чем (project:42, invoice:777)
- outcome — результат (success / denied / error)
- context — произвольный JSON с деталями

## Invariants

1. append-only: нет UPDATE, нет DELETE
2. timestamp ставится только сервером
3. actor обязателен
4. не добавлять новые библиотеки без сильной необходимости

## Architectual rules

Технологические требования:
- Java 21, Spring Boot 3, Gradle Kotlin DSL
- Postgres через Flyway-миграции
- Testcontainers для интеграционных тестов

При написании нового кода необходимо сразу покрывать его тестами и проверять, что они выполняются.