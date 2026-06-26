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

"""Cocotb test bench for the Zvt FP32 outer-product matmul (vtfmm.tvv).

Runs vme_matmul_test_program.cc, which zeroes tile 0, loads A and B into v8
and v12, runs vtfmm.tvv (computing mt0 += A^T * B with K=1, i.e. the outer
product), then drains the four rows of mt0 to memory. This bench asserts
the result is bit-exact against np.outer(A, B).
"""

import cocotb
import numpy as np
from coralnpu_test_utils.core_mini_axi_interface import CoreMiniAxiInterface
from bazel_tools.tools.python.runfiles import runfiles


@cocotb.test()
async def vme_matmul_test(dut):
  """vtfmm.tvv mt0, v8, v12 should equal np.outer(A, B) bit-exactly."""

  core_mini_axi = CoreMiniAxiInterface(dut)
  await core_mini_axi.init()
  await core_mini_axi.reset()
  cocotb.start_soon(core_mini_axi.clock.start())

  r = runfiles.Create()
  elf_path = r.Rlocation(
      "coralnpu_hw/tests/cocotb/vme_test/vme_matmul_test_program.elf")
  if not elf_path:
    raise ValueError("Could not find ELF file. Build the target first.")

  with open(elf_path, "rb") as f:
    entry_point = await core_mini_axi.load_elf(f)

  with open(elf_path, "rb") as f:
    result_addr = core_mini_axi.lookup_symbol(f, "test_outputs")

  await core_mini_axi.execute_from(entry_point)
  await core_mini_axi.wait_for_halted()

  # Reference computation. Inputs picked so integer products fit exactly in
  # FP32 (no rounding) and the comparison can be bit-exact.
  a = np.array([1.0, 2.0, 3.0, 4.0], dtype=np.float32)
  b = np.array([5.0, 6.0, 7.0, 8.0], dtype=np.float32)
  expected = np.outer(a, b)  # 4x4 FP32

  raw = await core_mini_axi.read(result_addr, 4 * 4 * 4)  # 16 FP32 = 64 bytes
  actual = np.frombuffer(raw, dtype=np.float32).reshape(4, 4)
  cocotb.log.info(f"vtfmm.tvv result:\n{actual}")
  cocotb.log.info(f"np.outer(A, B):\n{expected}")
  np.testing.assert_array_equal(actual, expected)
  cocotb.log.info("[VME] vtfmm.tvv FP32 outer-product round-trip passed")
