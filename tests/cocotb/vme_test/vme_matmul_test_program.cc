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
//
// Zvt FP32 outer-product matmul (vtfmm.tvv) round-trip:
//   1. msetmtype + vsetivli for Zvtfmm (SEW=32, LMUL=4, tm=tn=4, tk=1).
//   2. vtzero the tile under SEW=8/mtwiden=3 first (vendor only supports
//      vtzero in that config) and re-config to SEW=32 for the matmul.
//   3. Load A row into v8 and B row into v12 (LMUL4, vl=4 -> first 4
//      FP32 elements are body).
//   4. vtfmm.tvv mt0, v8, v12 computes the 4x4 FP32 outer product.
//   5. Drain tile 0 row-by-row via vtmv.v.t and store each row to memory
//      so the bench can compare against numpy `np.outer(A, B)`.

#include <cstdint>

// -----------------------------------------------------------------------------
// Inline-asm wrappers for the Zvt instructions the assembler doesn't know.
// -----------------------------------------------------------------------------

// vme_msetmtype rs1, rs2  (rd = x0). funct7=1000001, funct3=111, opcode=1010111.
static inline __attribute__((always_inline))
void vme_msetmtype(uint32_t mtype_value, uint32_t vtype_value) {
  register uint32_t a0_arg asm("a0") = mtype_value;
  register uint32_t a1_arg asm("a1") = vtype_value;
  asm volatile(".insn r 0b1010111, 0b111, 0b1000001, x0, a0, a1"
               :
               : "r"(a0_arg), "r"(a1_arg));
}

// vtzero mt0 (rs1 = x0). Bytewise zero of the tile under the current
// mtype config; the vendor only enumerates {SEW8, mtwiden=3} so we set
// that config before calling.
#define VME_VTZERO_T0() \
  asm volatile(".word 0x43e06057" ::: "memory")

// vtfmm.tvv mt0, v8, v12.
// funct6=111100, vm=1, vs2=01000 (v8), vs1=01100 (v12), funct3=001,
// rd=00000 (mt0, bit7=0), opcode=1110111  ->  0xF2861077
#define VME_VTFMM_TVV_T0_V8_V12() \
  asm volatile(".word 0xf2861077" ::: "memory")

// vtmv.v.t vd, rs1, vs2=v31  drains one tile row to a vector register.
//
// TSS encoding (packed, see TSS_t in rvv_backend.svh): tile[$clog2(NUM_ACC)+|
// pattern[1] | index[$clog2(TE)]. For our test: tile=0, pattern=0 (row),
// index=row -> TSS == row.
//
// rs1 is hardcoded to a5 in the encoding below so we can vary row at
// runtime without re-emitting the instruction word. The .word encodings
// would be:
//   funct6=010000, vm=1, vs2=11111, rs1=01111(a5), funct3=110, vd, opcode=1010111
// vd=16: 0x43f7e857  |  vd=17: 0x43f7e8d7  |  vd=18: 0x43f7e957  |  vd=19: 0x43f7e9d7
//
// Each vtmv.v.t MUST be executed under LMUL=1 (no stripmining). Under
// LMUL=4 the vendor zvt_ctrl only acknowledges uop 0 of the 4-uop stripmine
// (uopRdy[0]=res_vme2rvv_rdy and uopRdy[3:1] are tied 0 in the isMv2Rvv
// arm), so uops 1-3 wedge the pipeline.
#define VME_VTMV_V_T_VD(VD_HEX, ROW)                                          \
  do {                                                                        \
    register uint32_t _a5 asm("a5") = (ROW);                                  \
    asm volatile(".word " VD_HEX "" : : "r"(_a5) : "memory");                 \
  } while (0)
#define VME_VTMV_V_T_V16_ROW(R) VME_VTMV_V_T_VD("0x43f7e857", R)
#define VME_VTMV_V_T_V17_ROW(R) VME_VTMV_V_T_VD("0x43f7e8d7", R)
#define VME_VTMV_V_T_V18_ROW(R) VME_VTMV_V_T_VD("0x43f7e957", R)
#define VME_VTMV_V_T_V19_ROW(R) VME_VTMV_V_T_VD("0x43f7e9d7", R)

