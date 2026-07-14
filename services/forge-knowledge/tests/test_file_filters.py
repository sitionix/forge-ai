from knowledge_service.file_filters import should_include_file


def test_file_filters_include_and_exclude_patterns():
    assert should_include_file("src/App.java", ["**/*.java"], [])
    assert not should_include_file("target/App.java", ["**/*.java"], ["target/**"])
    assert not should_include_file("module/target/App.java", ["**/*.java"], ["target/**", "**/target/**"])
    assert not should_include_file(".git/config", ["**/*"], [".git/**"])
    assert not should_include_file(".idea/workspace.xml", ["**/*.xml"], [])
    assert not should_include_file("node_modules/a.js", ["**/*.js"], ["node_modules/**"])
    assert not should_include_file(".env", ["**/*"], ["**/.env"])


def test_exclude_exception_only_reincludes_matching_paths_before_include_checks():
    exceptions = ["services/api/target/generated-sources/annotations/**/*.java"]

    assert should_include_file(
        "services/api/target/generated-sources/annotations/com/example/GeneratedApi.java",
        ["**/*.java"],
        ["**/target/**"],
        exceptions,
    )
    assert not should_include_file(
        "services/api/target/generated-sources/other/com/example/Other.java",
        ["**/*.java"],
        ["**/target/**"],
        exceptions,
    )
    assert not should_include_file(
        "services/api/target/generated-sources/annotations/com/example/GeneratedApi.java",
        ["**/*.kt"],
        ["**/target/**"],
        exceptions,
    )
