# Test Unit Usecase/Service Rules

Read this file only when affected source files include use cases, services, validators, security/application components, or domain/application behavior classes.

- Mock external collaborators.
- Use real request/command/DTO/domain/result data when field values affect assertions.
- Call SUT directly.
- Assert returned result or thrown exception.
- Verify behavior-relevant interactions.
- Avoid private-implementation-detail assertions.

Typical mocks when relevant:

- repositories;
- clients;
- producers/consumers;
- token/hash/authentication services;
- clock/id providers;
- mapper dependencies.