// -----------------------------------------------------------------------------
// Test IO.
// -----------------------------------------------------------------------------

// FP32 inputs. Integer values so np.outer(A, B) is bit-exact in FP32.
volatile float matmul_a[4] __attribute__((section(".data"), used)) = {
    1.0f, 2.0f, 3.0f, 4.0f,
};
volatile float matmul_b[4] __attribute__((section(".data"), used)) = {
    5.0f, 6.0f, 7.0f, 8.0f,
};

// Pre-poison so the bench cannot accidentally pass on uninitialised memory.
struct TestOutputs {
  float matmul_result[4 * 4];  // 4x4 FP32, row-major.
};
volatile TestOutputs test_outputs __attribute__((section(".data"), used)) = {
    .matmul_result = {
        -1.0f, -1.0f, -1.0f, -1.0f,
        -1.0f, -1.0f, -1.0f, -1.0f,
        -1.0f, -1.0f, -1.0f, -1.0f,
        -1.0f, -1.0f, -1.0f, -1.0f,
    },
};

// _pack_mtype: tm[23:10] | tk[6:5] | mtwiden[1:0].
//   tm=4, tk=1, mtwiden=3  ->  (4<<10) | (1<<5) | 3 = 0x1023  (Zvtfmm SEW8)
//   tm=4, tk=1, mtwiden=1  ->  (4<<10) | (1<<5) | 1 = 0x1021  (Zvtfmm FP32)
#define MTYPE_VTZERO_SEW8   0x1023u
#define MTYPE_VTFMM_FP32    0x1021u

int main() {
  // Stage A: zero the tile under SEW=8 / mtwiden=3 (the only vtzero config
  // the vendor supports). The acc array is shared across mtype settings, so
  // this clears all bytes; the subsequent FP32 matmul accumulates into 0.
  vme_msetmtype(MTYPE_VTZERO_SEW8, 0x02);  // vtype: SEW8/LMUL4 placeholder
  asm volatile("vsetivli zero, 4, e8, m4, ta, ma");
  VME_VTZERO_T0();

  // Stage B: switch to Zvtfmm (FP32) config and load operands.
  // vtype = vma|vta|vsew(010=SEW32)|vlmul(010=LMUL4) = 0xD2.
  vme_msetmtype(MTYPE_VTFMM_FP32, 0xD2);
  asm volatile("vsetivli zero, 4, e32, m4, ta, ma");

  // Warm-up load — vendor LSU drops the first VRF writeback after a vtype
  // reconfig, so the real loads must come second/third. Discard v0.
  asm volatile("vle32.v v0, (%0)" : : "r"(matmul_a) : "memory");
  asm volatile("vle32.v v8, (%0)" : : "r"(matmul_a) : "memory");
  asm volatile("vle32.v v12, (%0)" : : "r"(matmul_b) : "memory");

  // Stage C: vtfmm.tvv mt0, v8, v12. Computes
  //   mt0[i,j] += A[0,i] * B[0,j]   for i,j in 0..tm-1, 0..tn-1
  // i.e. the 4x4 FP32 outer product of A[:4] and B[:4] (mt0 was zeroed).
  VME_VTFMM_TVV_T0_V8_V12();

  // Stage D: switch to LMUL=1 so vtmv.v.t executes as a single uop, then
  // drain each of the four tile rows (TSS index = row) into its own v
  // register and store to memory.
  asm volatile("vsetivli zero, 4, e32, m1, ta, ma");
  VME_VTMV_V_T_V16_ROW(0);
  asm volatile("vse32.v v16, (%0)"
               :
               : "r"(&test_outputs.matmul_result[0])
               : "memory");
  VME_VTMV_V_T_V17_ROW(1);
  asm volatile("vse32.v v17, (%0)"
               :
               : "r"(&test_outputs.matmul_result[4])
               : "memory");
  VME_VTMV_V_T_V18_ROW(2);
  asm volatile("vse32.v v18, (%0)"
               :
               : "r"(&test_outputs.matmul_result[8])
               : "memory");
  VME_VTMV_V_T_V19_ROW(3);
  asm volatile("vse32.v v19, (%0)"
               :
               : "r"(&test_outputs.matmul_result[12])
               : "memory");

  return 0;
}
