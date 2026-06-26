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
// Tiled NxN FP32 matmul using vtfmm.tvv outer-product accumulate.
//
// Algorithm (per 4x4 output tile mt0 := C[m:m+4, n:n+4]):
//   for k in 0..N-1:
//     v8  := A^T[k, m:m+4]    // column m..m+4 of A (= row k of A^T)
//     v12 := B  [k, n:n+4]    // row k of B
//     mt0 += v8^T * v12       // 4x4 outer product accumulate
// Drain mt0 row-by-row into C, then move to the next tile.
//
// A is stored TRANSPOSED in memory so each "column of A" is contiguous and
// loadable with unit-stride vle32.v. The bench writes A.T into A_T and the
// untransposed B into B before launching.

#include <cstdint>

#ifndef MATMUL_N
#define MATMUL_N 4   // SMOKE — restore to 64 (or higher) after validation.
#endif

// Per Zvtfmm spec at TWIDEN=1 / KMAX=1 / SEW=32: tile edge = TE / 4 = 4.
#define TILE 4

static_assert((MATMUL_N % TILE) == 0,
              "MATMUL_N must be a multiple of the 4x4 tile size");

// -----------------------------------------------------------------------------
// IO matrices in EXTMEM (.extbss is NOT cleared by the C runtime, unlike
// .bss, so bench-written data survives _start). Symbols are looked up by
// name from the bench; the bench backdoor-writes A_T and B and reads back C.
// -----------------------------------------------------------------------------
volatile float A_T[MATMUL_N * MATMUL_N]
    __attribute__((section(".extbss"), used));
volatile float B[MATMUL_N * MATMUL_N]
    __attribute__((section(".extbss"), used));
volatile float C[MATMUL_N * MATMUL_N]
    __attribute__((section(".extbss"), used));

// -----------------------------------------------------------------------------
// Inline-asm wrappers (identical to the 4x4 single-tile matmul test).
// -----------------------------------------------------------------------------
static inline __attribute__((always_inline))
void vme_msetmtype(uint32_t mtype_value, uint32_t vtype_value) {
  register uint32_t a0_arg asm("a0") = mtype_value;
  register uint32_t a1_arg asm("a1") = vtype_value;
  asm volatile(".insn r 0b1010111, 0b111, 0b1000001, x0, a0, a1"
               :
               : "r"(a0_arg), "r"(a1_arg));
}

// vtzero mt0.
#define VME_VTZERO_T0() asm volatile(".word 0x43e06057" ::: "memory")

// vtfmm.tvv mt0, v8, v12.
#define VME_VTFMM_TVV_T0_V8_V12() \
  asm volatile(".word 0xf2861077" ::: "memory")

// vtmv.v.t vd, rs1=a5, vs2=v31. Drains tile row (TSS from a5) to v(vd).
#define VME_VTMV_V_T_VD(VD_HEX, ROW)                                          \
  do {                                                                        \
    register uint32_t _a5 asm("a5") = (ROW);                                  \
    asm volatile(".word " VD_HEX "" : : "r"(_a5) : "memory");                 \
  } while (0)
#define VME_VTMV_V_T_V16_ROW(R) VME_VTMV_V_T_VD("0x43f7e857", R)
#define VME_VTMV_V_T_V17_ROW(R) VME_VTMV_V_T_VD("0x43f7e8d7", R)
#define VME_VTMV_V_T_V18_ROW(R) VME_VTMV_V_T_VD("0x43f7e957", R)
#define VME_VTMV_V_T_V19_ROW(R) VME_VTMV_V_T_VD("0x43f7e9d7", R)

#define MTYPE_VTZERO_SEW8 0x1023u  // tm=4, tk=1, mtwiden=3
#define MTYPE_VTFMM_FP32  0x1021u  // tm=4, tk=1, mtwiden=1

int main() {
  for (int m_tile = 0; m_tile < MATMUL_N / TILE; m_tile++) {
    for (int n_tile = 0; n_tile < MATMUL_N / TILE; n_tile++) {
      // Stage A: clear mt0 (vendor only enumerates vtzero in SEW=8/mtwiden=3).
      vme_msetmtype(MTYPE_VTZERO_SEW8, 0x02u);
      asm volatile("vsetivli zero, 4, e8, m4, ta, ma");
      VME_VTZERO_T0();

      // Stage B: switch to Zvtfmm FP32 (mtwiden=1, SEW=32, LMUL=4 vtype).
      vme_msetmtype(MTYPE_VTFMM_FP32, 0xD2u);
      asm volatile("vsetivli zero, 4, e32, m4, ta, ma");

      // Warm-up load -- vendor LSU drops the first VRF writeback after a
      // vtype reconfig, so this discard load eats that drop.
      asm volatile("vle32.v v0, (%0)" : : "r"(&A_T[m_tile * TILE]) : "memory");

      // Stage C: K-accumulation. Each iteration is one 4x4 outer-product
      // add into mt0; pointers walk row-by-row through A^T and B.
      //
      // KNOWN BUG: under K>=4 back-to-back vtfmm.tvv with reused vs1/vs2
      // (v8, v12), the 4th iteration reads stale operands (the dispatch
      // operand bypass does not wait for the prior vtfmm to release its
      // vs1/vs2 read before the next vle32.v overwrites them). Repro:
      // identity-matrix matmul at N=4 gives mt0[3,2]=1, mt0[3,3]=0
      // instead of the expected identity. Suspected vendor scoreboard
      // miss in rvv_backend_dispatch.sv / rvv_backend_dispatch_operand.sv
      // for VT_F_MMTVV reads -- see vme_matmul_tiled_bench.py.
      const float* a_ptr = (const float*)&A_T[m_tile * TILE];
      const float* b_ptr = (const float*)&B[n_tile * TILE];
      for (int k = 0; k < MATMUL_N; k++) {
        asm volatile("vle32.v v8, (%0)" : : "r"(a_ptr) : "memory");
        asm volatile("vle32.v v12, (%0)" : : "r"(b_ptr) : "memory");
        VME_VTFMM_TVV_T0_V8_V12();
        a_ptr += MATMUL_N;
        b_ptr += MATMUL_N;
      }

      // Stage D: drain mt0 rows to the 4x4 block in C. LMUL=1 so each
      // vtmv.v.t executes as a single uop (vendor zvt_ctrl only acks
      // uop 0 in the isMv2Rvv arm; under LMUL>=2 it wedges).
      asm volatile("vsetivli zero, 4, e32, m1, ta, ma");
      float* c_ptr = (float*)&C[m_tile * TILE * MATMUL_N + n_tile * TILE];
      VME_VTMV_V_T_V16_ROW(0);
      asm volatile("vse32.v v16, (%0)" : : "r"(c_ptr + 0 * MATMUL_N) : "memory");
      VME_VTMV_V_T_V17_ROW(1);
      asm volatile("vse32.v v17, (%0)" : : "r"(c_ptr + 1 * MATMUL_N) : "memory");
      VME_VTMV_V_T_V18_ROW(2);
      asm volatile("vse32.v v18, (%0)" : : "r"(c_ptr + 2 * MATMUL_N) : "memory");
      VME_VTMV_V_T_V19_ROW(3);
      asm volatile("vse32.v v19, (%0)" : : "r"(c_ptr + 3 * MATMUL_N) : "memory");
    }
  }
  return 0;
}
