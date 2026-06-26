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

"""Cocotb test bench for VME (Zvt) non-memory instructions.

Runs vme_non_memory_test_program.cc which configures the matrix unit, zeroes
tile 0 (`vtzero`), drains tile 0 row 0 into v12 (`vtmv.v.t`), and stores the
v12 body to memory. This bench asserts the body is all-zero.
"""

import cocotb
import numpy as np
from coralnpu_test_utils.core_mini_axi_interface import CoreMiniAxiInterface
from bazel_tools.tools.python.runfiles import runfiles


@cocotb.test()
async def vme_non_memory_test(dut):
  """vtzero → vtmv.v.t round-trip should produce a zero vector."""

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

  # Body = vl(4) * EEW(8b) = 4 bytes. Tail bytes are ta=1 (unspecified) and
  # are not asserted on.
  raw = await core_mini_axi.read(result_addr, 4)
  body = np.frombuffer(raw, dtype=np.uint8)
  cocotb.log.info(f"vtmv.v.t body bytes: {body.tolist()}")
  for i, b in enumerate(body):
    assert b == 0, (
        f"vtmv.v.t body[{i}] = 0x{b:02x}, expected 0x00 after vtzero")
  cocotb.log.info("[VME] vtzero -> vtmv.v.t round-trip produced zeros")
