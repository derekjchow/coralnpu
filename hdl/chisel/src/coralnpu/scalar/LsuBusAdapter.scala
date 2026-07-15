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

package coralnpu

import chisel3._
import chisel3.util._
import bus._

// A-channel user payload for LSU-originated TL-UL requests. The legacy buses
// carry the requesting instruction's pc for tracing and fault reporting.
class LsuTlulAUser(p: Parameters) extends Bundle {
  val pc = UInt(p.programCounterBits.W)
}

object LsuBusAdapter {
  def tlParameters(p: Parameters): TLULParameters = {
    new TLULParameters(dataBits = p.lsuDataBits, addrBits = p.lsuAddrBits, idBits = 1)
  }

  def lineBits(p: Parameters): Int = log2Ceil(p.lsuDataBytes)

  def lineAligned(p: Parameters, addr: UInt): UInt = {
    Cat(addr(p.lsuAddrBits - 1, lineBits(p)), 0.U(lineBits(p).W))
  }
}

// D-channel response path shared by the TLUL2*Bus converters: a request
// pulsed at A-channel fire produces a D response one cycle later (matching
// the fixed next-cycle read-data timing of the legacy buses), buffered in a
// 1-entry skid queue so a stalling host cannot lose data. canIssue gates the
// next A accept so the skid queue never overflows.
class TlulDResponder(p: Parameters) extends Module {
  val tlp = LsuBusAdapter.tlParameters(p)

  class Request extends Bundle {
    val opcode = UInt(3.W)
    val size   = UInt(tlp.z.W)
    val source = UInt(tlp.o.W)
    val error  = Bool()
  }

  val io = IO(new Bundle {
    val req      = Input(Valid(new Request))          // Pulsed at A-channel fire.
    val rdata    = Input(UInt((8 * tlp.w).W))         // Valid one cycle after req.
    val d        = Decoupled(new TileLink_D_ChannelBase(tlp, () => new NoUser))
    val canIssue = Output(Bool())
  })

  val dq = Module(new Queue(chiselTypeOf(io.d.bits), entries = 1, flow = true, pipe = true))
  io.d <> dq.io.deq

  val meta = Pipe(io.req.valid, io.req.bits, latency = 1)
  io.canIssue := dq.io.deq.fire || (!meta.valid && (dq.io.count === 0.U))
  assert(!meta.valid || dq.io.enq.ready, "TlulDResponder: response arrived with skid queue full")

  dq.io.enq.valid       := meta.valid
  dq.io.enq.bits        := 0.U.asTypeOf(dq.io.enq.bits)
  dq.io.enq.bits.opcode := Mux(
    meta.bits.opcode === TLULOpcodesA.Get.asUInt,
    TLULOpcodesD.AccessAckData.asUInt,
    TLULOpcodesD.AccessAck.asUInt
  )
  dq.io.enq.bits.size   := meta.bits.size
  dq.io.enq.bits.source := meta.bits.source
  dq.io.enq.bits.data   := io.rdata
  dq.io.enq.bits.error  := meta.bits.error
}

object TlulDResponder {
  def isWrite(opcode: UInt): Bool = {
    (opcode === TLULOpcodesA.PutFullData.asUInt) ||
    (opcode === TLULOpcodesA.PutPartialData.asUInt)
  }
}

// Converts TL-UL requests to IBusIO (ITCM). The IBus is read-only: Get
// requests become bus reads, Put requests are accepted without issuing a bus
// transaction and answered with an errored AccessAck (stores to ITCM fault).
// io.ibus.fault is ignored; SCore ties it off for the LSU's ibus port.
class TLUL2IBus(p: Parameters) extends Module {
  val tlp = LsuBusAdapter.tlParameters(p)
  val io  = IO(new Bundle {
    val tl   = new TLULDevice2Host(tlp, () => new LsuTlulAUser(p), () => new NoUser)
    val ibus = new IBusIO(p)
  })

  val a       = io.tl.a
  val isWrite = TlulDResponder.isWrite(a.bits.opcode)

  val responder = Module(new TlulDResponder(p))
  io.tl.d <> responder.io.d
  responder.io.rdata := io.ibus.rdata(8 * tlp.w - 1, 0)

  io.ibus.valid := a.valid && responder.io.canIssue && !isWrite
  io.ibus.addr  := LsuBusAdapter.lineAligned(p, a.bits.address)

  a.ready := responder.io.canIssue && (io.ibus.ready || isWrite)

  responder.io.req.valid       := a.fire
  responder.io.req.bits.opcode := a.bits.opcode
  responder.io.req.bits.size   := a.bits.size
  responder.io.req.bits.source := a.bits.source
  responder.io.req.bits.error  := isWrite
}

// Converts TL-UL requests to DBusIO (DTCM). TCM accesses cannot fault.
class TLUL2DBus(p: Parameters) extends Module {
  val tlp = LsuBusAdapter.tlParameters(p)
  val io  = IO(new Bundle {
    val tl   = new TLULDevice2Host(tlp, () => new LsuTlulAUser(p), () => new NoUser)
    val dbus = new DBusIO(p)
  })

  val a        = io.tl.a
  val isWrite  = TlulDResponder.isWrite(a.bits.opcode)
  val lineAddr = LsuBusAdapter.lineAligned(p, a.bits.address)

