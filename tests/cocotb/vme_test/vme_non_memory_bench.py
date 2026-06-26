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

Runs vme_non_memory_test_program.cc, which exercises two stages:
  1. vtzero -> vtmv.v.t: drained body must be all-zero.
  2. vle8 -> vtmv.t.v -> vtmv.v.t: round-trip must preserve the input pattern.
"""

import cocotb
import numpy as np
from coralnpu_test_utils.core_mini_axi_interface import CoreMiniAxiInterface
from bazel_tools.tools.python.runfiles import runfiles


@cocotb.test()
async def vme_non_memory_test(dut):
  """vtzero -> vtmv.v.t -> zeros; vtmv.t.v -> vtmv.v.t round-trip preserved."""

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
  # are not asserted on. TestOutputs layout: vtmv_v_t_result[16] then
  # vtmv_t_v_result[16] (the second field begins 16 bytes in).
  raw_zero = await core_mini_axi.read(result_addr, 4)
  body_zero = np.frombuffer(raw_zero, dtype=np.uint8)
  cocotb.log.info(f"stage 1 (vtzero) drained body: {body_zero.tolist()}")
  for i, b in enumerate(body_zero):
    assert b == 0, (
        f"vtmv.v.t body[{i}] = 0x{b:02x}, expected 0x00 after vtzero")

  raw_rt = await core_mini_axi.read(result_addr + 16, 4)
  body_rt = np.frombuffer(raw_rt, dtype=np.uint8)
  expected_rt = [0x11, 0x22, 0x33, 0x44]
  cocotb.log.info(f"stage 2 (vtmv.t.v round-trip) drained body: {body_rt.tolist()}")
  for i, (b, e) in enumerate(zip(body_rt, expected_rt)):
    assert b == e, (
        f"vtmv.t.v round-trip body[{i}] = 0x{b:02x}, expected 0x{e:02x}")

  cocotb.log.info("[VME] vtzero/vtmv.v.t/vtmv.t.v all passed")
