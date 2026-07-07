from __future__ import annotations

from difflib import SequenceMatcher


def _ratio(a: object, b: object) -> float:
    return SequenceMatcher(None, str(a or ""), str(b or "")).ratio() * 100.0


def ratio(a: object, b: object, *_args, **_kwargs) -> float:
    return _ratio(a, b)


def partial_ratio(a: object, b: object, *_args, **_kwargs) -> float:
    left = str(a or "")
    right = str(b or "")
    if not left or not right:
        return 0.0
    if len(left) > len(right):
        left, right = right, left
    best = 0.0
    width = len(left)
    for index in range(0, max(len(right) - width + 1, 1)):
        best = max(best, _ratio(left, right[index : index + width]))
    return best


def token_sort_ratio(a: object, b: object, *_args, **_kwargs) -> float:
    return _ratio(_sort_tokens(a), _sort_tokens(b))


def token_set_ratio(a: object, b: object, *_args, **_kwargs) -> float:
    left = set(str(a or "").split())
    right = set(str(b or "").split())
    if not left or not right:
        return 0.0
    common = " ".join(sorted(left & right))
    left_only = " ".join(sorted(left - right))
    right_only = " ".join(sorted(right - left))
    return max(
        _ratio(common, common + " " + left_only),
        _ratio(common, common + " " + right_only),
        _ratio(common + " " + left_only, common + " " + right_only),
    )


def WRatio(a: object, b: object, *_args, **_kwargs) -> float:
    return max(ratio(a, b), partial_ratio(a, b), token_sort_ratio(a, b), token_set_ratio(a, b))


def QRatio(a: object, b: object, *_args, **_kwargs) -> float:
    return ratio(a, b)


def _sort_tokens(value: object) -> str:
    return " ".join(sorted(str(value or "").split()))
