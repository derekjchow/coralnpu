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

load("//rules:mpact.bzl", "mpact_binary")
load("//rules:uvm.bzl", "uvm_binaries", "uvm_test_suite")

package(default_visibility = ["//visibility:public"])

exports_files(["uvm_test_runner.sh"])

# MPACT-Sim cosim DPI library, built with the clang host toolchain that
# MPACT requires.
mpact_binary(
    name = "cosim_lib_clang",
    dep = "@coralnpu_mpact//sim/cosim:coralnpu_cosim_lib",
)

# Testbench compile units in compilation order (interfaces, then packages
# in dependency order, then the top). The order is load-bearing.
UVM_TB_SRCS = [
    "common/coralnpu_axi_master/coralnpu_axi_master_if.sv",
    "common/coralnpu_axi_slave/coralnpu_axi_slave_if.sv",
    "common/coralnpu_irq/coralnpu_irq_if.sv",
    "common/cosim/coralnpu_cosim_dpi_if.sv",
    "common/memory_map_pkg.sv",
    "common/transaction_item/transaction_item_pkg.sv",
    "common/coralnpu_axi_master/coralnpu_axi_master_agent_pkg.sv",
    "common/coralnpu_axi_slave/coralnpu_axi_slave_agent_pkg.sv",
    "common/coralnpu_irq/coralnpu_irq_agent_pkg.sv",
    "common/cosim/coralnpu_rvvi_agent_pkg.sv",
    "common/cosim/coralnpu_cosim_checker_pkg.sv",
    "env/coralnpu_env_pkg.sv",
    "tests/coralnpu_test_pkg.sv",
    "tb/coralnpu_tb_top.sv",
]

# Files `include`d by the packages above; needed as action inputs but not
# compiled standalone. common/rvvi_agent is dead code and excluded.
filegroup(
    name = "uvm_tb_hdrs",
    srcs = glob(
        [
            "common/**/*.sv",
            "common/**/*.svh",
            "env/*.sv",
            "tests/*.sv",
            "tb/*.sv",
        ],
        exclude = UVM_TB_SRCS + ["common/rvvi_agent/**"],
    ),
)

UVM_INCDIRS = [
    "tests/uvm/common",
    "tests/uvm/common/coralnpu_axi_master",
    "tests/uvm/common/coralnpu_axi_slave",
    "tests/uvm/common/coralnpu_irq",
    "tests/uvm/common/transaction_item",
    "tests/uvm/common/cosim",
    "tests/uvm/env",
    "tests/uvm/tests",
    "tests/uvm/tb",
    ".",
]

UVM_DEFINES = [
    "USE_GENERIC",
    "UVM_NO_DEPRECATED",
    "TB_SUPPORT",
    "VLEN_128",
    "ZVE32F_ON",
]

uvm_binaries(
    name = "coralnpu_uvm",
    hdrs = [":uvm_tb_hdrs"],
    cc_deps = [
        ":cosim_lib_clang",
        "//hdl/verilog:sram_backdoor",
    ],
    defines = UVM_DEFINES,
    dut_verilog = "//hdl/chisel/src/coralnpu:rvv_core_mini_verification_axi_cc_library_verilog",
    incdirs = UVM_INCDIRS,
    sv_srcs = UVM_TB_SRCS,
    top = "coralnpu_tb_top",
)

uvm_test_suite(
    name = "uvm",
    binaries = ":coralnpu_uvm",
    tests = {
        "base_test_nop": {
            "elf": "//tests/cocotb:nop_test.elf",
        },
    },
)
