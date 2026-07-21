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

#ifndef TESTS_COCOTB_RVV_ML_OPS_I8_GEMV_I8_GEMV_H_
#define TESTS_COCOTB_RVV_ML_OPS_I8_GEMV_I8_GEMV_H_

#include <stdint.h>

// Per-tensor tflite FULLY_CONNECTED quantization parameters. Weight
// zero-point is 0 per the tflite int8 convention.
typedef struct {
  int32_t input_offset;    // -input_zero_point
  int32_t output_offset;   // output_zero_point
  int32_t output_multiplier;  // Q31 fixed-point multiplier
  int32_t output_shift;    // positive = left shift, negative = right shift
  int32_t output_activation_min;
  int32_t output_activation_max;
} I8GemvQuantParams;

// out[n] = requant(sum_k (A[k] + input_offset) * W[n][k] + bias[n])
// The weight layout differs per kernel implementation: the baseline expects
// W as [N][K] row-major (each output's weights contiguous); the optimized
// kernel expects W transposed to [K][N] (each input's weights contiguous).
extern "C" void i8_gemv(const int8_t* A, const int8_t* W, const int32_t* bias,
                        int32_t K, int32_t N, const I8GemvQuantParams* qp,
                        int8_t* out);

// Double-rounding requantization, bit-exact with tflite's
// MultiplyByQuantizedMultiplier (TFLITE_SINGLE_ROUNDING is not used in this
// repo; see sw/opt/litert-micro/accumulator_util.h).
static inline int32_t SaturatingRoundingDoublingHighMul(int32_t a, int32_t b) {
  bool overflow = (a == b) && (a == INT32_MIN);
  int64_t ab = (int64_t)a * (int64_t)b;
  int64_t nudge = ab >= 0 ? (1ll << 30) : (1 - (1ll << 30));
  int32_t high = (int32_t)((ab + nudge) / (1ll << 31));
  return overflow ? INT32_MAX : high;
}

static inline int32_t RoundingDivideByPOT(int32_t x, int32_t exponent) {
  if (exponent == 0) {
    return x;
  }
  int32_t mask = (1 << exponent) - 1;
  int32_t remainder = x & mask;
  int32_t threshold = (mask >> 1) + (x < 0 ? 1 : 0);
  return (x >> exponent) + (remainder > threshold ? 1 : 0);
}

static inline int32_t MultiplyByQuantizedMultiplier(int32_t x,
                                                    int32_t multiplier,
                                                    int32_t shift) {
  int32_t left_shift = shift > 0 ? shift : 0;
  int32_t right_shift = shift > 0 ? 0 : -shift;
  return RoundingDivideByPOT(
      SaturatingRoundingDoublingHighMul(x * (1 << left_shift), multiplier),
      right_shift);
}

static inline int8_t RequantizeClamp(int32_t acc, const I8GemvQuantParams* qp) {
  int32_t y =
      MultiplyByQuantizedMultiplier(acc, qp->output_multiplier,
                                    qp->output_shift) + qp->output_offset;
  y = y < qp->output_activation_min ? qp->output_activation_min : y;
  y = y > qp->output_activation_max ? qp->output_activation_max : y;
  return (int8_t)y;
}

#endif  // TESTS_COCOTB_RVV_ML_OPS_I8_GEMV_I8_GEMV_H_
