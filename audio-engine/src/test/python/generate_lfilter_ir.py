#!/usr/bin/env python3
# generate_lfilter_ir.py
#
# P2-2 — Generate a TRULY independent reference impulse response by running
# the cascade through scipy.signal.lfilter (Direct Form II in compiled Fortran),
# which is independent from our C++ TDF2 inner loop.
#
# This sits one level deeper than generate_golden.py:
#   generate_golden.py     → magnitude vs frequency via scipy.signal.freqz
#                             (analytical transfer function)
#   generate_lfilter_ir.py → time-domain impulse response via scipy.signal.lfilter
#                             (actual difference equation, different topology)
#
# A TDF2 implementation bug in our C++ engine (e.g., delay state shuffle wrong)
# is INVISIBLE to freqz but visible to lfilter, because freqz never runs the
# difference equation.
#
# Output (stdout): a binary float64 array of shape (n_rates, IR_LEN) preceded
# by a tiny ASCII header so the C++ test can deserialize without bringing in
# a JSON dep:
#
#   AURALTUNE_LFILTER_IR\n
#   <num_rates>\n
#   <ir_len>\n
#   <rate_0> <rate_1> ... <rate_{n-1}>\n
#   <8 * num_rates * ir_len bytes of float64 little-endian, row-major>
#
# Usage:
#   python3 generate_lfilter_ir.py > ../cpp/golden/hd600_lfilter_ir.bin

import math
import struct
import sys

try:
    import numpy as np
    from scipy import signal
except ImportError as e:
    sys.stderr.write(f"ERROR: scipy/numpy required. {e}\n")
    sys.exit(1)


FIXTURE = [
    {"type": "low_shelf",  "frequency": 105.0,  "gain_db":  2.5, "q": 0.7},
    {"type": "peaking",    "frequency": 3000.0, "gain_db": -2.0, "q": 1.0},
    {"type": "high_shelf", "frequency": 8000.0, "gain_db":  1.5, "q": 0.7},
]
PROFILE_RATE = 48000.0
TEST_RATES = [44100.0, 48000.0, 96000.0]
IR_LEN = 2048   # short enough that the test deserializes quickly, long enough
                # that the IR has decayed to numerical noise on all sample rates.


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


def cascade_ir(filters, fs, profile_rate, n):
    """Run a length-n impulse through the cascade with scipy.signal.lfilter."""
    x = np.zeros(n, dtype=np.float64)
    x[0] = 1.0
    y = x.copy()
    for f in filters:
        eff_f = f["frequency"]
        if abs(profile_rate - fs) > 1.0:
            eff_f = pre_warp(eff_f, profile_rate, fs)
        if eff_f <= 0.0 or eff_f >= fs / 2.0:
            continue  # unity passthrough
        b, a = COEFF_BUILDERS[f["type"]](eff_f, f["gain_db"], f["q"], fs)
        # IMPORTANT: we explicitly use scipy.signal.lfilter here. This runs the
        # difference equation on actual samples in compiled native code (DF2 by
        # default), which is the truly-independent path vs our C++ TDF2.
        y = signal.lfilter(b, a, y)
    return y


def main():
    out = sys.stdout.buffer
    out.write(b"AURALTUNE_LFILTER_IR\n")
    out.write(f"{len(TEST_RATES)}\n".encode())
    out.write(f"{IR_LEN}\n".encode())
    out.write((" ".join(str(int(r)) for r in TEST_RATES) + "\n").encode())
    for fs in TEST_RATES:
        ir = cascade_ir(FIXTURE, fs, PROFILE_RATE, IR_LEN)
        out.write(struct.pack(f"<{IR_LEN}d", *ir))


if __name__ == "__main__":
    main()
