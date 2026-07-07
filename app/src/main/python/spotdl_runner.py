from __future__ import annotations

import contextlib
import io
import json
import logging
import os
import sys
import traceback
from pathlib import Path


MEDIA_EXTENSIONS = {".mp3", ".m4a", ".opus", ".ogg", ".flac", ".wav"}


def _prepare_env(files_dir: str) -> Path:
    home = Path(files_dir)
    home.mkdir(parents=True, exist_ok=True)
    os.environ["HOME"] = str(home)
    os.environ["XDG_CACHE_HOME"] = str(home / "cache")
    os.environ["XDG_CONFIG_HOME"] = str(home / "config")
    os.environ["SPOTDL_HOME"] = str(home / "spotdl")
    os.environ["SPOTDL_PATH"] = str(home / "spotdl")
    for key in ("cache", "config", "spotdl", "logs", "temp"):
        (home / key).mkdir(parents=True, exist_ok=True)
    return home


def _existing_executable(path: str | None) -> str:
    if not path:
        return ""
    candidate = Path(path)
    if candidate.is_file() and os.access(candidate, os.X_OK):
        return str(candidate)
    return ""


def _prepend_env_path(name: str, value: str) -> None:
    if not value:
        return
    current = os.environ.get(name, "")
    parts = [part for part in current.split(":") if part]
    if value in parts:
        return
    os.environ[name] = value + ((":" + current) if current else "")


def _set_native_library_path(ffmpeg: str) -> None:
    if ffmpeg:
        _prepend_env_path("LD_LIBRARY_PATH", str(Path(ffmpeg).parent))


def _default_ffmpeg(files_dir: str, explicit: str | None = None) -> str:
    explicit_path = _existing_executable(explicit)
    if explicit_path:
        _set_native_library_path(explicit_path)
        return explicit_path
    home = Path(files_dir)
    for candidate in (
        home / "bin" / "ffmpeg",
        home / "ffmpeg",
        home / "spotdl" / "ffmpeg",
    ):
        candidate_path = _existing_executable(str(candidate))
        if candidate_path:
            _set_native_library_path(candidate_path)
            return candidate_path
    return ""


def _tail(path: Path, lines: int = 120) -> str:
    if not path.exists():
        return ""
    data = path.read_text(errors="replace").splitlines()
    return "\n".join(data[-lines:])


def _scan_media(output_dir: Path) -> list[str]:
    if not output_dir.exists():
        return []
    files: list[str] = []
    for path in sorted(output_dir.rglob("*")):
        if path.is_file() and path.suffix.lower() in MEDIA_EXTENSIONS:
            files.append(str(path))
    return files


def health(files_dir: str, ffmpeg_path: str = "") -> str:
    _prepare_env(files_dir)
    result: dict[str, object] = {
        "python": sys.version.split()[0],
        "home": files_dir,
        "spotdl_available": False,
        "spotdl_version": "",
        "ffmpeg_path": _default_ffmpeg(files_dir, ffmpeg_path),
        "ffmpeg_available": False,
        "error": "",
    }
    try:
        from spotdl._version import __version__  # pylint: disable=import-outside-toplevel
        from spotdl.utils.ffmpeg import is_ffmpeg_installed  # pylint: disable=import-outside-toplevel

        result["spotdl_available"] = True
        result["spotdl_version"] = __version__
        ffmpeg = str(result["ffmpeg_path"] or "ffmpeg")
        result["ffmpeg_available"] = bool(result["ffmpeg_path"]) and is_ffmpeg_installed(ffmpeg)
    except Exception as exc:  # pragma: no cover - reported to Android UI
        result["error"] = "".join(traceback.format_exception_only(type(exc), exc)).strip()
    return json.dumps(result)


def search_playlists(files_dir: str, query: str, limit: int = 10) -> str:
    _prepare_env(files_dir)
    clean_query = (query or "").strip()
    try:
        max_results = max(1, min(int(limit), 20))
    except (TypeError, ValueError):
        max_results = 10

    if not clean_query:
        return json.dumps({"ok": False, "query": clean_query, "error": "Enter a Spotify playlist search term", "playlists": []})

    try:
        from spotdl.utils.config import SPOTIFY_OPTIONS  # pylint: disable=import-outside-toplevel
        from spotdl.utils.spotify import SpotifyClient  # pylint: disable=import-outside-toplevel

        SpotifyClient._instance = None  # pylint: disable=protected-access
        SpotifyClient._use_official_api = False  # pylint: disable=protected-access
        spotify_settings = dict(SPOTIFY_OPTIONS)
        spotify_settings.update({"headless": True, "no_cache": False, "use_cache_file": False, "use_official_api": True})
        SpotifyClient.init(**spotify_settings)
        spotify_client = SpotifyClient()

        raw_results = spotify_client.search(q=clean_query, type="playlist", limit=max_results)
        items = (raw_results or {}).get("playlists", {}).get("items", [])
        playlists = []
        for item in items:
            if not isinstance(item, dict) or not item.get("id"):
                continue
            owner = item.get("owner") or {}
            tracks = item.get("tracks") or {}
            images = item.get("images") or []
            external_urls = item.get("external_urls") or {}
            playlists.append(
                {
                    "name": item.get("name") or "Untitled playlist",
                    "url": external_urls.get("spotify") or f"https://open.spotify.com/playlist/{item.get('id')}",
                    "description": item.get("description") or "",
                    "owner": owner.get("display_name") or owner.get("id") or "Spotify",
                    "tracks": tracks.get("total", 0),
                    "cover_url": images[0].get("url") if images and isinstance(images[0], dict) else "",
                }
            )

        return json.dumps({"ok": True, "query": clean_query, "error": "", "playlists": playlists})
    except Exception:  # pragma: no cover - reported to Android UI
        error = traceback.format_exc()
        if "/playlist/" in clean_query or clean_query.startswith("spotify:playlist:"):
            return json.dumps({
                "ok": True,
                "query": clean_query,
                "error": error,
                "playlists": [
                    {
                        "name": clean_query,
                        "url": clean_query,
                        "description": "Direct playlist URL fallback",
                        "owner": "Spotify",
                        "tracks": 0,
                        "cover_url": "",
                    }
                ],
            })
        return json.dumps({"ok": False, "query": clean_query, "error": error, "playlists": []})


