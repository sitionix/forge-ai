from setuptools import find_packages, setup


setup(
    name="jarvis-agent",
    version="0.1.0",
    description="Local safe Jarvis text-command action service",
    package_dir={"": "src"},
    packages=find_packages("src"),
    python_requires=">=3.8",
    install_requires=[
        "fastapi>=0.95.0",
        "uvicorn[standard]>=0.22.0",
        "pydantic>=1.10.0,<2.0.0",
        "httpx>=0.27.0",
        "pyyaml>=6.0.1",
    ],
    extras_require={"test": ["pytest>=8.0.0"]},
)
