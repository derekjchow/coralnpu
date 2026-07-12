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

"""Bazel rules for building and running UVM testbenches.

Provides a Verilator (--binary mode, using the @uvm sources) and a VCS
(-ntb_opts uvm-1.2) build of the same testbench, plus a test-suite macro
that runs a UVM test against an ELF and greps the log for the UVM
pass/fail verdict (the simulators exit 0 either way).
"""

load("@coralnpu_host_cpus//:defs.bzl", "MAKE_JOBS")
load("@coralnpu_hw//rules:vcs.bzl", "vcs_binary")
load("@coralnpu_hw//rules:verilog.bzl", "collect_verilog_files")
load("@rules_cc//cc:find_cc_toolchain.bzl", "find_cc_toolchain")
load("@rules_cc//cc/common:cc_info.bzl", "CcInfo")
load("@rules_hdl//verilog:providers.bzl", "VerilogInfo")

def _verilator_resource_estimator(_os, _input_size):
    # Cap the scheduler reservation at 4 so multiple actions can still run
    # in parallel on larger hosts (same policy as coco_tb.bzl).
    return {"cpu": min(MAKE_JOBS, 4), "memory": 4096}

def _uvm_verilator_binary_impl(ctx):
    cc_toolchain = find_cc_toolchain(ctx)

    # C++/DPI dependencies: static archives are linked into the simulation
    # binary, headers become action inputs.
    libs = []
    objects = []
    headers_depsets = []
    for dep in ctx.attr.deps:
        headers_depsets.append(dep[CcInfo].compilation_context.headers)
        transitive_linker_inputs = depset(
            [],
            transitive = [dep[CcInfo].linking_context.linker_inputs],
        )
        for link in transitive_linker_inputs.to_list():
            for library in link.libraries:
                if library.pic_static_library:
                    libs.append(library.pic_static_library)
                elif library.static_library:
                    libs.append(library.static_library)
                elif library.pic_objects:
                    objects.extend(library.pic_objects)
                elif library.objects:
                    objects.extend(library.objects)

    # Locate the UVM package and its DPI implementation in @uvm.
    uvm_pkg = None
    uvm_dpi = None
    for f in ctx.files._uvm:
        if f.path.endswith("/src/uvm_pkg.sv"):
            uvm_pkg = f
        elif f.path.endswith("/src/dpi/uvm_dpi.cc"):
            uvm_dpi = f
    if not uvm_pkg or not uvm_dpi:
        fail("Could not find src/uvm_pkg.sv / src/dpi/uvm_dpi.cc in @uvm")

    dut_verilog = collect_verilog_files(ctx.attr.verilog_deps).to_list()
    dut_files = [f for f in dut_verilog if f.extension in ["v", "sv"]]

    simv = ctx.actions.declare_file(ctx.attr.name + "_build/simv")
    outdir = simv.dirname

    # The @verilator runfiles live at <bin>.runfiles/<canonical> (see
    # coco_tb.bzl for details on the canonical-name resolution).
    verilator_canonical = ctx.executable._verilator_bin.owner.workspace_name
    verilator_root = "$PWD/{}.runfiles/{}".format(
        ctx.executable._verilator_bin.path,
        verilator_canonical,
    )

    def _abs(p):
        return p if p.startswith("/") else "$PWD/" + p

    ldflags = ""
    if libs or objects:
        ldflags = "-LDFLAGS \"-Wl,--start-group {} -Wl,--end-group\"".format(
            " ".join([_abs(f.path) for f in libs + objects]),
        )

    command = " ".join(
        [
            "PATH=$(dirname {}):$PATH".format(_abs(cc_toolchain.ld_executable)),
            "VERILATOR_ROOT=" + verilator_root,
            ctx.executable._verilator_bin.path,
            "--binary",
            "--build-jobs {}".format(MAKE_JOBS),
            "-Mdir " + outdir,
            "--top " + ctx.attr.top,
            "-o simv",
            "--vpi",
            "--timescale 1ns/1ps",
            "-Wno-PINMISSING -Wno-lint -Wno-style -Wno-fatal",
        ] +
        ["+define+" + d for d in ctx.attr.defines] +
        ["+incdir+" + i for i in ctx.attr.incdirs] +
        ctx.attr.vopts +
        [
            "-CFLAGS -std=c++17",
            ldflags,
            "+incdir+" + uvm_pkg.dirname,
            _abs(uvm_pkg.path),
            _abs(uvm_dpi.path),
        ] +
        [_abs(f.path) for f in dut_files] +
        [_abs(f.path) for f in ctx.files.sv_srcs] +
        [
            "-MAKEFLAGS CXX={}".format(_abs(cc_toolchain.compiler_executable)),
            "-MAKEFLAGS LINK={}".format(_abs(cc_toolchain.compiler_executable)),
            "-MAKEFLAGS AR={}".format(_abs(cc_toolchain.ar_executable)),
        ],
    )

    ctx.actions.run_shell(
        outputs = [simv],
        tools = ctx.files._verilator_bin + ctx.files._verilator,
        inputs = depset(
            dut_files + ctx.files.sv_srcs + ctx.files.hdrs +
            ctx.files._uvm + libs + objects,
            transitive = headers_depsets + [cc_toolchain.all_files],
        ),
        command = command,
        mnemonic = "VerilateUvm",
        resource_set = _verilator_resource_estimator,
    )

    return [DefaultInfo(
        files = depset([simv]),
        executable = simv,
    )]

