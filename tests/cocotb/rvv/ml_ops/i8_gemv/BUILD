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

load("//rules:coco_tb.bzl", "cocotb_test_suite")
load("//rules:coralnpu_v2.bzl", "coralnpu_v2_binary")
load(
    "//tests/cocotb:build_defs.bzl",
    "VCS_BUILD_ARGS",
    "VCS_DEFINES",
    "VCS_TEST_ARGS",
)

package(default_visibility = ["//visibility:public"])

# i8 GEMV 1024->256 benchmark binaries: {baseline, opt} kernels x
# {DTCM-resident, DDR-streaming} weight placement.
[coralnpu_v2_binary(
    name = name,
    srcs = [
        "i8_gemv_runner.cc",
        kernel,
    ],
    defines = defines,
    dtcm_size_kbytes = dtcm,
    enable_vmem = False,
    hdrs = ["i8_gemv.h"],
    itcm_size_kbytes = itcm,
    deps = ["//sw/utils"],
) for name, kernel, defines, itcm, dtcm in [
    ("i8_gemv_baseline_dtcm", "i8_gemv_kernel.cc", [], 512, 512),
    ("i8_gemv_opt_dtcm", "i8_gemv_opt.cc", [], 512, 512),
    ("i8_gemv_baseline_ddr", "i8_gemv_kernel.cc", ["GEMV_DDR"], 1024, 1024),
    ("i8_gemv_opt_ddr", "i8_gemv_opt.cc", ["GEMV_DDR"], 1024, 1024),
]]

py_library(
    name = "i8_gemv_common",
    srcs = ["i8_gemv_common.py"],
    deps = [
        "//coralnpu_test_utils:sim_test_fixture",
        "//sw/utils:metrics",
        "@bazel_tools//tools/python/runfiles",
    ],
)

# Compute-bound configuration: the full 256KB weight matrix is resident in a
# 512KB DTCM.
cocotb_test_suite(
    name = "i8_gemv_dtcm_test_suite",
    simulators = ["verilator"],
    testcases = [
        "i8_gemv_baseline_dtcm_test",
        "i8_gemv_opt_dtcm_test",
    ],
    tests_kwargs = {
        "hdl_toplevel": "RvvCoreMini_ITCM512KB_DTCM512KBAxi",
        "waves": False,
        "seed": "42",
        "test_module": ["i8_gemv_dtcm_bench.py"],
        "deps": [
            ":i8_gemv_common",
            "//coralnpu_test_utils:sim_test_fixture",
            "@bazel_tools//tools/python/runfiles",
        ],
        "data": [
            ":i8_gemv_baseline_dtcm.elf",
            ":i8_gemv_opt_dtcm.elf",
        ],
        "size": "enormous",
    },
    verilator_model = "//tests/cocotb:rvv_core_mini_itcm512kb_dtcm512kb_axi_model",
)

# Memory-streaming configuration: weights in DDR at 0x80000000, streamed over
# the AXI master.
cocotb_test_suite(
    name = "i8_gemv_ddr_test_suite",
    simulators = [
        "verilator",
        "vcs",
    ],
    testcases = [
        "i8_gemv_baseline_ddr_test",
        "i8_gemv_opt_ddr_test",
    ],
    tests_kwargs = {
        "hdl_toplevel": "RvvCoreMiniHighmemAxi",
        "waves": False,
        "seed": "42",
        "test_module": ["i8_gemv_ddr_bench.py"],
        "deps": [
            ":i8_gemv_common",
            "//coralnpu_test_utils:sim_test_fixture",
            "@bazel_tools//tools/python/runfiles",
        ],
        "data": [
            ":i8_gemv_baseline_ddr.elf",
            ":i8_gemv_opt_ddr.elf",
        ],
        "size": "enormous",
    },
    vcs_build_args = VCS_BUILD_ARGS,
    vcs_data = [
        ":i8_gemv_baseline_ddr.elf",
        ":i8_gemv_opt_ddr.elf",
        "//tests/cocotb:xprop.cfg",
        "@coralnpu_hw//hdl/verilog:dpi_files",
    ],
    vcs_defines = VCS_DEFINES,
    vcs_test_args = VCS_TEST_ARGS,
    vcs_verilog_sources = ["//hdl/chisel/src/coralnpu:rvv_core_mini_highmem_axi_cc_library_verilog"],
    verilator_model = "//tests/cocotb:rvv_core_mini_highmem_axi_model",
)
