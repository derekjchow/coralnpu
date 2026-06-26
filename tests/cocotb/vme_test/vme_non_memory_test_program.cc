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
  uint32_t vtmv_v_t_result[4];  // Body bytes drained after vtzero (expect 0).
  uint32_t vtmv_t_v_result[4];  // Body bytes from vtmv.t.v -> vtmv.v.t round-trip.
};

// Pre-poison with a non-zero pattern so the test cannot accidentally pass on
// uninitialised zero memory.
volatile TestOutputs test_outputs __attribute__((section(".data"), used)) = {
    .vtmv_v_t_result = {0xDEADBEEF, 0xDEADBEEF, 0xDEADBEEF, 0xDEADBEEF},
    .vtmv_t_v_result = {0xDEADBEEF, 0xDEADBEEF, 0xDEADBEEF, 0xDEADBEEF},
};

// Source pattern for the vtmv.t.v round-trip. Picked so each body byte is
// distinct, monotonic, and recognisable in waveforms.
volatile uint8_t vtmv_t_v_input[4] __attribute__((section(".data"), used)) = {
    0x11, 0x22, 0x33, 0x44,
};

int main() {
  // Configure: tm = 4, tk = 1, mtwiden = 3 (TEW=32). vtype: SEW8, LMUL4 (0x02).
  // _pack_mtype(tm=4, tk=1, mtwiden=3) = 0x1023. This is the only mode the
  // build supports for vtzero ({SEW8, mtwiden=3}); vtmv.v.t is also valid in
  // the same SEW8/LMUL4 config.
  vme_msetmtype(0x1023, 0x02);

  // vl=4 satisfies check_tn for both vtzero and vtmv.v.t.
  asm volatile("vsetivli zero, 4, e8, m4, ta, ma");

  // Stage 1: vtzero -> vtmv.v.t. Drained body must be all-zero.
  VME_VTZERO_T0();
  VME_VTMV_V_T_V12_T0();
  asm volatile("vse8.v v12, (%0)"
               :
               : "r"(&test_outputs.vtmv_v_t_result[0])
               : "memory");

  // Stage 2: vle8 -> vtmv.t.v -> vtmv.v.t round-trip. The drained body must
  // match the input pattern.
  asm volatile("vle8.v v8, (%0)" : : "r"(vtmv_t_v_input));
  VME_VTMV_T_V_V8_T0();
  VME_VTMV_V_T_V16_T0();
  asm volatile("vse8.v v16, (%0)"
               :
               : "r"(&test_outputs.vtmv_t_v_result[0])
               : "memory");

  return 0;
}
