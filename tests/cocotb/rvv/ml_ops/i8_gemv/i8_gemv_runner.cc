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

#include <stddef.h>
#include <stdint.h>

#include "sw/utils/utils.h"
#include "tests/cocotb/rvv/ml_ops/i8_gemv/i8_gemv.h"

// 1024 -> 256 GEMV, scaled proxy for a 1024 -> 4096 fully-connected layer
// (constant fan-in). Fully frozen dimensions.
constexpr size_t kK = 1024;
constexpr size_t kN = 256;

extern "C" {
const uint32_t gemv_k = kK;
const uint32_t gemv_n = kN;
volatile uint32_t csr_cycle_count = 0;
}

// The 256KB weight matrix goes to DTCM (.data) by default, or DDR when built
// with -DGEMV_DDR to benchmark streaming over the AXI master.
#ifdef GEMV_DDR
#define GEMV_WEIGHT_SECTION ".ddr_data"
#else
#define GEMV_WEIGHT_SECTION ".data"
#endif

int8_t A_input[kK]
    __attribute__((section(".data"), used, retain)) __attribute__((aligned(16)));
int8_t W_input[kK * kN]
    __attribute__((section(GEMV_WEIGHT_SECTION), used, retain))
    __attribute__((aligned(16)));
int32_t bias_input[kN]
    __attribute__((section(".data"), used, retain)) __attribute__((aligned(16)));
I8GemvQuantParams qparams
    __attribute__((section(".data"), used, retain)) __attribute__((aligned(16)));
int8_t out_output[kN]
    __attribute__((section(".data"), used, retain)) __attribute__((aligned(16)));

int main() {
  // Flag start of power-critical section (write to mcontext0)
  uint32_t mcontext0_write_value = 1;
  asm volatile("csrw 0x7C0, %0" : : "r"(mcontext0_write_value));

  cycle_counter_reset();
  uint64_t start_cycles = mcycle_read();

  i8_gemv(A_input, W_input, bias_input, kK, kN, &qparams, out_output);

  uint64_t end_cycles = mcycle_read();
  csr_cycle_count = static_cast<uint32_t>(end_cycles - start_cycles);

  // Flag end of power-critical section (write to mcontext0)
  mcontext0_write_value = 0;
  asm volatile("csrw 0x7C0, %0" : : "r"(mcontext0_write_value));

  return 0;
}
