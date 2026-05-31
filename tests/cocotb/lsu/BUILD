# Copyright 2025 Google LLC
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

load("@coralnpu_hw//rules:coco_tb.bzl", "cocotb_test_suite", "verilator_cocotb_model")
load(
    "//tests/cocotb:build_defs.bzl",
    "VCS_BUILD_ARGS",
    "VCS_DEFINES",
    "VCS_TEST_ARGS",
    "VERILATOR_BUILD_ARGS",
)

package(default_visibility = ["//visibility:public"])

verilator_cocotb_model(
    name = "lsu_v3_model",
    cflags = VERILATOR_BUILD_ARGS,
    hdl_toplevel = "LsuV3",
    trace = True,
    verilog_source = "//hdl/chisel/src/coralnpu:LsuV3.sv",
)

# BEGIN_TESTCASES_FOR_lsu_v3_cocotb_test
LSU_V3_TESTCASES = [
    "test_scalar_load_word",
    "test_scalar_store_word",
    "test_scalar_load_sext",
    "test_vector_unit_load",
    "test_vector_unit_store",
    "test_vector_strided_load",
    "test_vector_strided_store",
    "test_pingpong",
    "test_outstanding_reorder",
    "test_indexed_load_eq",
    "test_indexed_store_eq",
    "test_indexed_load_narrow",
    "test_indexed_store_narrow",
    "test_indexed_load_wide",
    "test_indexed_store_wide",
    "test_indexed_segmented",
    "test_lsu_v3_random",
]
# END_TESTCASES_FOR_lsu_v3_cocotb_test

cocotb_test_suite(
    name = "lsu_v3_cocotb_test",
    simulators = ["verilator", "vcs"],
    testcases = LSU_V3_TESTCASES,
    testcases_vname = "LSU_V3_TESTCASES",
    tests_kwargs = {
        "hdl_toplevel": "LsuV3",
        "test_module": ["test_lsu_v3.py"],
        "deps": [
            "//coralnpu_test_utils:TileLinkULInterface",
        ],
        "seed": "42",
        "waves": True,
        "size": "large",
    },
    verilator_model = ":lsu_v3_model",
    vcs_verilog_sources = ["//hdl/chisel/src/coralnpu:lsu_v3_cc_library_verilog"],
    vcs_build_args = VCS_BUILD_ARGS,
    vcs_test_args = VCS_TEST_ARGS,
    vcs_defines = VCS_DEFINES,
    vcs_data = ["//hdl/verilog:dpi_files"],
)
