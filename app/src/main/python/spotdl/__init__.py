"""
Android-embedded spotDL package entry.

The upstream package imports the CLI and web UI at package import time. That
pulls in FastAPI/Pydantic, including native wheels that aren't available in the
Chaquopy Android target. The native app uses the downloader modules directly,
so the package init stays intentionally small.
"""

from spotdl._version import __version__

__all__ = ["__version__"]
