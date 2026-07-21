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

// Baseline i8 GEMV: one dot-product reduction per output, mirroring the
// FullyConnected kernel in sw/opt/litert-micro/fully_connected.cc.
// W layout: [N][K] row-major (tflite filter layout, weights per output
// contiguous).
extern "C" void i8_gemv(const int8_t* A, const int8_t* W, const int32_t* bias,
                        int32_t K, int32_t N, const I8GemvQuantParams* qp,
                        int8_t* out) {
  for (int32_t n = 0; n < N; ++n) {
    const int8_t* w_row = W + n * K;
    vint32m4_t acc_v = __riscv_vmv_v_x_i32m4(0, __riscv_vsetvlmax_e32m4());

    int32_t d = 0;
    int32_t d_rem = K;
    while (d_rem > 0) {
      size_t vl = __riscv_vsetvl_e8m1(d_rem);
      vint8m1_t in_v8 = __riscv_vle8_v_i8m1(A + d, vl);
      vint8m1_t weight_v8 = __riscv_vle8_v_i8m1(w_row + d, vl);

      vint16m2_t in_v16 = __riscv_vadd_vx_i16m2(
          __riscv_vsext_vf2_i16m2(in_v8, vl), qp->input_offset, vl);
      vint16m2_t weight_v16 = __riscv_vsext_vf2_i16m2(weight_v8, vl);

      acc_v = __riscv_vwmacc_vv_i32m4(acc_v, in_v16, weight_v16, vl);

      d += vl;
      d_rem -= vl;
    }

    vint32m1_t zero_v = __riscv_vmv_v_x_i32m1(0, 1);
    vint32m1_t sum_v =
        __riscv_vredsum_vs_i32m4_i32m1(acc_v, zero_v, __riscv_vsetvlmax_e32m4());
    int32_t acc = __riscv_vmv_x_s_i32m1_i32(sum_v);

    if (bias) {
      acc += bias[n];
    }
    out[n] = RequantizeClamp(acc, qp);
  }
}
