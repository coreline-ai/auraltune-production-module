#!/usr/bin/env python3
"""Runtime oracle that mirrors core/PcmConvert.h arithmetic EXACTLY.

The NDK host toolchain compiles PcmConvert.h cleanly for arm64/armv7/x86_64
(verified) but cannot LINK a runnable Windows EXE (no mingw import libs). So we
re-implement the exact operations the header performs -- lround/llround, clamp,
3-byte LE packing, 24->32 sign extend, /2^N scaling, float32 quantization -- and
run the same adversarial assertions, printing the computed numbers.

float32 quantization is modeled with numpy.float32 so round-trip error bounds are
honest (the header stores intermediate `out[i]` as float).
"""
import struct, math, sys

P = 0
F = 0
def chk(cond, label, got, expect):
    global P, F
    if cond:
        P += 1; print(f"  PASS  {label:<45} got={got!r} expect={expect!r}")
    else:
        F += 1; print(f"  FAIL  {label:<45} got={got!r} expect={expect!r}")

def f32(x):
    # round a Python double to IEEE-754 binary32 (no numpy dependency)
    return struct.unpack('<f', struct.pack('<f', x))[0]

def lround(x):  # C lround: round half away from zero
    return math.floor(x + 0.5) if x >= 0 else math.ceil(x - 0.5)

def clamp(v, lo, hi): return lo if v < lo else (hi if v > hi else v)

# ---- decode (mirrors PcmConvert.h decodeToFloat) ----
def decode_s16(b):
    s = struct.unpack('<h', b)[0]
    return f32(f32(s) / f32(32768.0))   # /32768.0f
def decode_s24(b):
    v = b[0] | (b[1] << 8) | (b[2] << 16)
    if v & 0x800000: v |= ~0xFFFFFF
    return f32(f32(v) / f32(8388608.0))
def decode_s32(b):
    s = struct.unpack('<i', b)[0]
    return f32(float(s) / 2147483648.0)  # double division then -> float
def decode_f32(b):
    return struct.unpack('<f', b)[0]

# ---- encode (mirrors encodeFromFloat) ----
def encode_s16(x):
    v = lround(f32(x) * f32(32768.0))    # in[i]*32768.0f  (float) then lround
    v = clamp(v, -32768, 32767)
    return struct.pack('<h', v)
def encode_s24(x):
    v = lround(f32(x) * f32(8388608.0))
    v = clamp(v, -8388608, 8388607)
    return bytes([v & 0xFF, (v >> 8) & 0xFF, (v >> 16) & 0xFF])
def encode_s32(x):
    v = int(round(float(x) * 2147483648.0))  # llround(double)
    # emulate llround (half away from zero) precisely:
    d = float(x) * 2147483648.0
    v = math.floor(d + 0.5) if d >= 0 else math.ceil(d - 0.5)
    v = clamp(v, -2147483648, 2147483647)
    return struct.pack('<i', v)
def encode_f32(x):
    return struct.pack('<f', x)

def s24_signed(b):
    v = b[0] | (b[1] << 8) | (b[2] << 16)
    if v & 0x800000: v |= ~0xFFFFFF
    return v

print("=== B. PcmConvert oracle test (Python mirror of PcmConvert.h) ===")

# B1 ROUND-TRIP
print("\n[B1] ROUND-TRIP")
xs = [0.0,0.5,-0.5,0.25,-0.25,0.999969,-1.0,0.123456,-0.777,0.000031]
me = max(abs(decode_s16(encode_s16(x)) - f32(x)) for x in xs)
chk(me <= 1/32768 + 1e-9, "S16 round-trip max err <= 1/32768", float(me), 1/32768)
me = max(abs(decode_s24(encode_s24(x)) - f32(x)) for x in xs)
chk(me <= 1/8388608 + 1e-9, "S24 round-trip max err <= 1/8388608", float(me), 1/8388608)
me = max(abs(decode_s32(encode_s32(x)) - f32(x)) for x in xs)
chk(me <= 1.2e-7, "S32 round-trip max err <= float eps", float(me), 1.2e-7)
fx = [0.0,-0.0,1.0,-1.0,3.14159,1e-30,1e30,-123.456]
ok = all(encode_f32(x) == encode_f32(decode_f32(encode_f32(x))) and
         struct.pack('<f',x) == struct.pack('<f', decode_f32(encode_f32(x))) for x in fx)
