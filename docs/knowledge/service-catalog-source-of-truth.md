# Service Catalog Source Of Truth

Knowledge derives sources from `services.yaml`. Local config only points to the catalog path and workspace root.

Knowledge must not duplicate labels, paths, tags, or groups in a separate service list. Service paths are resolved as:

```text
catalog.workspace_root + services.<serviceId>.path
```

Missing source roots are reported as `rootExists: false` and are not fatal. Absolute service paths are rejected by validation.