uvm_verilator_binary = rule(
    doc = "Builds a standalone Verilator simulation binary of a UVM testbench.",
    implementation = _uvm_verilator_binary_impl,
    attrs = {
        "top": attr.string(
            doc = "Top-level testbench module name.",
            mandatory = True,
        ),
        "sv_srcs": attr.label_list(
            doc = "Testbench compile units, in compilation order.",
            allow_files = True,
        ),
        "hdrs": attr.label_list(
            doc = "Files `include`d by sv_srcs; inputs only, not compile units.",
            allow_files = True,
        ),
        "incdirs": attr.string_list(
            doc = "Workspace-relative +incdir+ directories.",
        ),
        "defines": attr.string_list(),
        "verilog_deps": attr.label_list(
            doc = "DUT verilog libraries.",
            providers = [VerilogInfo],
        ),
        "deps": attr.label_list(
            doc = "C++ DPI dependencies.",
            providers = [CcInfo],
        ),
        "vopts": attr.string_list(
            doc = "Extra verilator flags.",
        ),
        "_uvm": attr.label(default = "@uvm//:uvm_src"),
        "_verilator": attr.label(
            default = "@verilator//:verilator",
            executable = True,
            cfg = "exec",
        ),
        "_verilator_bin": attr.label(
            default = "@verilator//:verilator_bin",
            executable = True,
            cfg = "exec",
        ),
    },
    executable = True,
    toolchains = ["@bazel_tools//tools/cpp:toolchain_type"],
)

def uvm_binaries(
        name,
        top,
        dut_verilog,
        sv_srcs,
        hdrs,
        incdirs,
        defines,
        cc_deps,
        vcs_build_args = [],
        verilator_vopts = []):
    """Builds <name>_verilator and <name>_vcs simulators of one UVM testbench.

    Args:
        name: Base name; the simulator targets get _verilator/_vcs suffixes.
        top: Top-level testbench module.
        dut_verilog: verilog_library target of the generated DUT.
        sv_srcs: Ordered testbench compile units.
        hdrs: `include`d testbench files (not compiled standalone).
        incdirs: Workspace-relative include directories.
        defines: Preprocessor defines (without the +define+ prefix).
        cc_deps: C++ DPI dependencies (CcInfo).
        vcs_build_args: Extra VCS build arguments.
        verilator_vopts: Extra verilator flags.
    """
    uvm_verilator_binary(
        name = name + "_verilator",
        top = top,
        sv_srcs = sv_srcs,
        hdrs = hdrs,
        incdirs = incdirs,
        defines = defines,
        verilog_deps = [dut_verilog],
        deps = cc_deps,
        vopts = verilator_vopts,
    )

    vcs_binary(
        name = name + "_vcs",
        build_args = ["-ntb_opts", "uvm-1.2"] +
                     ["+define+" + d for d in defines] +
                     ["+incdir+" + i for i in incdirs] +
                     vcs_build_args,
        # srcs keep their order and are appended after the DUT verilog.
        srcs = sv_srcs,
        hdrs = hdrs,
        verilog_deps = [dut_verilog],
        deps = cc_deps,
    )

def uvm_test_suite(
        name,
        binaries,
        tests,
        simulators = ["verilator", "vcs"],
        runner = "//tests/uvm:uvm_test_runner.sh",
        size = "medium",
        tags = []):
    """Generates sh_test targets running UVM tests on each simulator.

    Follows the cocotb_test_suite naming convention: the verilator test is
    `<name>_<testcase>`, the VCS test is `vcs_<name>_<testcase>` and tagged
    "vcs" so it is filtered out of default builds.

    Args:
        name: Suite base name.
        binaries: Base name/label of the uvm_binaries pair (without the
            _verilator/_vcs suffix).
        tests: Dict testcase name -> config dict with keys: elf (label of
            the .elf to run, required), uvm_test (UVM_TESTNAME, default
            coralnpu_base_test), timeout_ns (default 100000), verbosity
            (default UVM_MEDIUM), extra_plusargs (list), data (extra data
            labels).
        simulators: Subset of ["verilator", "vcs"].
        runner: The runner script label.
        size: Test size.
        tags: Extra tags for all generated tests.
    """
    for testcase, config in tests.items():
        elf = config["elf"]
        plusargs = [
            "+UVM_TESTNAME=" + config.get("uvm_test", "coralnpu_base_test"),
            "+UVM_VERBOSITY=" + config.get("verbosity", "UVM_MEDIUM"),
            "+TEST_TIMEOUT=" + str(config.get("timeout_ns", 100000)),
        ] + config.get("extra_plusargs", [])
        for simulator in simulators:
            prefix = "vcs_" if simulator == "vcs" else ""
            simulator_binary = "{}_{}".format(binaries, simulator)
            native.sh_test(
                name = "{}{}_{}".format(prefix, name, testcase),
                srcs = [runner],
                args = [
                    "$(rlocationpath {})".format(simulator_binary),
                    "$(rlocationpath {})".format(elf),
                ] + plusargs,
                data = [simulator_binary, elf] + config.get("data", []),
                size = size,
                tags = (["vcs"] if simulator == "vcs" else []) + tags,
            )
