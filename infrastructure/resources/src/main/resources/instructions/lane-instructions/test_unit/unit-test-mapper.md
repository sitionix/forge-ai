# Test Unit Mapper Rules

Read this file only when affected source files include mappers or mapping helpers.

Mapper test shape:

1. build source object;
2. build expected target object;
3. call mapper;
4. compare actual and expected.

- Instantiate mapper implementation directly (usually in `@BeforeEach`).
- Mock nested mapper dependencies only when the mapper depends on them.
- Prefer full object comparison.
- Use recursive comparison only when equality is unavailable or not meaningful.
- Avoid long manual field-by-field assertions unless necessary.
