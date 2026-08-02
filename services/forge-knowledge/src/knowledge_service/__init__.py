from importlib.metadata import PackageNotFoundError, metadata, version

__distribution_name__ = "knowledge-service"
__application_name__ = "forge-knowledge"

try:
    __version__ = version(__distribution_name__)
    __package_name__ = metadata(__distribution_name__)["Name"]
except PackageNotFoundError as exc:  # pragma: no cover - startup/runtime environments provide package metadata.
    raise RuntimeError(f"Package metadata is required for {__distribution_name__}") from exc
