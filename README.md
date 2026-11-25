# backend

Java 21 · Spring Boot 4.2 · PostgreSQL 17 · Flyway · springdoc-openapi

## 실행

```bash
./gradlew bootRun   # Docker 필요: compose.yaml의 Postgres를 자동으로 띄움
./gradlew build     # 컴파일 + 테스트
```

- API 명세: http://localhost:8080/v3/api-docs (Swagger UI: `/swagger-ui/index.html`)
- DB 스키마는 `src/main/resources/db/migration`의 Flyway 마이그레이션으로만 변경
- 환경변수: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `CORS_ALLOWED_ORIGINS`
