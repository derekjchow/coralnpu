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

#include <riscv_vector.h>
#include <stdint.h>

#include "tests/cocotb/rvv/ml_ops/i8_gemv/i8_gemv.h"

// Optimized i8 GEMV: input-major weights, e8 two-stage accumulation,
// hand-scheduled inline asm.
//
// W layout: [K][N] (transposed: the N weights feeding each input index k are
// contiguous). Per k the raw i8 activation is broadcast as a scalar into e8
// widening MACs (vwmul/vwmacc, 8 MACs per 128-bit uop vs 4 for the e16
// form), accumulating pairs of k-steps in i16m4 partials folded into i32m8
// accumulators with vwadd.wv.
//
// The vector backend has an 8-entry ROB, so multi-uop instructions barely
// overlap; throughput comes from minimizing instruction and uop counts and
// keeping independent chains adjacent. The asm schedule per 4-k block:
// multiplies for this block, then the NEXT block's four weight loads (so the
// LSU fills during the vwadds), then two vwadds onto two independent
// accumulator chains. Explicit register allocation uses all 32 vector regs
// with zero spills:
//   v0-v1 w3, v2-v3 w0, v28-v29 w1, v30-v31 w2   (weight loads, e8m2)
//   v4-v7 part_a, v24-v27 part_b                  (i16m4 partials)
//   v8-v15 acc_a, v16-v23 acc_b                   (i32m8 accumulators)
//
// Contract (vs the baseline kernel):
//  - qp->input_offset must be 0: the caller folds the input zero-point into
//    the bias offline (bias'[n] = bias[n] + input_offset * sum_k W[k][n]),
//    an exact i32 identity. The raw i8 activation then fits the e8 scalar
//    operand.
//  - Weights must be narrow-range [-127, 127] (the tflite int8 weight
//    convention), so a 2-step i16 partial cannot overflow:
//    2 * 127 * 128 = 32512 <= 32767.
//  - N % 32 == 0, K % 4 == 0, K >= 8.
extern "C" void i8_gemv(const int8_t* A, const int8_t* W, const int32_t* bias,
                        int32_t K, int32_t N, const I8GemvQuantParams* qp,
                        int8_t* out) {
  for (int32_t n0 = 0; n0 < N; n0 += 32) {
    int32_t acc_scratch[32];

    const int8_t* ap = A;
    const int8_t* p0 = W + n0;
    const int8_t* p1 = p0 + N;
    const int8_t* p2 = p1 + N;
    const int8_t* p3 = p2 + N;
    int32_t stride = 4 * N;
    int32_t cnt = K / 4 - 1;
    int32_t vlr = 32;
    int32_t t0, t1, t2, t3;

    asm volatile(
        // Zero both accumulator chains.
        "vsetvli zero, %[vlr], e32, m8, ta, ma\n"
        "vmv.v.i v8, 0\n"
        "vmv.v.i v16, 0\n"
        // Prologue: load block 0's four weight rows.
        "vsetvli zero, %[vlr], e8, m2, ta, ma\n"
        "vle8.v v2, (%[p0])\n"
        "add %[p0], %[p0], %[stride]\n"
        "vle8.v v28, (%[p1])\n"
        "add %[p1], %[p1], %[stride]\n"
        "vle8.v v30, (%[p2])\n"
        "add %[p2], %[p2], %[stride]\n"
        "vle8.v v0, (%[p3])\n"
        "add %[p3], %[p3], %[stride]\n"
        "1:\n"
        "lb %[t0], 0(%[ap])\n"
        "lb %[t1], 1(%[ap])\n"
        "lb %[t2], 2(%[ap])\n"
        "lb %[t3], 3(%[ap])\n"
        "addi %[ap], %[ap], 4\n"
        "vsetvli zero, %[vlr], e8, m2, ta, ma\n"
        "vwmul.vx v4, v2, %[t0]\n"
        "vwmacc.vx v4, %[t1], v28\n"
        "vwmul.vx v24, v30, %[t2]\n"
        "vwmacc.vx v24, %[t3], v0\n"
        // Next block's loads issue while the vwadds below execute.
        "vle8.v v2, (%[p0])\n"
        "add %[p0], %[p0], %[stride]\n"
        "vle8.v v28, (%[p1])\n"
        "add %[p1], %[p1], %[stride]\n"
        "vle8.v v30, (%[p2])\n"
        "add %[p2], %[p2], %[stride]\n"
        "vle8.v v0, (%[p3])\n"
        "add %[p3], %[p3], %[stride]\n"
        "vsetvli zero, %[vlr], e16, m4, ta, ma\n"
        "vwadd.wv v8, v8, v4\n"
        "vwadd.wv v16, v16, v24\n"
        "addi %[cnt], %[cnt], -1\n"
        "bnez %[cnt], 1b\n"
        // Epilogue: last block (weights already loaded).
        "lb %[t0], 0(%[ap])\n"
        "lb %[t1], 1(%[ap])\n"
        "lb %[t2], 2(%[ap])\n"
        "lb %[t3], 3(%[ap])\n"
        "vsetvli zero, %[vlr], e8, m2, ta, ma\n"
        "vwmul.vx v4, v2, %[t0]\n"
        "vwmacc.vx v4, %[t1], v28\n"
        "vwmul.vx v24, v30, %[t2]\n"
        "vwmacc.vx v24, %[t3], v0\n"
        "vsetvli zero, %[vlr], e16, m4, ta, ma\n"
        "vwadd.wv v8, v8, v4\n"
        "vwadd.wv v16, v16, v24\n"
        // Combine the two chains and spill to scratch for requantization.
        "vsetvli zero, %[vlr], e32, m8, ta, ma\n"
        "vadd.vv v8, v8, v16\n"
        "vse32.v v8, (%[scratch])\n"
        : [ap] "+r"(ap), [p0] "+r"(p0), [p1] "+r"(p1), [p2] "+r"(p2),
          [p3] "+r"(p3), [cnt] "+r"(cnt), [t0] "=&r"(t0), [t1] "=&r"(t1),
          [t2] "=&r"(t2), [t3] "=&r"(t3)
        : [stride] "r"(stride), [vlr] "r"(vlr), [scratch] "r"(acc_scratch)
        : "memory", "v0", "v1", "v2", "v3", "v4", "v5", "v6", "v7", "v8",
          "v9", "v10", "v11", "v12", "v13", "v14", "v15", "v16", "v17",
          "v18", "v19", "v20", "v21", "v22", "v23", "v24", "v25", "v26",
          "v27", "v28", "v29", "v30", "v31");

    for (int32_t i = 0; i < 32; ++i) {
      int32_t v = acc_scratch[i];
      if (bias) {
        v += bias[n0 + i];
      }
      out[n0 + i] = RequantizeClamp(v, qp);
    }
  }
}
