#!/bin/bash
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
#
# uvm_test_runner.sh <sim rlocationpath> <elf rlocationpath> [plusargs...]
#
# Runs a UVM simulation binary against an ELF and derives pass/fail from
# the UVM report in the log: the simulators exit 0 whether the UVM test
# passed or failed, so the exit code alone is meaningless.

# --- Begin Bazel Bash Runfiles Library Boilerplate ---
# Sourced from @bazel_tools//tools/bash/runfiles
if [[ -z "${RUNFILES_DIR:-}" ]]; then
  if [[ -n "${PYTHON_RUNFILES:-}" ]]; then
    export RUNFILES_DIR="$PYTHON_RUNFILES"
  elif [[ -n "${TEST_SRCDIR:-}" ]]; then
    export RUNFILES_DIR="$TEST_SRCDIR"
  fi
fi
if [[ -n "${RUNFILES_DIR:-}" ]]; then
  export RUNFILES_MANIFEST_FILE="${RUNFILES_DIR}_manifest"
fi
# --- End Bazel Bash Runfiles Library Boilerplate ---

# Locate runfiles
function rlocation() {
  local path="$1"
  if [[ -f "${RUNFILES_DIR}/${path}" ]]; then
    echo "${RUNFILES_DIR}/${path}"
  elif [[ -f "${RUNFILES_MANIFEST_FILE}" ]]; then
    grep -m1 "^${path} " "${RUNFILES_MANIFEST_FILE}" | cut -d' ' -f2-
  else
    echo "Error: Runfiles not found" >&2
    exit 1
  fi
}

SIM=$(rlocation "$1")
ELF=$(rlocation "$2")
shift 2

if [[ -z "${SIM}" || ! -x "${SIM}" ]]; then
  echo "Error: simulator binary '$1' not found or not executable" >&2
  exit 1
fi
if [[ -z "${ELF}" || ! -f "${ELF}" ]]; then
  echo "Error: test ELF not found" >&2
  exit 1
fi

LOG="${TEST_UNDECLARED_OUTPUTS_DIR:-${TEST_TMPDIR:-.}}/sim.log"

echo "Running: ${SIM} $* +TEST_ELF=${ELF}"
"${SIM}" "$@" +TEST_ELF="${ELF}" 2>&1 | tee "${LOG}"
EXIT_CODE=${PIPESTATUS[0]}

if [[ ${EXIT_CODE} -ne 0 ]]; then
  echo "UVM test FAILED: simulator exited with code ${EXIT_CODE}" >&2
  exit "${EXIT_CODE}"
fi
if grep -qF 'UVM TEST FAILED' "${LOG}"; then
  echo "UVM test FAILED: log contains UVM TEST FAILED" >&2
  exit 1
fi
if grep -qE 'UVM_FATAL :\s*[1-9]' "${LOG}"; then
  echo "UVM test FAILED: nonzero UVM_FATAL count" >&2
  exit 1
fi
if ! grep -qF '** UVM TEST PASSED **' "${LOG}"; then
  echo "UVM test FAILED: no '** UVM TEST PASSED **' in log" >&2
  exit 1
fi

echo "UVM test PASSED"
