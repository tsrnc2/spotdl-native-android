"""
Lyrics providers are not bundled in the Android build.
"""

from spotdl.providers.lyrics.base import LyricsProvider


class _UnavailableLyricsProvider(LyricsProvider):
    def get_lyrics(self, *_args, **_kwargs):
        return None


class AzLyrics(_UnavailableLyricsProvider):
    pass


class Genius(_UnavailableLyricsProvider):
    pass


class MusixMatch(_UnavailableLyricsProvider):
    pass


class Synced(_UnavailableLyricsProvider):
    pass


__all__ = ["AzLyrics", "Genius", "MusixMatch", "Synced", "LyricsProvider"]
