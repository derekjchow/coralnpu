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

"""Cocotb test bench for the tiled NxN FP32 matmul (vtfmm.tvv).

Backdoor-writes random A.T and B into EXTMEM, runs the test program (which
loops over output tiles, accumulating K=N outer products per tile via
vtfmm.tvv, then drains each tile to C), and asserts the result matches
A @ B within FP32 tolerance.

N is set by the MATMUL_N #define in vme_matmul_tiled_test_program.cc and
must match the MATMUL_N constant below.
"""

import cocotb
import numpy as np
from coralnpu_test_utils.core_mini_axi_interface import CoreMiniAxiInterface
from bazel_tools.tools.python.runfiles import runfiles


# Must match MATMUL_N in vme_matmul_tiled_test_program.cc.
#
# NOTE: this test currently fails for N>=4 due to a vendor RTL bug -- under
# K>=4 back-to-back vtfmm.tvv calls accumulating into the same mt0, the 4th
# iteration's vs1 (v12) reads stale data (the dispatch operand bypass
# doesn't wait for the prior vtfmm to release its read before the next vle
# overwrites v12). Identity-matrix repro at N=4 shows mt0[3,2]=1 / [3,3]=0
# instead of the expected identity. Suspected scoreboard hole in
# rvv_backend_dispatch / dispatch_operand for VT_F_MMTVV reads.
#
# The single-tile 4x4 outer product (K=1) does work correctly -- see
# vme_matmul_test.
MATMUL_N = 4


@cocotb.test()
async def vme_matmul_tiled_test(dut):
  """Tiled NxN FP32 matmul via per-tile vtfmm.tvv accumulation."""

  core_mini_axi = CoreMiniAxiInterface(dut)
  await core_mini_axi.init()
  await core_mini_axi.reset()
  cocotb.start_soon(core_mini_axi.clock.start())

  r = runfiles.Create()
  elf_path = r.Rlocation(
      "coralnpu_hw/tests/cocotb/vme_test/vme_matmul_tiled_test_program.elf")
  if not elf_path:
    raise ValueError("Could not find ELF file. Build the target first.")

  with open(elf_path, "rb") as f:
    entry_point = await core_mini_axi.load_elf(f)
  with open(elf_path, "rb") as f:
    addr_a_t = core_mini_axi.lookup_symbol(f, "A_T")
  with open(elf_path, "rb") as f:
    addr_b = core_mini_axi.lookup_symbol(f, "B")
  with open(elf_path, "rb") as f:
    addr_c = core_mini_axi.lookup_symbol(f, "C")
  cocotb.log.info(
      f"matmul N={MATMUL_N}: A_T=0x{addr_a_t:08x} B=0x{addr_b:08x} "
      f"C=0x{addr_c:08x}")

  # Small positive-integer FP32. Mixed-sign inputs hit a separate vendor
  # FP-adder edge case (the absolute-value adder mis-signs partials when
  # one operand is negative); restrict to positive values to keep the
  # tiled-matmul test focused on validating the K-accumulation correctness.
  # FP32 represents integers up to 16M exactly, so K-summed products of
  # small positive integers stay bit-exact -- assert_allclose tolerance
  # below is conservative.
  rng = np.random.default_rng(seed=42)
  a = rng.integers(1, 9, size=(MATMUL_N, MATMUL_N)).astype(np.float32)
  b = rng.integers(1, 9, size=(MATMUL_N, MATMUL_N)).astype(np.float32)

  # Reference: numpy matmul (inner-product summation order, different from
  # the NPU's outer-product accumulation, so allow FP tolerance).
  expected = (a @ b).astype(np.float32)

  # Stage inputs into EXTMEM. The NPU loads "column m of A" via vle32.v, so
  # we hand it A.T (row m of A.T == column m of A). write() iterates the
  # array byte-wise, so flatten to 1D first.
  await core_mini_axi.write(addr_a_t,
                            np.ascontiguousarray(a.T, dtype=np.float32).ravel())
  await core_mini_axi.write(addr_b,
                            np.ascontiguousarray(b, dtype=np.float32).ravel())

  # Read back what we wrote, to confirm the staging actually landed.
  raw_a_check = await core_mini_axi.read(addr_a_t,
                                         MATMUL_N * MATMUL_N * 4)
  staged_a = np.frombuffer(raw_a_check, dtype=np.float32).reshape(
      MATMUL_N, MATMUL_N)
  cocotb.log.info(f"matmul N={MATMUL_N}: staged A.T[0,:]={staged_a[0]}")

  await core_mini_axi.execute_from(entry_point)

  # Budget enough cycles for the full inner-loop sweep:
  # (N/4)^2 tiles * (per-tile setup + N * ~3 inner-loop insts + drain)
  # * ~5 cycles/inst, plus generous slack.
  per_tile_insts = 32 + 3 * MATMUL_N + 16
  approx_cycles = (MATMUL_N // 4) ** 2 * per_tile_insts * 8
  timeout_cycles = max(approx_cycles, 2_000_000)
  await core_mini_axi.wait_for_halted(timeout_cycles=timeout_cycles)

  raw = await core_mini_axi.read(addr_c,
                                 MATMUL_N * MATMUL_N * 4)  # FP32 bytes
  actual = np.frombuffer(raw,
                         dtype=np.float32).reshape(MATMUL_N, MATMUL_N).copy()

  abs_diff = np.abs(actual - expected)
  cocotb.log.info(
      f"matmul N={MATMUL_N}: max abs diff = {abs_diff.max():.3e}, "
      f"max rel diff = "
      f"{(abs_diff / np.maximum(np.abs(expected), 1e-30)).max():.3e}")

  np.testing.assert_allclose(actual, expected, rtol=1e-5, atol=1e-6)
  cocotb.log.info(f"[VME] vtfmm.tvv tiled {MATMUL_N}x{MATMUL_N} matmul passed")
