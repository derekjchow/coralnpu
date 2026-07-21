# Copyright 2026 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Shared golden model and driver for the i8 GEMV (1024 -> 256) benchmark.

The golden model implements per-tensor tflite FULLY_CONNECTED int8 semantics:
i32 accumulation with input zero-point (weight zero-point 0), i32 bias, and
double-rounding MultiplyByQuantizedMultiplier requantization, matching the
C implementation in i8_gemv.h bit-exactly. The cocotb testcases live in
i8_gemv_dtcm_bench.py / i8_gemv_ddr_bench.py, one module per DUT model.
"""

import numpy as np
from coralnpu_test_utils.sim_test_fixture import Fixture
from bazel_tools.tools.python.runfiles import runfiles
from sw.utils.metrics import log_gemv_metrics

K = 1024
N = 256

INT32_MIN = -2**31
INT32_MAX = 2**31 - 1


def quantize_multiplier(real_multiplier: float):
    """Returns (q31_multiplier, shift) for a real multiplier in (0, 1)."""
    assert 0.0 < real_multiplier < 1.0
    mantissa, exponent = np.frexp(real_multiplier)
    q = int(round(mantissa * (1 << 31)))
    if q == (1 << 31):
        q //= 2
        exponent += 1
    return q, int(exponent)


def saturating_rounding_doubling_high_mul(a: int, b: int) -> int:
    if a == INT32_MIN and b == INT32_MIN:
        return INT32_MAX
    ab = a * b
    nudge = (1 << 30) if ab >= 0 else (1 - (1 << 30))
    v = ab + nudge
    # C truncating division by 2^31.
    result = abs(v) >> 31
    return result if v >= 0 else -result


def rounding_divide_by_pot(x: int, exponent: int) -> int:
    if exponent == 0:
        return x
    mask = (1 << exponent) - 1
    remainder = x & mask
    threshold = (mask >> 1) + (1 if x < 0 else 0)
    return (x >> exponent) + (1 if remainder > threshold else 0)


def multiply_by_quantized_multiplier(x: int, multiplier: int,
                                     shift: int) -> int:
    left_shift = shift if shift > 0 else 0
    right_shift = -shift if shift < 0 else 0
    x = np.int32(np.int64(x) << left_shift)  # i32 wraparound like C
    return rounding_divide_by_pot(
        saturating_rounding_doubling_high_mul(int(x), multiplier), right_shift
    )


def gen_test_case(seed=42):
    rng = np.random.default_rng(seed=seed)
    a_data = rng.integers(-128, 128, K, dtype=np.int8)
    # tflite int8 weights are narrow-range: [-127, 127].
    w_data = rng.integers(-127, 128, [N, K], dtype=np.int8)  # [out][in]
    bias = rng.integers(-2**15, 2**15, N, dtype=np.int32)
    input_offset = int(rng.integers(-10, 11))  # -input_zero_point
    output_offset = int(rng.integers(-10, 11))  # output_zero_point
    # input_scale * weight_scale / output_scale for a typical i8 FC layer.
    multiplier, shift = quantize_multiplier(2.0**-9 * 1.25)
    return a_data, w_data, bias, input_offset, output_offset, multiplier, shift


def golden_gemv(a_data, w_data, bias, input_offset, output_offset, multiplier,
                shift):
    acc = w_data.astype(np.int32) @ (a_data.astype(np.int32) + input_offset)
    acc += bias
    y = np.array([
        multiply_by_quantized_multiplier(int(v), multiplier, shift)
        for v in acc
    ])
    return np.clip(y + output_offset, -128, 127).astype(np.int8)


def pack_qparams(input_offset, output_offset, multiplier, shift):
    # Field order matches I8GemvQuantParams in i8_gemv.h.
    return np.array(
        [input_offset, output_offset, multiplier, shift, -128, 127],
        dtype=np.int32
    )


async def run_gemv_benchmark(dut, fixture, elf_name, transposed_weights,
                             timeout_cycles):
    r = runfiles.Create()
    elf_path = r.Rlocation(
        f'coralnpu_hw/tests/cocotb/rvv/ml_ops/i8_gemv/{elf_name}.elf'
    )
    await fixture.load_elf_and_lookup_symbols(
        elf_path,
        ['A_input', 'W_input', 'bias_input', 'qparams', 'out_output',
         'csr_cycle_count']
    )

    (a_data, w_data, bias, input_offset, output_offset, multiplier,
     shift) = gen_test_case()
    expected = golden_gemv(
        a_data, w_data, bias, input_offset, output_offset, multiplier, shift
    )

    # Baseline kernel consumes W as [N][K] with the input offset applied in
    # the inner loop. The optimized kernel consumes W as [K][N] and requires
    # the input zero-point folded into the bias offline (exact i32 identity):
    # bias'[n] = bias[n] + input_offset * sum_k W[n][k].
    if transposed_weights:
        w_packed = w_data.transpose()
        kernel_bias = (
            bias + input_offset * w_data.astype(np.int64).sum(axis=1)
        ).astype(np.int32)
        kernel_input_offset = 0
    else:
        w_packed = w_data
        kernel_bias = bias
        kernel_input_offset = input_offset

    await fixture.write('A_input', a_data)
    await fixture.write('W_input', np.ascontiguousarray(w_packed).flatten())
    await fixture.write('bias_input', kernel_bias)
    await fixture.write(
        'qparams',
        pack_qparams(kernel_input_offset, output_offset, multiplier, shift)
    )
    await fixture.run_to_halt(timeout_cycles=timeout_cycles)

    actual = (await fixture.read('out_output', N)).view(np.int8)
    np.testing.assert_array_equal(expected, actual)

    cycles = (await fixture.read_word('csr_cycle_count')).view(np.uint32)[0]
    log_gemv_metrics(dut, elf_name, int(cycles), K, N)


async def create_dtcm_fixture(dut):
    # Non-default TCM sizes move DTCM to 0x100000 and external CSRs to
    # 0x200000 (see rules/linker.bzl).
    return await Fixture.Create(dut, csr_base_addr=0x200000)


async def create_ddr_fixture(dut):
    return await Fixture.Create(
        dut,
        highmem=True,
        ext_mem_base_addr=0x80000000,
        ext_mem_size=32 * 1024 * 1024
    )
