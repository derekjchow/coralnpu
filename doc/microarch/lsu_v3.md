# Load Store Unit V3

`LsuV3` is a next-generation Load Store Unit for CoralNPU. It keeps the
slot/scatter-gather model of the original [LSU](lsu.md) but changes three
things:

1. The three memory ports (`ibus`, `dbus`, `ebus`) collapse into a **single
   consolidated TileLink-UL master port** (`tl`). Address-region routing is
   delegated to the downstream crossbar.
2. It holds **two super-slots** that ping-pong, so one operation can pull
   vector data from the RvvCore while the other transacts on the bus.
3. The bus is **multiple-outstanding**: several TileLink transactions may be
   in flight at once, tracked by a source-tag table.

The interface is defined by `LsuNext` (a trimmed `Lsu`: buses consolidated,
the `vldst` switch and `flush`/fence interface removed); `LsuV3` is the
implementation. Both live in `hdl/chisel/src/coralnpu/scalar/Lsu.scala`.

> **Status / scope.** `LsuV3` is delivered standalone (the `Lsu` factory
> still returns `LsuV2`). It supports scalar loads/stores and vector
> loads/stores: unit-stride, strided, and **indexed** (ordered and unordered,
> including mixed index-width EEW ≠ data-width SEW). Fence/flush ops and core
> integration are future work.

## Super-slots

Like the original LSU, a super-slot tracks one operation byte-by-byte. Each
of its `vlenb*8` entries holds `{data, addr, state}`, where `state` is one of:

| State    | Meaning                                                      |
| -------- | ----------------------------------------------------------- |
| Idle     | no work assigned (default / post-completion).               |
| Pending  | address (and data, for stores) ready; eligible for issue.   |
| InFlight | selected into a TileLink-A beat; awaiting its D response.    |
| Done     | transaction acknowledged (loads have their data).           |

The entries are grouped into `nChunks = vlenb*8/dlenb` cache-line-sized
chunks. An **issue head** names the first chunk that still holds Pending
work; each beat re-derives it with a find-first so it self-corrects and can
skip a run of drained chunks in one step. `emitTxn` selects the line of the
first Pending entry at/after the head, scatters **all** Pending entries on
that line into one TileLink-A beat (so line-straddling tails ride along), and
moves them to InFlight.

With the emitter's configuration (`lsuDataBits = 128`), the TileLink beat,
the RVV beat, and the chunk size are all 16 bytes: `vlenb = dlenb = 16`,
`nEntries = 128`, `nChunks = 8`.

## Slot lifecycle

Each slot runs an independent four-phase FSM. The two slots overlap: while
one is `Transact`, the other can be `Fill`.

```
            dequeue (vector)                 fill done
  Inactive ─────────────────► Fill ───────────────────► Transact
     ▲   │                                                  │
     │   │ dequeue (scalar)                                 │ no Pending/InFlight
     │   └──────────────────────────────────────────────►  │
     │                                                      ▼
     └──────────────────── Inactive ◄──────────────── Writeback
                          (drain / rd / storeComplete)
```

1. **Inactive** — a free slot dequeues the head op. Vector ops construct the
   slot with `LsuV3SuperSlot.apply` (priming the address generators) and go
   to **Fill**. Scalar ops construct entries directly from the command with
   `applyScalar` (no RvvCore data needed) and go straight to **Transact**.
2. **Fill** (vector only) — the slot pulls beats from `rvv2lsu(i)`. Each beat
   scatters its bytes into entries (honoring the per-element mask) and advances
   the address generators. Loads still consume these beats for their masks and
   address generation; only their data is ignored.
   - *Non-indexed* (`apply`): `nf * 2^EMUL` beats; addresses from
     `NonIndexedAddrGenerator` (unit-stride/strided).
   - *Indexed* (`applyIndexed`): addresses come from the index vector
     (`rvv2lsu.idx`) via `ComputeIndexedAddrs`. Each beat carries one data
     register's elements (or, when EEW > SEW, a `vlenb/EEW`-element sub-beat),
     so fill takes `nf * 2^EMUL * max(1, EEW/SEW)` beats; a `(lmul, segment,
     sub)` cursor places them. The Transact/Writeback paths are identical —
     `emitTxn` already groups the scattered Pending entries by cache line, and
     the drain replays whole data registers.
