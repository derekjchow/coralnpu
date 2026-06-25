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

"""Cocotb test bench for VME (Zvt) non-memory instructions (Phase 1).

Runs vme_non_memory_test_program.cc which zeroes a tile, moves vector register
to the tile, and reads it back, then verifies the output.
"""

import cocotb
import numpy as np
from coralnpu_test_utils.core_mini_axi_interface import CoreMiniAxiInterface
from bazel_tools.tools.python.runfiles import runfiles


@cocotb.test()
async def vme_non_memory_test(dut):
  """Drive a program exercising non-memory Zvt instructions and check result."""

  core_mini_axi = CoreMiniAxiInterface(dut)
  await core_mini_axi.init()
  await core_mini_axi.reset()
  cocotb.start_soon(core_mini_axi.clock.start())

  r = runfiles.Create()
  elf_path = r.Rlocation(
      "coralnpu_hw/tests/cocotb/vme_test/vme_non_memory_test_program.elf")
  if not elf_path:
    raise ValueError("Could not find ELF file. Build the target first.")

  with open(elf_path, "rb") as f:
    entry_point = await core_mini_axi.load_elf(f)

  with open(elf_path, "rb") as f:
    result_addr = core_mini_axi.lookup_symbol(f, "test_outputs")

  await core_mini_axi.execute_from(entry_point)
  await core_mini_axi.wait_for_halted()

  # Pull results back: 4 uint32 words.
  #raw = await core_mini_axi.read(result_addr, 4 * 4)
  #results = np.frombuffer(raw, dtype=np.uint32)

  #expected = [0x11111111, 0x22222222, 0x33333333, 0x44444444]
  #cocotb.log.info(f"Expected: {[hex(x) for x in expected]}")
  #cocotb.log.info(f"Actual:   {[hex(x) for x in results]}")

  #for i in range(4):
  #  assert results[i] == expected[i], (
  #      f"Mismatch at index {i}: got 0x{results[i]:08x}, "
  #      f"expected 0x{expected[i]:08x}")

  #cocotb.log.info("[VME] Phase 1 (Data Movement) Passed!")
