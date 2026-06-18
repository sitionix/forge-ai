from knowledge_service.path_security import is_under_root, safe_relative_path


def test_path_security_keeps_paths_under_root(tmp_path):
    root = tmp_path / "root"
    root.mkdir()
    file = root / "a.txt"
    file.write_text("x", encoding="utf-8")

    assert is_under_root(file, root)
    assert safe_relative_path(file, root) == "a.txt"
    assert not is_under_root(tmp_path / "outside.txt", root)
