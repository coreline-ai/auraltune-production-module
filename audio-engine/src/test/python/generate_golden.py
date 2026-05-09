#!/usr/bin/env python3
# generate_golden.py
#
# P3-B — Generate the truly-independent golden frequency-response fixture
# for AuralTuneEQEngine using scipy.signal.
#
# Why a separate reference:
#   The C++ analytical reference in GoldenResponseTest.cpp re-derives RBJ
#   coefficients with the same formulas the engine uses. That catches inner-loop
#   bugs but NOT a coefficient-derivation bug (a typo in both copies would slip
#   through). scipy.signal is a third-party reference, so any divergence here
#   means our RBJ math is wrong.
#
# Usage:
#   python3 generate_golden.py > ../cpp/golden/hd600_oratory1990.json
#
# Schema:
#   {
#     "fixture": "hd600_oratory1990",
#     "filters": [{type, frequency, gainDB, q}, ...],
#     "profileOptimizedRate": 48000.0,
#     "rates": {
#       "48000":  [[freq, magDb], ...],
#       "44100":  ...,
#       "96000":  ...
#     },
#     "tolerance_db": 0.5
#   }
#
# The C++ test reads this file (or a compiled-in copy) and compares each
# (rate, freq) point to the engine's measured impulse-DFT magnitude.

import json
import math
import sys

try:
    import numpy as np
    from scipy import signal
except ImportError as e:
    sys.stderr.write(f"ERROR: scipy/numpy required. {e}\n")
    sys.exit(1)


# HD 600 oratory1990-shaped fixture (matches dev-plan Phase 7 line 455).
FIXTURE = {
    "name": "hd600_oratory1990",
    "filters": [
        {"type": "low_shelf",  "frequency": 105.0,  "gain_db":  2.5, "q": 0.7},
        {"type": "peaking",    "frequency": 3000.0, "gain_db": -2.0, "q": 1.0},
        {"type": "high_shelf", "frequency": 8000.0, "gain_db":  1.5, "q": 0.7},
    ],
    "profile_optimized_rate": 48000.0,
}

TEST_RATES = [44100.0, 48000.0, 96000.0]

TEST_FREQUENCIES_HZ = [
    20, 30, 50, 80, 105, 150, 250, 500, 1000, 2000, 3000, 4000,
    5000, 6000, 8000, 10000, 12000, 15000, 18000, 20000,
]

TOLERANCE_DB = 0.5


def rbj_peaking(f0, gain_db, q, fs):
    A = 10.0 ** (gain_db / 40.0)
    w0 = 2.0 * math.pi * f0 / fs
    alpha = math.sin(w0) / (2.0 * q)
    cosw = math.cos(w0)

    b0 = 1.0 + alpha * A
    b1 = -2.0 * cosw
    b2 = 1.0 - alpha * A
    a0 = 1.0 + alpha / A
    a1 = -2.0 * cosw
    a2 = 1.0 - alpha / A
    return [b0/a0, b1/a0, b2/a0], [1.0, a1/a0, a2/a0]


def rbj_low_shelf(f0, gain_db, q, fs):
    A = 10.0 ** (gain_db / 40.0)
    w0 = 2.0 * math.pi * f0 / fs
    alpha = math.sin(w0) / (2.0 * q)
    cosw = math.cos(w0)
    sa = 2.0 * math.sqrt(A) * alpha

    b0 = A * ((A + 1.0) - (A - 1.0) * cosw + sa)
    b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cosw)
    b2 = A * ((A + 1.0) - (A - 1.0) * cosw - sa)
    a0 = (A + 1.0) + (A - 1.0) * cosw + sa
    a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cosw)
    a2 = (A + 1.0) + (A - 1.0) * cosw - sa
    return [b0/a0, b1/a0, b2/a0], [1.0, a1/a0, a2/a0]


def rbj_high_shelf(f0, gain_db, q, fs):
    A = 10.0 ** (gain_db / 40.0)
    w0 = 2.0 * math.pi * f0 / fs
    alpha = math.sin(w0) / (2.0 * q)
    cosw = math.cos(w0)
    sa = 2.0 * math.sqrt(A) * alpha

    b0 = A * ((A + 1.0) + (A - 1.0) * cosw + sa)
    b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosw)
    b2 = A * ((A + 1.0) + (A - 1.0) * cosw - sa)
    a0 = (A + 1.0) - (A - 1.0) * cosw + sa
    a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cosw)
    a2 = (A + 1.0) - (A - 1.0) * cosw - sa
    return [b0/a0, b1/a0, b2/a0], [1.0, a1/a0, a2/a0]


def pre_warp(f, source_rate, target_rate):
    f_analog = (source_rate / math.pi) * math.tan(math.pi * f / source_rate)
    return (target_rate / math.pi) * math.atan(math.pi * f_analog / target_rate)


COEFF_BUILDERS = {
    "peaking": rbj_peaking,
    "low_shelf": rbj_low_shelf,
    "high_shelf": rbj_high_shelf,
}


def build_cascade(filters, engine_rate, profile_rate):
    """Returns list of (b, a) tuples for scipy.signal.freqz."""
    sections = []
    for f in filters:
        eff_f = f["frequency"]
        if abs(profile_rate - engine_rate) > 1.0:
            eff_f = pre_warp(eff_f, profile_rate, engine_rate)
        if eff_f <= 0.0 or eff_f >= engine_rate / 2.0:
            sections.append(([1.0, 0.0, 0.0], [1.0, 0.0, 0.0]))  # unity passthrough
            continue
        b, a = COEFF_BUILDERS[f["type"]](eff_f, f["gain_db"], f["q"], engine_rate)
        sections.append((b, a))
    return sections


def cascade_db(sections, freq_hz, fs):
    """Sum the dB magnitude of each section at freq_hz."""
    w_target = 2.0 * math.pi * freq_hz / fs
    total_db = 0.0
    for (b, a) in sections:
        # Use scipy.signal.freqz at exactly w_target for an INDEPENDENT
        # numerical implementation. (This is the whole point of the fixture.)
        w, h = signal.freqz(b, a, worN=[w_target])
        mag = abs(h[0])
        total_db += 20.0 * math.log10(max(mag, 1e-30))
    return total_db


def main():
    out = {
        "fixture": FIXTURE["name"],
        "filters": FIXTURE["filters"],
        "profileOptimizedRate": FIXTURE["profile_optimized_rate"],
        "rates": {},
        "tolerance_db": TOLERANCE_DB,
        "comment": (
            "Generated by audio-engine/src/test/python/generate_golden.py via "
            "scipy.signal.freqz. Per-section magnitudes summed in dB. "
            "Independent of the C++ engine's TDF2 inner loop."
        ),
    }
    profile_rate = FIXTURE["profile_optimized_rate"]
    for fs in TEST_RATES:
        sections = build_cascade(FIXTURE["filters"], fs, profile_rate)
        points = []
        for f in TEST_FREQUENCIES_HZ:
            if f >= fs / 2.0:
                continue
            db = cascade_db(sections, float(f), fs)
            points.append([float(f), float(db)])
        out["rates"][str(int(fs))] = points

    json.dump(out, sys.stdout, indent=2)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
