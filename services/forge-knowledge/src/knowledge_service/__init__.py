from importlib.metadata import PackageNotFoundError, metadata, version

__distribution_name__ = "knowledge-service"
__application_name__ = "forge-knowledge"

try:
    __version__ = version(__distribution_name__)
    __package_name__ = metadata(__distribution_name__)["Name"]
except PackageNotFoundError:  # pragma: no cover - editable metadata is available in runtime/test environments.
    __version__ = "0+unknown"
    __package_name__ = __distribution_name__
