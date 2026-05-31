# Copyright 2025 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Constrained-random cocotb testbench for the LsuV3 load/store unit.

The DUT exposes a single consolidated TileLink-UL master port (`io_tl`).  A
device-side memory model (built on `TileLinkULInterface`) answers every A
request from a sparse byte dictionary, which doubles as the scoreboard's
ground truth.  Stimulus is issued through the command interface
(`io_req` + `io_rvvState` + `io_busPort`); vector store data is fed on
`io_rvv2lsu`, and results are observed on `io_lsu2rvv` (vector) or `io_rd`
(scalar).

Agents (background coroutines):
  * device_model      - serves io_tl A/D, owns the golden memory.
  * lsu2rvv monitor    - captures vector writeback beats per channel.
  * rd / storeComplete monitor - captures scalar writeback + completions.
The LsuV3Tb object is the driver + scoreboard: it issues an op, predicts the
expected effect from the golden memory and the RVV address math, and checks.
"""

import random
import enum

import cocotb
from cocotb.clock import Clock
from cocotb.queue import Queue
from cocotb.triggers import RisingEdge, FallingEdge, ClockCycles, with_timeout

from coralnpu_test_utils.TileLinkULInterface import TileLinkULInterface


# --- LsuOp ordinals.  Must mirror LsuOp in hdl/.../scalar/Lsu.scala:72. ---
class LsuOp(enum.IntEnum):
    LB = 0
    LH = 1
    LW = 2
    LBU = 3
    LHU = 4
    SB = 5
    SH = 6
    SW = 7
    FENCEI = 8
    FLUSHAT = 9
    FLUSHALL = 10
    VLDST = 11
    FLOAT = 12
    VLOAD_UNIT = 13
    VLOAD_STRIDED = 14
    VLOAD_OINDEXED = 15
    VLOAD_UINDEXED = 16
    VSTORE_UNIT = 17
    VSTORE_STRIDED = 18
    VSTORE_OINDEXED = 19
    VSTORE_UINDEXED = 20


# TileLink-UL A/D opcodes.
TL_GET = 4
TL_PUTFULL = 0
TL_PUTPARTIAL = 1
TL_ACCESSACK = 0
TL_ACCESSACKDATA = 1

VLENB = 16            # rvvVlenb
DLENB = 16            # axi2DataBytes with lsuDataBits=128 (== TL beat bytes)
LINE_BITS = 4         # log2(DLENB)
TL_SIZE = 4           # log2Ceil(DLENB)
REGION_BASE = 0x1000
REGION_SIZE = 0x4000

# SEW/EEW byte width -> (eew encoding, sew encoding) for the eew == sew case.
SEW_CODES = {1: (0b000, 0b000), 2: (0b101, 0b001), 4: (0b110, 0b010)}


def to_int(sig):
    """Read a DUT signal, returning None if it currently holds X/Z."""
    try:
        return int(sig.value)
    except Exception:
        return None


def sext(value, bits):
    if value & (1 << (bits - 1)):
        return value - (1 << bits)
    return value & 0xFFFFFFFF


def read_line(mem, addr):
    """Read one DLENB-byte line as a little-endian integer."""
    return sum(mem.get(addr + i, 0) << (8 * i) for i in range(DLENB))


def write_line(mem, addr, data, mask):
    for i in range(DLENB):
        if (mask >> i) & 1:
            mem[addr + i] = (data >> (8 * i)) & 0xFF


def addr_beats(base, stride, nf, eb, nbeats):
    """Replicate NonIndexedAddrGenerator: yield each beat's VLENB addresses.

    Beat 0:  addr[j] = base + (j // eb) * stride + (j % eb)
    Update:  segment 0..nf-1 then lmul++, matching the RTL deltas.
    """
    er = VLENB // eb
    addrs = [(base + (j // eb) * stride + (j % eb)) & 0xFFFFFFFF
             for j in range(VLENB)]
    seg = 0
    for _ in range(nbeats):
        yield list(addrs)
        if seg == nf - 1:
            delta = (er * stride - (nf - 1) * eb) & 0xFFFFFFFF
            seg = 0
        else:
            delta = eb
            seg += 1
        addrs = [(a + delta) & 0xFFFFFFFF for a in addrs]


class TileLinkULNoUser(TileLinkULInterface):
    """Device-side TL-UL agent for a NoUser port (no integrity user fields)."""

    async def _device_d_driver(self, prefix, timeout=4096):
        d_valid = getattr(self.dut, f"{prefix}_d_valid")
        d_ready = getattr(self.dut, f"{prefix}_d_ready")
        d_valid.value = 0
        for prop in ["opcode", "param", "size", "source", "sink", "data",
                     "error"]:
            getattr(self.dut, f"{prefix}_d_bits_{prop}").value = 0
        while True:
            while True:
                await RisingEdge(self.clock)
                d_valid.value = 0
                if self.device_d_fifo.qsize():
                    break
            txn = await self.device_d_fifo.get()
            d_valid.value = 1
            for prop in ["opcode", "param", "size", "source", "sink", "data",
                         "error"]:
                getattr(self.dut, f"{prefix}_d_bits_{prop}").value = txn[prop]
            await FallingEdge(self.clock)
            count = 0
            while d_ready.value == 0:
                await FallingEdge(self.clock)
                count += 1
                assert count < timeout, "timeout waiting for d_ready"

    async def device_respond(self, opcode, param, size, source, sink=0,
                             data=0, error=0, width=None):
        await self.device_d_fifo.put({
            "opcode": opcode, "param": param, "size": size, "source": source,
            "sink": sink, "data": data, "error": error,
        })


class LsuV3Tb:
    """Driver + scoreboard around the LsuV3 DUT."""

    def __init__(self, dut, reorder=0):
        self.dut = dut
        self.clock = dut.clock
        self.mem = {}
        self.tl = TileLinkULNoUser(dut, device_if_name="io_tl", width=128)
        self.lsu2rvv_q = [Queue(), Queue()]
        self.rd_q = Queue()
        self.store_complete_q = Queue()
        self._agents = [
            cocotb.start_soon(self._device_model(reorder)),
            cocotb.start_soon(self._lsu2rvv_monitor(0)),
            cocotb.start_soon(self._lsu2rvv_monitor(1)),
            cocotb.start_soon(self._rd_monitor()),
            cocotb.start_soon(self._store_complete_monitor()),
        ]

    # ---- Agents ----
    async def _device_model(self, reorder):
        """Serve io_tl: reads from / writes to the golden memory."""
        pending = []
        while True:
            req = await self.tl.device_get_request()
            opcode = int(req["opcode"])
            addr = int(req["address"])
            size = int(req["size"])
            source = int(req["source"])
            if opcode == TL_GET:
                rsp = dict(opcode=TL_ACCESSACKDATA, param=0, size=size,
                           source=source, data=read_line(self.mem, addr))
            else:
                write_line(self.mem, addr, int(req["data"]), int(req["mask"]))
                rsp = dict(opcode=TL_ACCESSACK, param=0, size=size,
                           source=source)
            pending.append(rsp)
            # Optionally hold `reorder` responses and emit them reversed, to
            # exercise out-of-order completion against the source-tag table.
            if len(pending) > reorder:
                for r in reversed(pending):
                    await self.tl.device_respond(**r)
                pending = []

    async def _lsu2rvv_monitor(self, ch):
        valid = getattr(self.dut, f"io_lsu2rvv_{ch}_valid")
        ready = getattr(self.dut, f"io_lsu2rvv_{ch}_ready")
        ready.value = 1
        while True:
            await RisingEdge(self.clock)
            if valid.value == 1:
                data = to_int(getattr(self.dut, f"io_lsu2rvv_{ch}_bits_data"))
                addr = to_int(getattr(self.dut, f"io_lsu2rvv_{ch}_bits_addr"))
                last = to_int(getattr(self.dut, f"io_lsu2rvv_{ch}_bits_last"))
                await self.lsu2rvv_q[ch].put((addr, data, last))

    async def _rd_monitor(self):
        while True:
            await RisingEdge(self.clock)
            if self.dut.io_rd_valid.value == 1:
                await self.rd_q.put((to_int(self.dut.io_rd_bits_addr),
                                     to_int(self.dut.io_rd_bits_data)))

    async def _store_complete_monitor(self):
        while True:
            await RisingEdge(self.clock)
            if self.dut.io_storeComplete_valid.value == 1:
                await self.store_complete_q.put(
                    to_int(self.dut.io_storeComplete_bits))

    # ---- Drivers ----
    async def _issue_req(self, op, store, rd, addr, data, *, eew=0, nf_field=0,
                         sew=0, lmul=0):
        d = self.dut
        d.io_busPort_addr_0.value = addr
        d.io_busPort_data_0.value = data
        d.io_rvvState_valid.value = 1
        d.io_rvvState_bits_sew.value = sew
        d.io_rvvState_bits_lmul.value = lmul
        d.io_rvvState_bits_lmul_orig.value = lmul
        d.io_rvvState_bits_vl.value = 0
        d.io_rvvState_bits_vstart.value = 0
        d.io_rvvState_bits_ma.value = 0
        d.io_rvvState_bits_ta.value = 0
        d.io_rvvState_bits_xrm.value = 0
        d.io_rvvState_bits_vill.value = 0
        d.io_req_0_valid.value = 1
        d.io_req_0_bits_op.value = int(op)
        d.io_req_0_bits_store.value = 1 if store else 0
        d.io_req_0_bits_addr.value = rd
        d.io_req_0_bits_pc.value = 0
        d.io_req_0_bits_elemWidth.value = eew
        d.io_req_0_bits_nfields.value = nf_field
        d.io_req_0_bits_umop.value = 0
        while True:
            await RisingEdge(self.clock)
            if d.io_req_0_ready.value == 1:
                break
        d.io_req_0_valid.value = 0

    async def _drive_rvv_beats(self, ch, datas, idx_datas=None, masks=None):
        d = self.dut
        valid = getattr(d, f"io_rvv2lsu_{ch}_valid")
        ready = getattr(d, f"io_rvv2lsu_{ch}_ready")
        for k, payload in enumerate(datas):
            valid.value = 1
            getattr(d, f"io_rvv2lsu_{ch}_bits_vregfile_valid").value = 1
            getattr(d, f"io_rvv2lsu_{ch}_bits_vregfile_bits_addr").value = 0
            getattr(d, f"io_rvv2lsu_{ch}_bits_vregfile_bits_data").value = payload
            getattr(d, f"io_rvv2lsu_{ch}_bits_mask_valid").value = 1
            getattr(d, f"io_rvv2lsu_{ch}_bits_mask_bits").value = \
                (masks[k] if masks else 0xFFFF)
            if idx_datas is not None:
                getattr(d, f"io_rvv2lsu_{ch}_bits_idx_valid").value = 1
                getattr(d, f"io_rvv2lsu_{ch}_bits_idx_bits_data").value = \
                    idx_datas[k]
            else:
                getattr(d, f"io_rvv2lsu_{ch}_bits_idx_valid").value = 0
            while True:
                await RisingEdge(self.clock)
                if ready.value == 1:
                    break
            valid.value = 0
        valid.value = 0

    # ---- Scalar op ----
    async def scalar(self, op, addr, rd=1, store_data=0):
        nbytes = {LsuOp.LB: 1, LsuOp.LBU: 1, LsuOp.SB: 1,
                  LsuOp.LH: 2, LsuOp.LHU: 2, LsuOp.SH: 2,
                  LsuOp.LW: 4, LsuOp.SW: 4}[op]
        store = op in (LsuOp.SB, LsuOp.SH, LsuOp.SW)
        await self._issue_req(op, store, rd, addr, store_data)
        if store:
            write = [(addr + i, (store_data >> (8 * i)) & 0xFF)
                     for i in range(nbytes)]
            pc = await with_timeout(self.store_complete_q.get(), 2000, "us")
            for a, v in write:
                assert self.mem.get(a, 0) == v, (
                    f"scalar store mem[{a:#x}]={self.mem.get(a,0):#x} != {v:#x}")
            return pc
        # Load: assemble expected value from memory and check rd.
        raw = sum(self.mem.get(addr + i, 0) << (8 * i) for i in range(nbytes))
        if op == LsuOp.LB:
            expect = sext(raw, 8)
        elif op == LsuOp.LH:
            expect = sext(raw, 16)
        else:
            expect = raw & 0xFFFFFFFF
        got = await with_timeout(self.rd_q.get(), 2000, "us")
        assert got[0] == rd, f"rd addr {got[0]} != {rd}"
        assert (got[1] & 0xFFFFFFFF) == (expect & 0xFFFFFFFF), (
            f"{op.name} @ {addr:#x}: rd={got[1]:#x} expected {expect & 0xFFFFFFFF:#x}")

    # ---- Vector unit-stride / strided op ----
    #
    # Note on channels: the DUT picks the slot (lowest free), and slot `i` is
    # bound to channel `i`.  A single op issued while both slots are free lands
    # in slot 0 / channel 0; to exercise slot 1 / channel 1, issue a second op
    # (via _vec_issue) before the first completes.
    async def _vec_issue(self, op, base, sew_bytes, nf, lmul, ch, rd, stride,
                         store_data):
        store = op in (LsuOp.VSTORE_UNIT, LsuOp.VSTORE_STRIDED)
        eew, sew = SEW_CODES[sew_bytes]
        eb = sew_bytes
        beats = nf * (1 << lmul)
        is_strided = op in (LsuOp.VLOAD_STRIDED, LsuOp.VSTORE_STRIDED)
        eff_stride = stride if is_strided else nf * eb
        all_addrs = list(addr_beats(base, eff_stride, nf, eb, beats))
        # busPort.data carries the architected stride for strided ops.
        bus_data = eff_stride if is_strided else 0
        await self._issue_req(op, store, rd, base, bus_data,
                              eew=eew, nf_field=nf - 1, sew=sew, lmul=lmul)
        if store_data is None:
            store_data = [random.getrandbits(128) for _ in range(beats)]
        # Loads still consume `beats` fill beats (mask + address generation);
        # their vregfile data is ignored.
        drv = cocotb.start_soon(self._drive_rvv_beats(ch, store_data))
        return dict(store=store, beats=beats, all_addrs=all_addrs, ch=ch,
                    store_data=store_data, drv=drv)

    async def _vec_finish(self, ctx):
        ch, beats, all_addrs = ctx["ch"], ctx["beats"], ctx["all_addrs"]
        if ctx["store"]:
            expect = {}
            for k in range(beats):
                for j in range(VLENB):
                    expect[all_addrs[k][j]] = \
                        (ctx["store_data"][k] >> (8 * j)) & 0xFF
            # Drain beats (store completion handshake) then storeComplete.
            for _ in range(beats):
                await with_timeout(self.lsu2rvv_q[ch].get(), 4000, "us")
            await with_timeout(self.store_complete_q.get(), 4000, "us")
            await ctx["drv"]
            for a, v in expect.items():
                assert self.mem.get(a, 0) == v, (
                    f"vstore mem[{a:#x}]={self.mem.get(a,0):#x} != {v:#x}")
        else:
            await ctx["drv"]
            for k in range(beats):
                _, data, last = await with_timeout(
                    self.lsu2rvv_q[ch].get(), 4000, "us")
                expect = sum(self.mem.get(all_addrs[k][j], 0) << (8 * j)
                             for j in range(VLENB))
                assert data == expect, (
                    f"vload beat {k}: data={data:#034x} expected {expect:#034x}")
                # Loads keep last deasserted (the RvvCore counts beats itself).
                assert last == 0, f"vload beat {k}: last={last}"

    async def vector(self, op, base, sew_bytes, nf=1, lmul=0, ch=0, rd=1,
                     stride=None, store_data=None):
        ctx = await self._vec_issue(op, base, sew_bytes, nf, lmul, ch, rd,
                                    stride, store_data)
        await self._vec_finish(ctx)

    # ---- Indexed vector op (full mixed EEW/SEW). ----
    #
    # Fill beats are sequenced (lmul, segment, sub).  `sub` (0..elemMul-1)
    # exists only for EEW > SEW, where one data register's indices span several
    # 128-bit index beats.  Distinct element indices (multiples of nf*SEW) keep
    # every element's address range disjoint, so indexed stores are
    # unambiguous and the golden is exact.
    async def indexed(self, op, base, sew_bytes, eew_bytes, nf=1, lmul=0,
                      ch=0, rd=1, rng=None):
        store = op in (LsuOp.VSTORE_OINDEXED, LsuOp.VSTORE_UINDEXED)
        eb, ib = sew_bytes, eew_bytes
        er_data = VLENB // eb
        emul = 1 << lmul
        total_elems = emul * er_data
        step_elems = VLENB // max(eb, ib)
        elem_mul = max(1, ib // eb)
        drain_beats = nf * emul
        rng = rng or random.Random(0)

        span = nf * eb
        idx_cap = min(REGION_SIZE // 2, 1 << (8 * ib))
        n_slots = idx_cap // span
        assert n_slots > total_elems, "test region too small for index count"
        indices = [c * span for c in rng.sample(range(1, n_slots), total_elems)]
        # Per-segment store data: total_elems*eb bytes (one per element byte).
        sdata = [[rng.randrange(256) for _ in range(total_elems * eb)]
                 for _ in range(nf)]

        eew_code = SEW_CODES[eew_bytes][0]
        sew_code = SEW_CODES[sew_bytes][1]
        await self._issue_req(op, store, rd, base, 0, eew=eew_code,
                              nf_field=nf - 1, sew=sew_code, lmul=lmul)

        idx_datas, vdatas, masks = [], [], []
        for l in range(emul):
            for seg in range(nf):
                for sub in range(elem_mul):
                    idxd = vd = 0
                    for m in range(step_elems):
                        ve = l * er_data + sub * step_elems + m
                        idxd |= indices[ve] << (8 * ib * m)
                        for b in range(eb):
                            vd |= sdata[seg][ve * eb + b] << (8 * (m * eb + b))
                    idx_datas.append(idxd)
                    vdatas.append(vd)
                    masks.append((1 << (step_elems * eb)) - 1)

        drv = cocotb.start_soon(
            self._drive_rvv_beats(ch, vdatas, idx_datas=idx_datas, masks=masks))

        if store:
            expect = {}
            for l in range(emul):
                for seg in range(nf):
                    for eir in range(er_data):
                        ve = l * er_data + eir
                        for b in range(eb):
                            a = (base + seg * eb + indices[ve] + b) & 0xFFFFFFFF
                            expect[a] = sdata[seg][ve * eb + b]
            for _ in range(drain_beats):
                await with_timeout(self.lsu2rvv_q[ch].get(), 8000, "us")
            await with_timeout(self.store_complete_q.get(), 8000, "us")
            await drv
            for a, v in expect.items():
                assert self.mem.get(a, 0) == v, (
                    f"vstore-idx mem[{a:#x}]={self.mem.get(a,0):#x} != {v:#x}")
        else:
            await drv
            for dbeat in range(drain_beats):
                l, seg = dbeat // nf, dbeat % nf
                _, data, last = await with_timeout(
                    self.lsu2rvv_q[ch].get(), 8000, "us")
                exp = 0
                for eir in range(er_data):
                    ve = l * er_data + eir
                    for b in range(eb):
                        a = (base + seg * eb + indices[ve] + b) & 0xFFFFFFFF
                        exp |= self.mem.get(a, 0) << (8 * (eir * eb + b))
                assert data == exp, (
                    f"vload-idx beat {dbeat}: {data:#034x} != {exp:#034x}")
                assert last == 0


def fill_region(mem, rng):
    for a in range(REGION_BASE, REGION_BASE + REGION_SIZE):
        mem[a] = rng.getrandbits(8)


async def setup(dut, reorder=0):
    cocotb.start_soon(Clock(dut.clock, 10, unit="us").start())
    # Quiesce all driven inputs before releasing reset.
    for lane in range(4):
        getattr(dut, f"io_req_{lane}_valid").value = 0
        getattr(dut, f"io_busPort_addr_{lane}").value = 0
        getattr(dut, f"io_busPort_data_{lane}").value = 0
    for ch in range(2):
        getattr(dut, f"io_rvv2lsu_{ch}_valid").value = 0
        getattr(dut, f"io_rvv2lsu_{ch}_bits_vregfile_valid").value = 0
        getattr(dut, f"io_rvv2lsu_{ch}_bits_mask_valid").value = 0
        getattr(dut, f"io_rvv2lsu_{ch}_bits_idx_valid").value = 0
    dut.io_rvvState_valid.value = 0
    dut.reset.value = 1
    await ClockCycles(dut.clock, 3)
    dut.reset.value = 0
    await RisingEdge(dut.clock)
    return LsuV3Tb(dut, reorder=reorder)


# ============================ Directed tests =============================

@cocotb.test()
async def test_scalar_load_word(dut):
    tb = await setup(dut)
    rng = random.Random(1)
    fill_region(tb.mem, rng)
    await tb.scalar(LsuOp.LW, REGION_BASE + 0x40, rd=3)
    await ClockCycles(dut.clock, 5)


@cocotb.test()
async def test_scalar_store_word(dut):
    tb = await setup(dut)
    await tb.scalar(LsuOp.SW, REGION_BASE + 0x80, rd=0, store_data=0xDEADBEEF)
    await ClockCycles(dut.clock, 5)


@cocotb.test()
async def test_scalar_load_sext(dut):
    tb = await setup(dut)
    tb.mem[REGION_BASE] = 0x80          # negative byte / halfword low
    tb.mem[REGION_BASE + 1] = 0xFF
    await tb.scalar(LsuOp.LB, REGION_BASE, rd=4)
    await tb.scalar(LsuOp.LBU, REGION_BASE, rd=5)
    await tb.scalar(LsuOp.LH, REGION_BASE, rd=6)
    await tb.scalar(LsuOp.LHU, REGION_BASE, rd=7)
    await ClockCycles(dut.clock, 5)


@cocotb.test()
async def test_vector_unit_load(dut):
    tb = await setup(dut)
    rng = random.Random(2)
    fill_region(tb.mem, rng)
    await tb.vector(LsuOp.VLOAD_UNIT, REGION_BASE + 0x100, sew_bytes=4,
                    nf=1, lmul=0, rd=8)
    await ClockCycles(dut.clock, 5)


@cocotb.test()
async def test_vector_unit_store(dut):
    tb = await setup(dut)
    await tb.vector(LsuOp.VSTORE_UNIT, REGION_BASE + 0x200, sew_bytes=4,
                    nf=1, lmul=0, rd=0)
    await ClockCycles(dut.clock, 5)


@cocotb.test()
async def test_vector_strided_load(dut):
    tb = await setup(dut)
    rng = random.Random(3)
    fill_region(tb.mem, rng)
    await tb.vector(LsuOp.VLOAD_STRIDED, REGION_BASE + 0x300, sew_bytes=4,
                    nf=1, lmul=0, rd=9, stride=8)
    await ClockCycles(dut.clock, 5)


@cocotb.test()
async def test_vector_strided_store(dut):
    tb = await setup(dut)
    await tb.vector(LsuOp.VSTORE_STRIDED, REGION_BASE + 0x400, sew_bytes=4,
                    nf=1, lmul=0, rd=0, stride=8)
    await ClockCycles(dut.clock, 5)


@cocotb.test()
async def test_pingpong(dut):
    """Two vector loads in flight together: op1 -> slot0/ch0, op2 -> slot1/ch1.

    Issuing op2 before op1 completes forces it into the second slot, so the two
    slots fill and drain concurrently on their own channels.
    """
    tb = await setup(dut)
    rng = random.Random(4)
    fill_region(tb.mem, rng)
    ctx0 = await tb._vec_issue(LsuOp.VLOAD_UNIT, REGION_BASE + 0x500, 4,
                               nf=2, lmul=1, ch=0, rd=10, stride=None,
                               store_data=None)
    ctx1 = await tb._vec_issue(LsuOp.VLOAD_UNIT, REGION_BASE + 0x800, 2,
                               nf=1, lmul=0, ch=1, rd=12, stride=None,
                               store_data=None)
    await tb._vec_finish(ctx0)
    await tb._vec_finish(ctx1)
    await ClockCycles(dut.clock, 5)


@cocotb.test()
async def test_outstanding_reorder(dut):
    """Device replies out-of-order to stress the source-tag table."""
    tb = await setup(dut, reorder=1)
    rng = random.Random(5)
    fill_region(tb.mem, rng)
    await tb.vector(LsuOp.VLOAD_UNIT, REGION_BASE + 0x600, sew_bytes=1,
                    nf=1, lmul=2, rd=14)
    await ClockCycles(dut.clock, 5)


@cocotb.test()
async def test_indexed_load_eq(dut):
    """EEW == SEW indexed loads (1/1, 2/2, 4/4)."""
    tb = await setup(dut)
    rng = random.Random(10)
    fill_region(tb.mem, rng)
    for w in (1, 2, 4):
        await tb.indexed(LsuOp.VLOAD_UINDEXED, REGION_BASE, sew_bytes=w,
                         eew_bytes=w, nf=1, lmul=0, rd=5, rng=rng)
    await ClockCycles(dut.clock, 5)


@cocotb.test()
async def test_indexed_store_eq(dut):
    """EEW == SEW indexed stores (1/1, 2/2, 4/4)."""
    tb = await setup(dut)
    rng = random.Random(11)
    for w in (1, 2, 4):
        await tb.indexed(LsuOp.VSTORE_UINDEXED, REGION_BASE, sew_bytes=w,
                         eew_bytes=w, nf=1, lmul=0, rd=0, rng=rng)
    await ClockCycles(dut.clock, 5)


@cocotb.test()
async def test_indexed_load_narrow(dut):
    """EEW < SEW indexed loads (idx 1/data 2, 1/4, 2/4)."""
    tb = await setup(dut)
    rng = random.Random(12)
    fill_region(tb.mem, rng)
    for ib, eb in ((1, 2), (1, 4), (2, 4)):
        await tb.indexed(LsuOp.VLOAD_OINDEXED, REGION_BASE, sew_bytes=eb,
                         eew_bytes=ib, nf=1, lmul=0, rd=6, rng=rng)
    await ClockCycles(dut.clock, 5)


@cocotb.test()
async def test_indexed_store_narrow(dut):
    """EEW < SEW indexed stores."""
    tb = await setup(dut)
    rng = random.Random(13)
    for ib, eb in ((1, 2), (1, 4), (2, 4)):
        await tb.indexed(LsuOp.VSTORE_OINDEXED, REGION_BASE, sew_bytes=eb,
                         eew_bytes=ib, nf=1, lmul=0, rd=0, rng=rng)
    await ClockCycles(dut.clock, 5)


@cocotb.test()
async def test_indexed_load_wide(dut):
    """EEW > SEW indexed loads (idx 2/data 1, 4/1, 4/2) -> index sub-beats."""
    tb = await setup(dut)
    rng = random.Random(14)
    fill_region(tb.mem, rng)
    for ib, eb in ((2, 1), (4, 1), (4, 2)):
        await tb.indexed(LsuOp.VLOAD_UINDEXED, REGION_BASE, sew_bytes=eb,
                         eew_bytes=ib, nf=1, lmul=0, rd=7, rng=rng)
    await ClockCycles(dut.clock, 5)


@cocotb.test()
async def test_indexed_store_wide(dut):
    """EEW > SEW indexed stores -> index sub-beats."""
    tb = await setup(dut)
    rng = random.Random(15)
    for ib, eb in ((2, 1), (4, 1), (4, 2)):
        await tb.indexed(LsuOp.VSTORE_UINDEXED, REGION_BASE, sew_bytes=eb,
                         eew_bytes=ib, nf=1, lmul=0, rd=0, rng=rng)
    await ClockCycles(dut.clock, 5)


@cocotb.test()
async def test_indexed_segmented(dut):
    """Segmented (NF>1) and LMUL>1 indexed load + store."""
    tb = await setup(dut)
    rng = random.Random(16)
    fill_region(tb.mem, rng)
    await tb.indexed(LsuOp.VLOAD_UINDEXED, REGION_BASE, sew_bytes=4,
                     eew_bytes=4, nf=2, lmul=1, rd=8, rng=rng)
    await tb.indexed(LsuOp.VSTORE_UINDEXED, REGION_BASE, sew_bytes=2,
                     eew_bytes=1, nf=2, lmul=1, rd=0, rng=rng)
    await ClockCycles(dut.clock, 5)


@cocotb.test()
async def test_lsu_v3_random(dut):
    tb = await setup(dut)
    rng = random.Random(0xC0FFEE)
    fill_region(tb.mem, rng)
    for _ in range(60):
        kind = rng.choice(["sl", "ss", "vl", "vs", "vsl", "vss", "vil", "vis"])
        if kind in ("vil", "vis"):
            sew_bytes = rng.choice([1, 2, 4])
            eew_bytes = rng.choice([1, 2, 4])
            # Keep EMUL_index = emul*max(1,EEW/SEW) <= 8 and a small element
            # count so distinct indices fit the test region.
            elem_mul = max(1, eew_bytes // sew_bytes)
            max_lmul = {1: 0, 2: 1, 4: 2}[elem_mul]
            lmul = rng.randrange(0, max_lmul + 1)
            max_nf = max(1, 8 // (1 << lmul))
            nf = rng.randrange(1, min(max_nf, 4) + 1)
            op = (LsuOp.VLOAD_UINDEXED if kind == "vil"
                  else LsuOp.VSTORE_UINDEXED)
            await tb.indexed(op, REGION_BASE, sew_bytes, eew_bytes, nf, lmul,
                             ch=0, rd=(1 if kind == "vil" else 0), rng=rng)
        elif kind in ("sl", "ss"):
            if kind == "sl":
                op = rng.choice([LsuOp.LB, LsuOp.LBU, LsuOp.LH, LsuOp.LHU,
                                 LsuOp.LW])
            else:
                op = rng.choice([LsuOp.SB, LsuOp.SH, LsuOp.SW])
            nb = {LsuOp.LB: 1, LsuOp.LBU: 1, LsuOp.SB: 1, LsuOp.LH: 2,
                  LsuOp.LHU: 2, LsuOp.SH: 2, LsuOp.LW: 4, LsuOp.SW: 4}[op]
            addr = REGION_BASE + rng.randrange(0, REGION_SIZE - 8) // nb * nb
            await tb.scalar(op, addr, rd=rng.randrange(1, 31),
                            store_data=rng.getrandbits(32))
        else:
            sew_bytes = rng.choice([1, 2, 4])
            lmul = rng.randrange(0, 3)
            max_nf = max(1, 8 // (1 << lmul))
            nf = rng.randrange(1, max_nf + 1)
            # Sequential ops always land in slot 0 -> channel 0.
            base = REGION_BASE + (rng.randrange(0, REGION_SIZE // 4)
                                  // sew_bytes * sew_bytes)
            if kind == "vl":
                await tb.vector(LsuOp.VLOAD_UNIT, base, sew_bytes, nf, lmul,
                                ch=0, rd=1)
            elif kind == "vs":
                await tb.vector(LsuOp.VSTORE_UNIT, base, sew_bytes, nf, lmul,
                                ch=0, rd=0)
            elif kind == "vsl":
                await tb.vector(LsuOp.VLOAD_STRIDED, base, sew_bytes, nf, lmul,
                                ch=0, rd=1, stride=sew_bytes * nf + sew_bytes)
            else:
                await tb.vector(LsuOp.VSTORE_STRIDED, base, sew_bytes, nf, lmul,
                                ch=0, rd=0, stride=sew_bytes * nf + sew_bytes)
    await ClockCycles(dut.clock, 10)