3. **Transact** — the slot issues one TileLink-A transaction per cache line
   (`Get` for loads, `PutPartialData` for stores) and consumes D responses.
   It leaves once it holds neither Pending nor InFlight entries.
4. **Writeback** — scalar loads drive `rd`/`rd_flt` (sign/zero extended);
   scalar/vector stores raise `storeComplete`; vector loads drain `nf*2^EMUL`
   register beats to `lsu2rvv(i)` with `last` on the final beat. The drain
   replays the fill's `(lmul, segment)` order by resetting the address
   generator to its start.

Slot `i` is bound to `rvv2lsu(i)`/`lsu2rvv(i)`. The single TileLink-A channel
is arbitrated round-robin between the two slots' Transact phases. Writeback
is serialized to one slot at a time, since `rd`/`storeComplete` are
single-ported.

## Bus protocol & multiple-outstanding

Transactions use a NoUser TileLink-UL master port. Because the D channel
carries no address, an **outstanding-transaction table** records, per source
tag, `{slot, line, isLoad}`:

* On A-fire, a free tag is allocated and the entry is written; the A channel
  stalls if the table is full.
* On D-fire, the tag is looked up and freed. The response writes back to the
  recorded slot/line: every InFlight entry on that line becomes Done, and for
  loads its byte is gathered from the returned line at the entry's offset.

Because `emitTxn` only ever selects Pending (never InFlight) entries, a slot
naturally skips lines that already have a transaction outstanding, so a single
slot can have several transactions in flight. D responses may arrive in any
order — the table keys writeback on the recorded line, not on issue order.

## Interfaces

### LSU Command Interface

| Signal Name   | Type          | Description                                          |
| ------------- | ------------- | ---------------------------------------------------- |
| req.valid     | Bool          | If the LSU command is valid.                         |
| req.op        | LsuOp         | The LSU operation to execute.                        |
| req.store     | Bool          | If the operation is a store.                         |
| req.addr      | UInt(5)       | Destination register for loads.                      |
| req.pc        | UInt(32)      | The PC of the instruction (fault reporting).         |
| req.elemWidth | UInt(3)       | RVV EEW (data/index width).                          |
| req.nfields   | UInt(3)       | RVV NF - 1 (number of segment fields minus one).     |
| req.umop      | UInt(5)       | RVV unit-stride sub-op encoding.                     |
| req.ready     | Bool (output) | Ready-valid handshake; one op is accepted per cycle. |

The effective byte address and store data are read from `busPort`
(`busPort.addr(i)`, `busPort.data(i)`); SEW/LMUL come from `rvvState`.

### Bus Interface (consolidated TileLink-UL master)

| Signal Name      | Type          | Description                                          |
| ---------------- | ------------- | ---------------------------------------------------- |
| tl.a.valid       | Bool          | If the A-channel request is valid.                   |
| tl.a.bits.opcode | UInt(3)       | Get / PutFullData / PutPartialData.                  |
| tl.a.bits.size   | UInt(z)       | log2 of the beat width in bytes (full line).         |
| tl.a.bits.source | UInt(o)       | Source tag; echoed by the matching D response.       |
| tl.a.bits.address| UInt(32)      | Line-aligned transaction address.                    |
| tl.a.bits.mask   | UInt(w)       | Byte enables for this beat.                          |
| tl.a.bits.data   | UInt(8w)      | Write data (stores).                                 |
| tl.a.ready       | Bool (input)  | Ready-valid handshake on the A channel.              |
| tl.d.valid       | Bool (input)  | If the D-channel response is valid.                  |
| tl.d.bits.opcode | UInt(3)       | AccessAck (stores) / AccessAckData (loads).          |
| tl.d.bits.source | UInt(o)       | Source tag matching the original request.            |
| tl.d.bits.data   | UInt(8w)      | Read data (loads).                                   |
| tl.d.bits.error  | Bool (input)  | If the access faulted.                               |
| tl.d.ready       | Bool (output) | Always asserted; responses drain in one cycle.       |