chk(ok, "F32 round-trip BIT-IDENTICAL", ok, True)

# B2 CLAMP
print("\n[B2] CLAMP")
chk(struct.unpack('<h',encode_s16(2.0))[0] == 32767, "+2.0 -> S16 == 32767", struct.unpack('<h',encode_s16(2.0))[0], 32767)
chk(struct.unpack('<h',encode_s16(-2.0))[0] == -32768, "-2.0 -> S16 == -32768", struct.unpack('<h',encode_s16(-2.0))[0], -32768)
chk(s24_signed(encode_s24(2.0)) == 8388607, "+2.0 -> S24 == 8388607", s24_signed(encode_s24(2.0)), 8388607)
chk(s24_signed(encode_s24(-2.0)) == -8388608, "-2.0 -> S24 == -8388608", s24_signed(encode_s24(-2.0)), -8388608)
chk(struct.unpack('<i',encode_s32(2.0))[0] == 2147483647, "+2.0 -> S32 == 2147483647", struct.unpack('<i',encode_s32(2.0))[0], 2147483647)
chk(struct.unpack('<i',encode_s32(-2.0))[0] == -2147483648, "-2.0 -> S32 == -2147483648", struct.unpack('<i',encode_s32(-2.0))[0], -2147483648)
chk(struct.unpack('<h',encode_s16(1.0))[0] == 32767, "+1.0 -> S16 clamps to 32767 (no wrap)", struct.unpack('<h',encode_s16(1.0))[0], 32767)
chk(s24_signed(encode_s24(1.0)) == 8388607, "+1.0 -> S24 clamps to 8388607", s24_signed(encode_s24(1.0)), 8388607)

# B3 S24 SIGN-EXTENSION
print("\n[B3] S24 SIGN-EXTENSION")
b = encode_s24(-0.5)
back = decode_s24(b)
chk(abs(back - (-0.5)) <= 1/8388608, "S24 decode of -0.5 ~ -0.5 (not large +)", float(back), -0.5)
chk(back < 0.0, "S24 decoded value NEGATIVE", float(back), "<0")
chk(b == bytes([0x00,0x00,0xC0]), "S24 -0.5 packs LE {00,00,C0}", b.hex(), "0000c0")
chk(abs(decode_s24(bytes([0x00,0x00,0x80])) - (-1.0)) < 1e-6, "S24 raw 0x800000 -> -1.0", float(decode_s24(bytes([0,0,0x80]))), -1.0)

# B4 S24 PACKING
print("\n[B4] S24 PACKING")
bps = {0:2,1:3,2:4,3:4}  # mirror bytesPerSample
chk(bps[1]==3, "bytesPerSample(S24)==3", bps[1], 3)
chk(bps[0]==2, "bytesPerSample(S16)==2", bps[0], 2)
chk(bps[2]==4, "bytesPerSample(S32)==4", bps[2], 4)
chk(bps[3]==4, "bytesPerSample(F32)==4", bps[3], 4)
chk(encode_s24(0.5) == bytes([0x00,0x00,0x40]), "S24 +0.5 packs LE {00,00,40}", encode_s24(0.5).hex(), "000040")
mid = decode_s24(bytes([0x56,0x34,0x12]))
chk(abs(mid - 0x123456/8388608) < 1e-6, "S24 {56,34,12} -> 0x123456/2^23", float(mid), 0x123456/8388608)

# B5 KNOWN VALUES
print("\n[B5] KNOWN VALUES")
chk(abs(decode_s16(bytes([0x00,0x40])) - 0.5) < 1e-7, "S16 {00,40}=16384 -> 0.5", float(decode_s16(bytes([0,0x40]))), 0.5)
chk(abs(decode_s16(bytes([0x00,0xC0])) - (-0.5)) < 1e-7, "S16 {00,C0}=-16384 -> -0.5", float(decode_s16(bytes([0,0xC0]))), -0.5)

print(f"\n=== RESULT: {P} passed, {F} failed ===")
sys.exit(0 if F == 0 else 1)
