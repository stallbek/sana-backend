# Contributing

Use Java 17 and preserve the existing controller/service/repository separation.
Keep changes focused, validate client and model input, and enforce ownership on the server.
Add focused regression tests for behavior changes and run `./mvnw clean verify`.
Run the secret and dependency checks described in the README before submitting a pull request.

Keep credentials in environment variables. Never commit `.env`, databases, customer data,
provider tokens, logs, or deployment configuration. Use synthetic examples and review your
staged diff before committing. CI and CodeQL must pass before merging.

Database migrations and public API changes need an explicit explanation in the pull request.
