"""
Audio providers enabled for the Android build.
"""

from spotdl.providers.audio.base import (
    ISRC_REGEX,
    AudioProvider,
    AudioProviderError,
    YTDLLogger,
)
from spotdl.providers.audio.youtube import YouTube
from spotdl.providers.audio.ytmusic import YouTubeMusic


class _UnavailableProvider(AudioProvider):
    SUPPORTS_ISRC = False
    GET_RESULTS_OPTS = [{}]

    def get_results(self, *_args, **_kwargs):
        raise AudioProviderError(f"{self.__class__.__name__} is not bundled in the Android build")


class SoundCloud(_UnavailableProvider):
    pass


class BandCamp(_UnavailableProvider):
    pass


class Piped(_UnavailableProvider):
    pass


__all__ = [
    "YouTube",
    "YouTubeMusic",
    "SoundCloud",
    "BandCamp",
    "Piped",
    "AudioProvider",
    "AudioProviderError",
    "YTDLLogger",
    "ISRC_REGEX",
]