### Writeback Interfaces

| Signal Name | Type     | Description                                       |
| ----------- | -------- | ------------------------------------------------- |
| rd.valid    | Bool     | If the scalar writeback is valid.                 |
| rd.addr     | UInt(5)  | Destination scalar register.                      |
| rd.data     | UInt(32) | Sign/zero-extended load result.                   |

`rd_flt` mirrors `rd` for floating-point loads. `storeComplete.valid` pulses
with the instruction PC when a store retires.

### RVV Interfaces

`rvv2lsu` (Vec of 2) and `lsu2rvv` (Vec of 2) match the original LSU's
per-channel `Rvv2Lsu`/`Lsu2Rvv` bundles; slot `i` uses channel `i`.
`rvvState` carries the active `vtype`/`vl` configuration.

## Verification

A cocotb testbench (`tests/cocotb/lsu/test_lsu_v3.py`) drives the DUT through
its command interface and serves all TileLink traffic from a sparse memory
model that doubles as the scoreboard:

* **Agents**: a TileLink device memory model (a NoUser-tolerant
  `TileLinkULInterface` subclass), per-channel `lsu2rvv` monitors, and an
  `rd`/`storeComplete` monitor.
* **Golden model**: a Python replica of the address generators (non-indexed
  and the indexed `idx`-driven path) predicts, for each beat, the addresses
  touched; expected load beats and store memory state are derived from the same
  sparse memory.
* **Tests**: directed cases for scalar load/store (incl. sign extension),
  vector unit-stride and strided load/store, indexed load/store across every
  EEW/SEW combination (including EEW > SEW sub-beats) and segmented/LMUL>1, the
  two-slot ping-pong, and an out-of-order response case, followed by a
  constrained-random sweep over op kind, SEW, EEW, NF, LMUL, base, and stride.

Run with:

```
bazel test //tests/cocotb/lsu:lsu_v3_cocotb_test_verilator
```

## Out of scope / future work

* Memory-ordering distinction between ordered (`OINDEXED`) and unordered
  (`UINDEXED`) indexed ops — the address math is identical; only consistency
  semantics differ, which the standalone unit does not model.
* OpenTitan user/integrity fields (the consolidated port is NoUser).
* Multi-lane command acceptance (currently one op per cycle).

## Core integration (work in progress)

LsuV3 can be dropped into `RvvCoreMiniAxi` behind `--useLsuV3=True` (the `Lsu`
factory returns `LsuV3Wrapper` instead of `LsuV2`; other core builds are
unchanged). The wrapper presents the legacy `Lsu` IO so `SCore`/`CoreAxi` are
untouched:

* **`LsuTlulMux`** routes LsuV3's single TileLink port to the existing
  `ibus`/`dbus`/`ebus` by memory region (single outstanding, replicating
  LsuV2's region decode and bus timing).
* **`LsuV3Wrapper`** wraps `LsuV3` + the mux, ties off `vldst`, and
  re-implements the `flush`/fence shim LsuV3 dropped.
* For the core, LsuV3's vector control runs **interleaved per register**
  (fill → transact → writeback, one register at a time) because the
  (external, LsuV2-co-designed) RvvCore serializes `rvv2lsu`/`lsu2rvv` on
  channel 0 and waits for each register's writeback before sending the next.

**Status:** scalar + non-indexed (unit-stride, strided, incl. segmented and
LMUL>1) and EEW==SEW indexed pass through the real core (`rvv_load_store`:
30/52). **Known gaps**, all matching the black-box RvvCore protocol:

* **EEW≠SEW indexed** — needs the RvvCore's mixed-width index/data partition
  protocol (the `indexParitions`/`subvector` machinery).
* **Segmented stores** (`vsseg*`) — a store-side interleave/ordering bug.
* **Masked** load/store — mask-policy value mismatch on masked elements.