  val responder = Module(new TlulDResponder(p))
  io.tl.d <> responder.io.d
  responder.io.rdata := io.dbus.rdata

  io.dbus.valid := a.valid && responder.io.canIssue
  io.dbus.write := isWrite
  io.dbus.pc    := a.bits.user.pc
  io.dbus.addr  := lineAddr
  io.dbus.adrx  := lineAddr
  io.dbus.size  := (1.U << a.bits.size)(p.dbusSize - 1, 0)
  io.dbus.wdata := a.bits.data
  io.dbus.wmask := a.bits.mask

  a.ready := responder.io.canIssue && io.dbus.ready

  responder.io.req.valid       := a.fire
  responder.io.req.bits.opcode := a.bits.opcode
  responder.io.req.bits.size   := a.bits.size
  responder.io.req.bits.source := a.bits.source
  responder.io.req.bits.error  := false.B
}

// Converts TL-UL requests to EBusIO (peripheral and external memory).
// `internal` marks accesses to Peripheral regions. The ebus fault input is
// combinational in the cycle the transaction completes and is reported as an
// error on the D response.
class TLUL2EBus(p: Parameters) extends Module {
  val tlp = LsuBusAdapter.tlParameters(p)
  val io  = IO(new Bundle {
    val tl   = new TLULDevice2Host(tlp, () => new LsuTlulAUser(p), () => new NoUser)
    val ebus = new EBusIO(p)
  })

  val a        = io.tl.a
  val isWrite  = TlulDResponder.isWrite(a.bits.opcode)
  val lineAddr = LsuBusAdapter.lineAligned(p, a.bits.address)
  val peri     = p.m
    .filter(_.memType == MemoryRegionType.Peripheral)
    .map(_.contains(lineAddr))
    .reduceOption(_ || _)
    .getOrElse(false.B)

  val responder = Module(new TlulDResponder(p))
  io.tl.d <> responder.io.d
  responder.io.rdata := io.ebus.dbus.rdata

  io.ebus.dbus.valid := a.valid && responder.io.canIssue
  io.ebus.dbus.write := isWrite
  io.ebus.dbus.pc    := a.bits.user.pc
  io.ebus.dbus.addr  := a.bits.address
  io.ebus.dbus.adrx  := lineAddr
  io.ebus.dbus.size  := (1.U << a.bits.size)(p.dbusSize - 1, 0)
  io.ebus.dbus.wdata := a.bits.data
  io.ebus.dbus.wmask := a.bits.mask
  io.ebus.internal   := peri

  a.ready := responder.io.canIssue && io.ebus.dbus.ready

  responder.io.req.valid       := a.fire
  responder.io.req.bits.opcode := a.bits.opcode
  responder.io.req.bits.size   := a.bits.size
  responder.io.req.bits.source := a.bits.source
  responder.io.req.bits.error  := io.ebus.fault.valid
}

// Fronts the LSU's three memory buses with a single TL-UL interface.
// Requests are routed by memory region: IMEM -> ibus, DMEM -> dbus (the
// default when no DMEM regions are configured), everything else (peripheral
// and external) -> ebus. Faults are reported via the D-channel error bit.
class LsuBusAdapter(p: Parameters) extends Module {
  val tlp = LsuBusAdapter.tlParameters(p)
  val io  = IO(new Bundle {
    val tl   = new TLULDevice2Host(tlp, () => new LsuTlulAUser(p), () => new NoUser)
    val ibus = new IBusIO(p)
    val dbus = new DBusIO(p)
    val ebus = new EBusIO(p)
  })

  def regionHit(memType: MemoryRegionType.Type, addr: UInt, default: Bool): Bool = {
    p.m
      .filter(_.memType == memType)
      .map(_.contains(LsuBusAdapter.lineAligned(p, addr)))
      .reduceOption(_ || _)
      .getOrElse(default)
  }

  val router = Module(
    new TlulRouter(
      tlp,
      () => new LsuTlulAUser(p),
      () => new NoUser,
      Seq(
        (addr: UInt) => regionHit(MemoryRegionType.IMEM, addr, false.B),
        (addr: UInt) => regionHit(MemoryRegionType.DMEM, addr, true.B),
        (addr: UInt) => true.B
      )
    )
  )
  router.io.host <> io.tl

  // Configured regions must not overlap.
  val reqAddr = io.tl.a.bits.address
  assert(
    !io.tl.a.valid || (PopCount(
      Cat(
        regionHit(MemoryRegionType.IMEM, reqAddr, false.B),
        regionHit(MemoryRegionType.DMEM, reqAddr, false.B),
        regionHit(MemoryRegionType.Peripheral, reqAddr, false.B)
      )
    ) <= 1.U),
    "LsuBusAdapter: memory regions overlap"
  )

  val tlul2ibus = Module(new TLUL2IBus(p))
  tlul2ibus.io.tl <> router.io.devs(0)
  io.ibus <> tlul2ibus.io.ibus

  val tlul2dbus = Module(new TLUL2DBus(p))
  tlul2dbus.io.tl <> router.io.devs(1)
  io.dbus <> tlul2dbus.io.dbus

  val tlul2ebus = Module(new TLUL2EBus(p))
  tlul2ebus.io.tl <> router.io.devs(2)
  io.ebus <> tlul2ebus.io.ebus
}