def download(items_json: str, files_dir: str, output_dir: str, output_format: str, ffmpeg_path: str = "") -> str:
    home = _prepare_env(files_dir)
    output_root = Path(output_dir)
    output_root.mkdir(parents=True, exist_ok=True)
    logs = home / "logs"
    logs.mkdir(parents=True, exist_ok=True)
    log_path = logs / "latest.log"

    try:
        items = json.loads(items_json)
    except json.JSONDecodeError:
        items = []
    items = [str(item).strip() for item in items if str(item).strip()]

    if not items:
        payload = {"ok": False, "returncode": 2, "error": "No Spotify URL or search query provided", "files": []}
        return json.dumps(payload)

    ffmpeg = _default_ffmpeg(files_dir, ffmpeg_path)
    output_template = str(output_root / "{list-name}" / "{list-position} - {artists} - {title}.{output-ext}")
    returncode = 0
    error = ""
    selected_files: list[str] = []

    try:
        with log_path.open("w", encoding="utf-8", errors="replace") as log_file:
            with contextlib.redirect_stdout(log_file), contextlib.redirect_stderr(log_file):
                logging.basicConfig(stream=log_file, level=logging.INFO, force=True)
                print("items=" + json.dumps(items))
                print("output=" + output_template)
                print("ffmpeg=" + (ffmpeg or "missing"))

                if not ffmpeg:
                    raise RuntimeError(
                        "FFmpeg is unavailable. The bundled native binary was not found "
                        "or could not be executed."
                    )

                from spotdl.download.downloader import Downloader  # pylint: disable=import-outside-toplevel
                from spotdl.utils.config import DOWNLOADER_OPTIONS, SPOTIFY_OPTIONS  # pylint: disable=import-outside-toplevel
                from spotdl.utils.search import parse_query  # pylint: disable=import-outside-toplevel
                from spotdl.utils.spotify import SpotifyClient  # pylint: disable=import-outside-toplevel

                SpotifyClient._instance = None  # pylint: disable=protected-access
                SpotifyClient._use_official_api = False  # pylint: disable=protected-access
                spotify_settings = dict(SPOTIFY_OPTIONS)
                spotify_settings.update({"headless": True, "no_cache": False, "use_cache_file": False})
                SpotifyClient.init(**spotify_settings)

                downloader_settings = dict(DOWNLOADER_OPTIONS)
                downloader_settings.update(
                    {
                        "audio_providers": ["youtube-music", "youtube"],
                        "lyrics_providers": [],
                        "threads": 1,
                        "format": output_format or "mp3",
                        "output": output_template,
                        "overwrite": "skip",
                        "print_errors": True,
                        "simple_tui": False,
                        "ffmpeg": ffmpeg,
                        "bitrate": "128k",
                        "scan_for_songs": False,
                        "generate_lrc": False,
                        "skip_album_art": False,
                    }
                )

                songs = parse_query(
                    query=items,
                    threads=1,
                    use_ytm_data=False,
                    playlist_numbering=False,
                    album_type=None,
                    playlist_retain_track_cover=False,
                )
                print(f"songs={len(songs)}")
                downloader = Downloader(downloader_settings)
                try:
                    results = downloader.download_multiple_songs(songs)
                finally:
                    downloader.progress_handler.close()

                selected_files = [str(path) for _song, path in results if path is not None and Path(path).is_file()]
                print(f"downloaded={len(selected_files)}")
    except Exception:  # pragma: no cover - reported to Android UI
        returncode = 1
        error = traceback.format_exc()
        with log_path.open("a", encoding="utf-8", errors="replace") as log_file:
            log_file.write("\n" + error)

    files = selected_files or _scan_media(output_root)
    payload = {
        "ok": returncode == 0,
        "returncode": returncode,
        "error": error,
        "log_path": str(log_path),
        "log_tail": _tail(log_path),
        "output_dir": str(output_root),
        "files": files,
        "ffmpeg_path": ffmpeg,
    }
    return json.dumps(payload)
