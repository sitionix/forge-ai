import pytest

from knowledge_service.path_glob import PathGlobMatcher


GENERATED_JAVA = PathGlobMatcher(["**/target/generated-sources/**/*.java"])


@pytest.mark.parametrize(
    "path",
    [
        "target/generated-sources/Foo.java",
        "target/generated-sources/src/main/java/Foo.java",
        "api-rest/target/generated-sources/Foo.java",
        "api-rest/target/generated-sources/openapi/src/main/java/Foo.java",
        "modules/client/target/generated-sources/annotations/Foo.java",
        "deeply/nested/module/target/generated-sources/x/y/Foo.java",
        "api-rest\\target\\generated-sources\\openapi\\src\\main\\java\\Foo.java",
    ],
)
def test_generated_java_glob_matches_root_and_nested_modules(path):
    assert GENERATED_JAVA.matches(path)


@pytest.mark.parametrize(
    "path",
    [
        "target/classes/Foo.java",
        "target/test-classes/Foo.java",
        "src/main/java/Foo.java",
        "api-rest/generated-sources/Foo.java",
        "api-rest/target/generated-sources/Foo.class",
    ],
)
def test_generated_java_glob_rejects_unrelated_target_paths(path):
    assert not GENERATED_JAVA.matches(path)


def test_star_does_not_cross_path_segments():
    matcher = PathGlobMatcher(["src/*/java/*.java"])

    assert matcher.matches("src/main/java/Foo.java")
    assert not matcher.matches("src/main/generated/java/Foo.java")
    assert not matcher.matches("src/main/java/com/example/Foo.java")


def test_question_mark_matches_exactly_one_segment_character():
    matcher = PathGlobMatcher(["src/?/Foo.java"])

    assert matcher.matches("src/a/Foo.java")
    assert not matcher.matches("src/ab/Foo.java")
    assert not matcher.matches("src//Foo.java")


def test_double_star_matches_zero_one_or_many_directories():
    matcher = PathGlobMatcher(["src/**/Foo.java"])

    assert matcher.matches("src/Foo.java")
    assert matcher.matches("src/main/Foo.java")
    assert matcher.matches("src/main/java/com/example/Foo.java")


def test_root_anchored_pattern_does_not_match_module_prefix():
    matcher = PathGlobMatcher(["target/generated-sources/**/*.java"])

    assert matcher.matches("target/generated-sources/Foo.java")
    assert not matcher.matches("api-rest/target/generated-sources/Foo.java")


def test_leading_double_star_matches_root_and_nested_paths():
    matcher = PathGlobMatcher(["**/target/generated-sources/**/*.java"])

    assert matcher.matches("target/generated-sources/Foo.java")
    assert matcher.matches("api-rest/target/generated-sources/Foo.java")


@pytest.mark.parametrize(
    ("pattern", "positive"),
    [
        ("**/generated/**/models/*.kt", "api/build/generated/src/models/User.kt"),
        ("**/build/generated/**/*.java", "module/build/generated/sources/Mapper.java"),
        ("src/*/java/**/*.java", "src/main/java/com/example/App.java"),
        ("**/README*", "README.md"),
        ("**/README*", "docs/README.adoc"),
        ("**/pom.xml", "pom.xml"),
        ("**/pom.xml", "api-rest/pom.xml"),
    ],
)
def test_unrelated_configured_patterns_work_without_code_changes(pattern, positive):
    assert PathGlobMatcher([pattern]).matches(positive)
