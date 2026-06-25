// Copyright 2026 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include <cstdint>

// -----------------------------------------------------------------------------
// VME Instructions Hardcoded Encodings (Targeting Tile 0 only)
// -----------------------------------------------------------------------------

// vme_msetmtype rs1, rs2 (rd = x0)
static inline __attribute__((always_inline))
void vme_msetmtype(uint32_t mtype_value, uint32_t vtype_value) {
  register uint32_t a0_arg asm("a0") = mtype_value;
  register uint32_t a1_arg asm("a1") = vtype_value;
  asm volatile(".insn r 0b1010111, 0b111, 0b1000001, x0, a0, a1"
               :
               : "r"(a0_arg), "r"(a1_arg));
}

// vtzero Tile 0 (rs1 = x0)
#define VME_VTZERO_T0() \
  asm volatile(".word 0x43e06057" ::: "memory")

// vtmv.t.v v8 to Tile 0, pattern 0, index 0 (vs2 = 8, rs1 = x0)
#define VME_VTMV_T_V_V8_T0() \
  asm volatile(".word 0x5e806057" ::: "memory")

// vtmv.v.t v12 from Tile 0, pattern 0, index 0 (vd = 12, rs1 = x0)
#define VME_VTMV_V_T_V12_T0() \
  asm volatile(".word 0x43f06657" ::: "memory")

// vtmv.v.t v16 from Tile 0, pattern 0, index 0 (vd = 16, rs1 = x0)
#define VME_VTMV_V_T_V16_T0() \
  asm volatile(".word 0x43f06857" ::: "memory")

// vmmt.vv v1 (Tile 0, signed), v4, v8 (vd = 1, vs2 = 4, vs1 = 8)
#define VME_VMMT_VV_T0_V4_V8() \
  asm volatile(".word 0xf24400d7" ::: "memory")

// -----------------------------------------------------------------------------
// Test IO Tables (using `used` attribute)
// -----------------------------------------------------------------------------

struct MatMulInputs {
  int8_t lhs[64] __attribute__((aligned(16)));
  int8_t rhs[64] __attribute__((aligned(16)));
};

struct TestOutputs {
  uint32_t vtmv_v_t_result[4];  // Output of the simple VTMV test
};

volatile TestOutputs test_outputs __attribute__((section(".data"), used)) = {};

// Keep val in .data to avoid copy loops and ensure DTCM base address
volatile uint32_t val[4] __attribute__((section(".data"), used)) = {0x11111111, 0x22222222, 0x33333333, 0x44444444};

int main() {
  // --- TEST STAGE 1: VTMV Data Movement ---
  // Configure: tm = 4, tk = 1, mtwiden = 3 (EEW32). vtype: SEW8, LMUL4 (0x02).
  // _pack_mtype(tm=4, tk=1, mtwiden=3) = 0x1023
  vme_msetmtype(0x1023, 0x02);

  // Set vl to 4 (so check_tn passes) and vtype to e8 to match VTZERO decode
  asm volatile("vsetivli zero, 4, e8, m4, ta, ma");

  // Clear accumulator tile 0
  VME_VTZERO_T0();

  // Set vtype to e32 to match EEW32 for VTMV data movement
  //asm volatile("vsetivli zero, 4, e32, m4, ta, ma");

  // Load vector register v8 with dummy test values from DTCM
  //asm volatile(
  //    "vle32.v v8, (%0)"
  //    : : "r"(val)
  //);

  // Move v8 into accumulator tile 0, pattern 0, index 0
  //VME_VTMV_T_V_V8_T0();

  // Read it back from tile 0 into v12
  //VME_VTMV_V_T_V12_T0();

  // Store v12 back to memory so cocotb can assert it
  //asm volatile(
  //    "vsetivli zero, 4, e32, m4, ta, ma;\n\t"
  //    "vse32.v v12, (%0)"
  //    : : "r"(test_outputs.vtmv_v_t_result)
  //);

  return 0;
}
