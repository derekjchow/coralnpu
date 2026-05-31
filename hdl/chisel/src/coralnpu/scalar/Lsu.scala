// Copyright 2023 Google LLC
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
import common._
import coralnpu.rvv._
import bus._

class DFlushFenceiIO(p: Parameters) extends DFlushIO(p) {
  val fencei = Output(Bool())
  val pcNext = Output(UInt(32.W))
}

class Lsu(p: Parameters) extends Module {
  val io = IO(new Bundle {
    // Decode cycle.
    val req = Vec(p.instructionLanes, Flipped(Decoupled(new LsuCmd(p))))
    val busPort = Flipped(new RegfileBusPortIO(p))
    val busPort_flt = Option.when(p.enableFloat)(Flipped(new RegfileBusPortIO(p)))

    // Execute cycle(s).
    val rd = Valid(Flipped(new RegfileWriteDataIO))
    val rd_flt = Valid(Flipped(new RegfileWriteDataIO))

    // Cached interface.
    val ibus = new IBusIO(p)
    val dbus = new DBusIO(p)
    val flush = new DFlushFenceiIO(p)
    val fault = Valid(new FaultInfo(p))

    // DBus that will eventually reach an external bus.
    // Intended for sending a transaction to an external
    // peripheral, likely on TileLink or AXI.
    val ebus = new EBusIO(p)

    // Vector switch.
    val vldst = Output(Bool())

    val rvv2lsu = Option.when(p.enableRvv)(
        Vec(2, Flipped(Decoupled(new Rvv2Lsu(p)))))
    val lsu2rvv = Option.when(p.enableRvv)(Vec(2, Decoupled(new Lsu2Rvv(p))))

    // RVV config state
    val rvvState = Option.when(p.enableRvv)(Input(Valid(new RvvConfigState(p))))

    val queueCapacity = Output(UInt(3.W))
    val active = Output(Bool())
    val storeComplete = Output(Valid(UInt(32.W)))
  })
}

object Lsu {
  def apply(p: Parameters): Lsu = {
    if (p.useLsuV3) {
      return Module(new LsuV3Wrapper(p))
    }
    return Module(new LsuV2(p))
  }
}

object LsuOp extends ChiselEnum {
  val LB  = Value
  val LH  = Value
  val LW  = Value
  val LBU = Value
  val LHU = Value
  val SB  = Value
  val SH  = Value
  val SW  = Value
  val FENCEI = Value
  val FLUSHAT = Value
  val FLUSHALL = Value
  val VLDST = Value
  val FLOAT = Value

  // Vector instructions.
  val VLOAD_UNIT = Value
  val VLOAD_STRIDED = Value
  val VLOAD_OINDEXED = Value
  val VLOAD_UINDEXED = Value
  val VSTORE_UNIT = Value
  val VSTORE_STRIDED = Value
  val VSTORE_OINDEXED = Value
  val VSTORE_UINDEXED = Value

  def isVector(op: LsuOp.Type): Bool = {
    op.isOneOf(LsuOp.VLOAD_UNIT, LsuOp.VLOAD_STRIDED,
               LsuOp.VLOAD_OINDEXED, LsuOp.VLOAD_UINDEXED,
               LsuOp.VSTORE_UNIT, LsuOp.VSTORE_STRIDED,
               LsuOp.VSTORE_OINDEXED, LsuOp.VSTORE_UINDEXED)
  }

  def isIndexedVector(op: LsuOp.Type): Bool = {
    op.isOneOf(LsuOp.VLOAD_OINDEXED, LsuOp.VLOAD_UINDEXED,
               LsuOp.VSTORE_OINDEXED, LsuOp.VSTORE_UINDEXED)
  }

  def isNonindexedVector(op: LsuOp.Type): Bool = {
    op.isOneOf(LsuOp.VLOAD_UNIT, LsuOp.VLOAD_STRIDED,
               LsuOp.VSTORE_UNIT, LsuOp.VSTORE_STRIDED)
  }

  def isFlush(op: LsuOp.Type): Bool = {
    op.isOneOf(LsuOp.FENCEI, LsuOp.FLUSHAT, LsuOp.FLUSHALL)
  }

  def isScalarLoad(op: LsuOp.Type): Bool = {
    op.isOneOf(LsuOp.LB, LsuOp.LBU, LsuOp.LH, LsuOp.LHU, LsuOp.LW)
  }

  def opSize(op: LsuOp.Type, address: UInt): (UInt, UInt) = {
    val halfAligned = (address(0) === 0.U)
    val wordAligned = (address(1, 0) === 0.U)

    val size = MuxUpTo1H(16.U, Seq(
      op.isOneOf(LsuOp.LB, LsuOp.LBU, LsuOp.SB) -> 1.U,
      op.isOneOf(LsuOp.LH, LsuOp.LHU, LsuOp.SH) -> Mux(halfAligned, 2.U, 16.U),
      op.isOneOf(LsuOp.LW, LsuOp.SW, LsuOp.FLOAT) ->
          Mux(wordAligned, 4.U, 16.U),
      LsuOp.isVector(op) -> 16.U,
    ))

    val halfAlignedAddress = address(31, 1) << 1.U
    val wordAlignedAddress = address(31, 2) << 2.U
    val lineAlignedAddress = address(31, 4) << 4.U
    val alignedAddress = MuxUpTo1H(lineAlignedAddress, Seq(
      op.isOneOf(LsuOp.LB, LsuOp.LBU, LsuOp.SB) -> address,
      (op.isOneOf(LsuOp.LH, LsuOp.LHU, LsuOp.SH) && halfAligned) ->
          halfAlignedAddress,
      (op.isOneOf(LsuOp.LW, LsuOp.SW, LsuOp.FLOAT) && wordAligned) ->
          wordAlignedAddress,
    ))

    (size, alignedAddress)
  }
}

class LsuCmd(p: Parameters) extends Bundle {
  val store = Bool()
  val addr = UInt(5.W)
  val op = LsuOp()
  val pc = UInt(32.W)
  val elemWidth = Option.when(p.enableRvv) { UInt(3.W) }
  val nfields = Option.when(p.enableRvv) { UInt(3.W) }
  val umop = Option.when(p.enableRvv) { UInt(5.W) }

  def isMaskOperation(): Bool = {
    if (p.enableRvv) {
      (umop.get === "b01011".U) &&
      op.isOneOf(LsuOp.VLOAD_UNIT, LsuOp.VSTORE_UNIT)
    } else {
      false.B
    }
  }

  def isWholeRegister(): Bool = {
    if (p.enableRvv) {
      (umop.get === "b01000".U) &&
      op.isOneOf(LsuOp.VLOAD_UNIT, LsuOp.VSTORE_UNIT)
    } else {
      false.B
    }
  }

  override def toPrintable: Printable = {
    cf"LsuCmd(store -> ${store}, addr -> 0x${addr}%x, op -> ${op}, " +
    cf"pc -> 0x${pc}%x, elemWidth -> ${elemWidth}, nfields -> ${nfields})"
  }
}

class LsuUOp(p: Parameters) extends Bundle {
  val store = Bool()
  val rd = UInt(5.W)
  val op = LsuOp()
  val pc = UInt(32.W)
  val addr = UInt(32.W)
  val data = UInt(32.W)  // Doubles as rs2
  // This aligns with "width" in the spec. It controls index width in
  // indexed loads/stores and data width otherwise.
  val elemWidth = Option.when(p.enableRvv) { UInt(3.W) }
  // This is the sew from vtype. It controls data width in indexed
  // loads/stores and is unused in other ops.
  val sew = Option.when(p.enableRvv) { UInt(3.W) }
  // How many data registers (per segment if applicable) to operate on.
  val emul_data = Option.when(p.enableRvv) { UInt(3.W) }
  val nfields = Option.when(p.enableRvv) { UInt(3.W) }

  override def toPrintable: Printable = {
    cf"LsuUOp(store -> ${store}, rd -> ${rd}, op -> ${op}, " +
    cf"pc -> 0x${pc}%x, addr -> 0x${addr}%x, data -> ${data})"
  }
}

object LsuUOp {
  def apply(p: Parameters,
            i: Int,
            cmd: LsuCmd,
            sbus: RegfileBusPortIO,
            fbus: Option[RegfileBusPortIO],
            rvvState: Option[Valid[RvvConfigState]]): LsuUOp = {
    val result = Wire(new LsuUOp(p))
    result.store := cmd.store
    result.rd := cmd.addr
    result.op := cmd.op
    result.pc := cmd.pc
    if (fbus.isDefined) {
      result.addr := sbus.addr(i)
      result.data := Mux(
          cmd.op === LsuOp.FLOAT, fbus.get.data(i), sbus.data(i))
    } else {
      result.addr := sbus.addr(i)
      result.data := sbus.data(i)
    }
    if (p.enableRvv) {
      val eew = cmd.elemWidth.get  // From instruction encoding
      val sew = rvvState.get.bits.sew  // From vtype
      val lmul = rvvState.get.bits.lmul
      // TODO(davidgao): Add checks for illegal LMUL values in the frontend.
      // Unit-stride, const-stride. Default value applies when eew == sew.
      val emul_data = MuxUpTo1H(lmul, Seq(
          // eew == 1/4 sew
          (eew === "b000".U && sew === "b010".U) -> (lmul - 2.U),
          // eew == 1/2 sew
          ((eew === "b000".U && sew === "b001".U) ||
           (eew === "b101".U && sew === "b010".U)) -> (lmul - 1.U),
          // eew == 2 sew
          ((eew === "b101".U && sew === "b000".U) ||
           (eew === "b110".U && sew === "b001".U)) -> (lmul + 1.U),
          // eew == 4 sew
          (eew === "b110".U && sew === "b000".U) -> (lmul + 2.U),
      ))
      result.elemWidth.get := eew
      result.emul_data.get := MuxCase(lmul, Seq(
          // If mask operation, always make LMUL=1.
          cmd.isMaskOperation() -> 0.U,
          // Section 7.9 of RVV Spec: "The nf field encodes how many vector
          // registers to load and store".
          cmd.isWholeRegister() -> MuxUpTo1H(0.U, Seq(
              (cmd.nfields.get === 0.U) -> 0.U,  // NF1 -> LMUL1
              (cmd.nfields.get === 1.U) -> 1.U,  // NF2 -> LMUL2
              (cmd.nfields.get === 3.U) -> 2.U,  // NF4 -> LMUL4
              (cmd.nfields.get === 7.U) -> 3.U,  // NF8 -> LMUL8
          )),
          LsuOp.isNonindexedVector(cmd.op) -> emul_data,
          // default: indexed vector and scalar
      ))

      // If mask operation, force fields to zero
      result.nfields.get := MuxUpTo1H(cmd.nfields.get, Seq(
          cmd.isMaskOperation() -> 0.U,
          cmd.isWholeRegister() -> 0.U,
      ))
      result.sew.get := rvvState.get.bits.sew
    }

    result
  }
}

object ComputeStridedAddrs {
  def apply(bytesPerSlot: Int,
            baseAddr: UInt,
            stride: UInt,
            elemWidth: UInt): Vec[UInt] = {
    MuxUpTo1H(VecInit.fill(bytesPerSlot)(0.U(32.W)), Seq(
      // elemWidth validation is done at decode time.
      // TODO: pass this as an enum.
      (elemWidth === "b000".U) -> VecInit((0 until bytesPerSlot).map(
          i => (baseAddr + (i.U*stride))(31, 0))),  // 1-byte elements
      (elemWidth === "b101".U) -> VecInit((0 until bytesPerSlot).map(
          i => (baseAddr + ((i >> 1).U*stride))(31, 0) + (i & 1).U)),  // 2-byte elements
      (elemWidth === "b110".U) -> VecInit((0 until bytesPerSlot).map(
          i => (baseAddr + ((i >> 2).U*stride))(31, 0) + (i & 3).U)),  // 4-byte elements
    ))
  }
}

object ComputeIndexedAddrs {
  def apply(bytesPerSlot: Int,
            baseAddr: UInt,
            indices: UInt,
            indexWidth: UInt,
            sew: UInt): Vec[UInt] = {
    val indices8 = UIntToVec(indices, 8).map(x => Cat(0.U(24.W), x))
    val indices16 = UIntToVec(indices, 16).map(x => Cat(0.U(16.W), x))
    val indices32 = UIntToVec(indices, 32)

    val indices_v = MuxUpTo1H(VecInit.fill(bytesPerSlot)(0.U(32.W)), Seq(
      // 8-bit indices.
      (indexWidth === "b000".U) -> VecInit(indices8),
      // 16-bit indices.
      (indexWidth === "b101".U) -> VecInit(indices16 ++ indices16),
      // 32-bit indices.
      (indexWidth === "b110".U) -> VecInit(
          indices32 ++ indices32 ++ indices32 ++ indices32),
    ))

    MuxUpTo1H(VecInit.fill(bytesPerSlot)(0.U(32.W)), Seq(
      // elemWidth validation is done at decode time.
      // 8-bit data. Each byte has its own offset.
      (sew === "b000".U) -> VecInit((0 until bytesPerSlot).map(
          i => (baseAddr + indices_v(i)))),
      // 16-bit data. Each 2-byte element has an offset.
      (sew === "b001".U) -> VecInit((0 until bytesPerSlot).map(
          i => (baseAddr + indices_v(i >> 1) + (i & 1).U))),
      // 32-bit data. Each 4-byte element has an offset.
      (sew === "b010".U) -> VecInit((0 until bytesPerSlot).map(
          i => (baseAddr + indices_v(i >> 2) + (i & 3).U)))
    ))
  }
}

class LsuVectorLoop extends Bundle {
  val subvector = new LoopingCounter(3.W)
  val segment = new LoopingCounter(4.W)
  val lmul = new LoopingCounter(4.W)
  // Additional internal states to help drive derived outputs.
  val rdStart = UInt(5.W)
  val rd = UInt(5.W)

  def isActive(): Bool = {
    (!subvector.isFull()) || (!segment.isFull()) || (!lmul.isFull())
  }

  def nextSubvector(): LsuVectorLoop = {
    val result = MakeWireBundle[LsuVectorLoop](new LsuVectorLoop, _ -> this)
    result.subvector := subvector.next()
    result
  }

  def nextVector(): LsuVectorLoop = {
    val result = MakeWireBundle[LsuVectorLoop](new LsuVectorLoop, _ -> this)
    result.subvector := subvector.reset()
    result.segment := Mux(segment.isFull(), segment.reset(), segment.next())
    result.lmul := Mux(segment.isFull(), lmul.next(), lmul)
    result.rd := Mux(segment.isFull(),
                     rdStart + lmul.next().curr,
                     rd + lmul.max)
    result
  }

  override def toPrintable: Printable = {
    cf"    subvector: ${subvector.curr} of [0..${subvector.max}]\n" +
    cf"    segment: ${segment.curr} of [0..${segment.max}]\n" +
    cf"    lmul: ${lmul.curr} of [0..${lmul.max}]\n" +
    cf"    rdStart: ${rdStart}\n    rd: ${rd}\n"
  }
}

// bytesPerSlot is the number of bytes in a vector register
// p.lsuDataBytes is the number of bytes in the AXI bus
class LsuSlot(p: Parameters, bytesPerSlot: Int) extends Bundle {
  val elemBits = log2Ceil(p.lsuDataBytes)

  val op = LsuOp()
  val rd = UInt(5.W)
  val store = Bool()
  val pc = UInt(32.W)
  val baseAddr = UInt(32.W)
  val active = Vec(bytesPerSlot, Bool())
  val addrs = Vec(bytesPerSlot, UInt(32.W))
  val data = Vec(bytesPerSlot, UInt(8.W))
  val pendingWriteback = Bool()
  val elemStride = UInt(32.W)     // Stride between lanes in a vector
  val segmentStride = UInt(32.W)  // Stride between base addr between segments
  // This aligns with "width" in the spec. It controls index width in
  // indexed loads/stores and data width otherwise.
  val elemWidth = UInt(3.W)
  // This controls data width in indexed loads/stores and is unused in
  // other ops.
  val sew = UInt(3.W)
  // Number of time indices repeats (up to 4 times)
  val indexParitions = UInt(3.W)
  val vectorLoop = new LsuVectorLoop()

  def pendingVector(): Bool = {
    !vectorLoop.subvector.isFull()
  }

  // If the slot has no pending tasks and can accept a new operation
  def slotIdle(): Bool = !(
      active.reduce(_||_) ||  // Active transaction
      pendingWriteback ||     // Send result back to regfile
      vectorLoop.isActive()     // More vector operations in progress
  )

  // If the slot has any active transactions.
  def activeTransaction(): Bool = {
    (!pendingVector()) && active.reduce(_||_)
  }

  def lineAddresses(): Vec[UInt] = {
    VecInit(addrs.map(x => x(31, elemBits)))
  }

  def elemAddresses(): Vec[UInt] = {
    VecInit(addrs.map(x => x(elemBits-1, 0)))
  }

  def targetAddress(lastRead: Valid[UInt]): Valid[UInt] = {
    // Determine which lines are active. If a read was issued last cycle,
    // supress those lines.
    val lineAddrs = lineAddresses()
    val lineActive = (0 until bytesPerSlot).map(i =>
        !pendingVector() &&
        active(i) && (!lastRead.valid || (lastRead.bits =/= lineAddrs(i))))

    MuxCase(MakeInvalid(UInt(32.W)), (0 until bytesPerSlot).map(
        i => lineActive(i) -> MakeValid(!pendingVector(), addrs(i))))
  }

  def vectorUpdate(rvv2lsu: Rvv2Lsu): LsuSlot = {
    val result = Wire(new LsuSlot(p, bytesPerSlot))
    result.op := op
    result.rd := rd
    result.store := store
    result.pc := pc
    result.pendingWriteback := pendingWriteback
    result.baseAddr := baseAddr
    result.elemStride := elemStride
    result.segmentStride := segmentStride
    result.indexParitions := indexParitions
    result.vectorLoop := vectorLoop.nextSubvector()
    result.elemWidth := elemWidth
    result.sew := sew

    val segmentBaseAddr = baseAddr + (segmentStride * vectorLoop.segment.curr)(31, 0)
    val bitsPerSlot = bytesPerSlot * 8
    val indices = MuxUpTo1H(rvv2lsu.idx.bits.data, Seq(
        // 2 of 2
        ((indexParitions === 2.U) && (vectorLoop.lmul.curr(0) === 1.U)) -> (rvv2lsu.idx.bits.data(bitsPerSlot - 1, bitsPerSlot / 2)),
        // 2 of 4
        ((indexParitions === 4.U) && (vectorLoop.lmul.curr(1, 0) === 1.U)) -> (rvv2lsu.idx.bits.data(bitsPerSlot / 2 - 1, bitsPerSlot / 4)),
        // 3 of 4
        ((indexParitions === 4.U) && (vectorLoop.lmul.curr(1, 0) === 2.U)) -> (rvv2lsu.idx.bits.data(bitsPerSlot * 3 / 4 - 1, bitsPerSlot / 2)),
        // 4 of 4
        ((indexParitions === 4.U) && (vectorLoop.lmul.curr(1, 0) === 3.U)) -> (rvv2lsu.idx.bits.data(bitsPerSlot - 1, bitsPerSlot * 3 / 4)),
    ))

    val shouldUpdate = LsuOp.isNonindexedVector(op) ||
                       (!vectorLoop.subvector.isEnabled()) ||
                       rvv2lsu.idx.valid
    val newActiveBytes = Mux(
        shouldUpdate && LsuOp.isVector(op) && rvv2lsu.mask.valid,
        VecInit(rvv2lsu.mask.bits.asBools),
        VecInit.fill(bytesPerSlot)(false.B))

    val updateAddrs = MuxUpTo1H(addrs, Seq(
        op.isOneOf(LsuOp.VLOAD_UNIT, LsuOp.VSTORE_UNIT) ->
            ComputeStridedAddrs(bytesPerSlot, segmentBaseAddr, elemStride, elemWidth),
        op.isOneOf(LsuOp.VLOAD_STRIDED, LsuOp.VSTORE_STRIDED) ->
            ComputeStridedAddrs(bytesPerSlot, segmentBaseAddr, elemStride, elemWidth),
        op.isOneOf(LsuOp.VLOAD_OINDEXED, LsuOp.VLOAD_UINDEXED,
                   LsuOp.VSTORE_OINDEXED, LsuOp.VSTORE_UINDEXED) ->
            ComputeIndexedAddrs(bytesPerSlot, segmentBaseAddr, indices,
                                elemWidth, sew),
    ))

    result.active := VecInit.tabulate(bytesPerSlot)(
        i => active(i) || newActiveBytes(i))

    result.addrs := VecInit.tabulate(bytesPerSlot)(
        i => Mux(newActiveBytes(i), updateAddrs(i), addrs(i)))

    result.data := Mux(shouldUpdate && LsuOp.isVector(op) && rvv2lsu.vregfile.valid,
        UIntToVec(rvv2lsu.vregfile.bits.data, 8), data)

    result
  }

  // Updates the slot based on a previous read.
  def loadUpdate(lineAddr: UInt, lineData: UInt): LsuSlot = {
    // TODO(derekjchow): Check ordering semantics
    val lineAddrs = lineAddresses()
    val lineActive = VecInit((0 until bytesPerSlot).map(i =>
        active(i) &&  // Update only if active
        (lineAddrs(i) === lineAddr)))  // Line must match read line
    val lineDataVec = UIntToVec(lineData, 8)
    val gatheredData = Gather(elemAddresses(), lineDataVec)

    val result = Wire(new LsuSlot(p, bytesPerSlot))
    result.op := op
    result.rd := rd
    result.store := store
    result.pc := pc
    result.baseAddr := baseAddr
    result.addrs := addrs
    result.pendingWriteback := pendingWriteback
    result.active := (0 until bytesPerSlot).map(
        i => active(i) & ~lineActive(i))
    result.data := VecInit((0 until bytesPerSlot).map(
        i => Mux(lineActive(i), gatheredData(i), data(i))))
    result.elemStride := elemStride
    result.segmentStride := segmentStride
    result.elemWidth := elemWidth
    result.sew := sew
    result.indexParitions := indexParitions
    result.vectorLoop := vectorLoop

    result
  }

  // If the load transaction is finished, but the result needs to be written
  // back to the regfile.
  def shouldWriteback(): Bool = {
    !pendingVector() && !active.reduce(_||_) && pendingWriteback
  }

  // Updates the slot if its result is written back to the regfile.
  def writebackUpdate(): LsuSlot = {
    val result = Wire(new LsuSlot(p, bytesPerSlot))
    result.op := op
    result.store := store
    result.pc := pc
    result.addrs := addrs
    result.active := active
    result.data := data
    result.elemStride := elemStride
    result.segmentStride := segmentStride
    result.elemWidth := elemWidth
    result.sew := sew

    val vectorLoopNext = vectorLoop.nextVector()
    val vectorWriteback = vectorLoop.isActive()
    val finished = vectorLoopNext.lmul.isFull()
    val finishedVectorLoop = Wire(new LsuVectorLoop)
    finishedVectorLoop := vectorLoop
    finishedVectorLoop.subvector.curr := vectorLoop.subvector.max
    finishedVectorLoop.segment.curr := vectorLoop.segment.max
    finishedVectorLoop.lmul.curr := vectorLoop.lmul.max

    result.indexParitions := indexParitions
    result.vectorLoop := Mux(finished,
                             finishedVectorLoop,
                             vectorLoopNext)
    result.pendingWriteback := !finished

    // TODO(davidgao): absorb baseAddr offset computation into vectorLoop
    val lmulUpdate = vectorWriteback && vectorLoop.segment.isFull()
    result.baseAddr := MuxCase(baseAddr, Seq(
      !lmulUpdate -> baseAddr,
      // For Unit and strided updates
      op.isOneOf(LsuOp.VLOAD_UNIT, LsuOp.VSTORE_UNIT) ->
          (baseAddr + (vectorLoop.segment.max * 16.U) + 16.U),
      op.isOneOf(LsuOp.VLOAD_STRIDED, LsuOp.VSTORE_STRIDED) ->
          MuxUpTo1H(baseAddr + (elemStride * bytesPerSlot.U), Seq(
            (elemWidth === "b000".U) ->
                (baseAddr + (elemStride * bytesPerSlot.U)),
            (elemWidth === "b101".U) ->
                (baseAddr + (elemStride * (bytesPerSlot/2).U)),
            (elemWidth === "b110".U) ->
                (baseAddr + (elemStride * (bytesPerSlot/4).U)),
          ))
          // (baseAddr + (vectorLoop.segment.max * elemStride)(31, 0)),

      // Indexed don't have base addr changed.
    ))
    result.rd := result.vectorLoop.rd

    result
  }

  def scatter(lineAddr: UInt): (Vec[UInt], Vec[Bool], Vec[Bool]) = {
    val canScatter = store && (!LsuOp.isVector(op) || !pendingVector())
    val lineAddrs = lineAddresses()
    val lineActive = VecInit((0 until bytesPerSlot).map(i =>
        canScatter && active(i) & (lineAddrs(i) === lineAddr)))
    Scatter(lineActive, elemAddresses(), data)
  }

  def storeUpdate(selected: Vec[Bool]): LsuSlot = {
    assert(selected.length == active.length)
    val result = Wire(new LsuSlot(p, bytesPerSlot))
    result.op := op
    result.rd := rd
    result.store := store
    result.pc := pc
    result.pendingWriteback := pendingWriteback
    result.active := (0 until bytesPerSlot).map(i => active(i) & ~selected(i))
    result.baseAddr := baseAddr
    result.addrs := addrs
    result.data := data
    result.elemStride := elemStride
    result.segmentStride := segmentStride
    result.elemWidth := elemWidth
    result.sew := sew
    result.indexParitions := indexParitions
    result.vectorLoop := vectorLoop
    result
  }

  def scalarLoadResult(): UInt = {
    val word = Cat(data(3), data(2), data(1), data(0))
    val half = Cat(data(1), data(0))
    val byte =  data(0)
    // Sign extends the result of a load operation when necessary.
    val halfSigned = Wire(SInt(32.W))
    halfSigned := half.asSInt
    val byteSigned = Wire(SInt(32.W))
    byteSigned := byte.asSInt
    MuxLookup(op, 0.U)(Seq(
      LsuOp.LB -> byteSigned.asUInt,
      LsuOp.LBU -> byte,
      LsuOp.LH -> halfSigned.asUInt,
      LsuOp.LHU -> half,
      LsuOp.LW -> word,
      LsuOp.FLOAT -> word,
    ))
  }

  override def toPrintable: Printable = {
    val lines = (0 until bytesPerSlot).map(i =>
        cf"  $i: ${active(i)}, 0x${addrs(i)}%x, 0x${data(i)}%x\n")
    cf"store: $store\n  op: ${op}\n  pc: 0x${pc}%x\n" +
    cf"  baseAddr: 0x${baseAddr}%x\n" +
    cf"  pendingWriteback: ${pendingWriteback}\n" +
    cf"  vectorLoop:\n${vectorLoop.toPrintable}" +
    cf"  elemWidth: 0b${elemWidth}%b elemStride: ${elemStride}\n" +
    lines.reduce(_+_)
  }
}

object LsuSlot {
  def inactive(p: Parameters, bytesPerSlot: Int): LsuSlot = {
    0.U.asTypeOf(new LsuSlot(p, bytesPerSlot))
  }

  def fromLsuUOp(uop: LsuUOp, p: Parameters, bytesPerSlot: Int): LsuSlot = {
    val result = Wire(new LsuSlot(p, bytesPerSlot))
    result.op := uop.op
    result.rd := uop.rd
    result.store := uop.store
    result.pc := uop.pc
    if (p.enableRvv) {
      val effectiveLmul = MuxCase(uop.emul_data.getOrElse(0.U)(1, 0), Seq(
        // Treat fractional EMULs as EMUL=1
        (uop.emul_data.getOrElse(0.U)(2)) -> 0.U(2.W),
      ))

      val nfields = Mux(LsuOp.isVector(uop.op), uop.nfields.get, 0.U)
      // Determine number of rvv2lsu interactions required for one vector for
      // indexed loads. This occurs when the index dtype is greater than data
      // dtype.
      val elemWidth = uop.elemWidth.get
      val elemMultiplier = MuxUpTo1H(1.U, Seq(
        // 8-bit data, 16-bit indices
        ((elemWidth === "b101".U) && (uop.sew.get === 0.U)) -> 2.U,
        // 8-bit data, 32-bit indices
        ((elemWidth === "b110".U) && (uop.sew.get === 0.U)) -> 4.U,
        // 16-bit data, 32-bit indices
        ((elemWidth === "b110".U) && (uop.sew.get === 1.U)) -> 2.U,
      ))
      val max_subvector = MuxUpTo1H(1.U, Seq(
        ((elemMultiplier === 2.U) && (uop.emul_data.get.asSInt >= 0.S)) -> 2.U,
        ((elemMultiplier === 4.U) && (uop.emul_data.get.asSInt >= 0.S)) -> 4.U,
        ((elemMultiplier === 4.U) && (uop.emul_data.get.asSInt === -1.S)) -> 2.U,
      ))
      // [0..x] data vecs we can operate on with one index vec
      result.indexParitions := MuxUpTo1H(1.U, Seq(
        // 16-bit data, 8-bit indices
        ((elemWidth === "b000".U) && (uop.sew.get === 1.U)) -> 2.U,
        // 32-bit data, 8-bit indices
        ((elemWidth === "b000".U) && (uop.sew.get === 2.U)) -> 4.U,
        // 32-bit data, 16-bit indices
        ((elemWidth === "b101".U) && (uop.sew.get === 2.U)) -> 2.U,
      ))
      result.vectorLoop := MakeWireBundle[LsuVectorLoop](
          new LsuVectorLoop,
          _.subvector -> LoopingCounter(MuxCase(0.U, Seq(
            LsuOp.isIndexedVector(uop.op) -> max_subvector,
            LsuOp.isVector(uop.op) -> 1.U,
          ))),
          _.segment -> LoopingCounter(
              Mux(LsuOp.isVector(uop.op), nfields, 0.U)),
          _.lmul -> LoopingCounter(
              Mux(LsuOp.isVector(uop.op), (1.U(4.W) << effectiveLmul), 0.U)),
          _.rdStart -> uop.rd,
          _.rd -> uop.rd,
      )
    } else {
      result.indexParitions := 0.U
      result.vectorLoop := 0.U.asTypeOf(result.vectorLoop)
    }

    // All vector ops require writeback. Lsu needs to inform RVV core store uop
    // has completed.
    result.pendingWriteback := !uop.store || LsuOp.isVector(uop.op)

    val active = MuxUpTo1H(0.U(bytesPerSlot.W), Seq(
      uop.op.isOneOf(LsuOp.LB, LsuOp.LBU, LsuOp.SB) -> "b1".U(bytesPerSlot.W),
      uop.op.isOneOf(LsuOp.LH, LsuOp.LHU, LsuOp.SH) -> "b11".U(bytesPerSlot.W),
      uop.op.isOneOf(LsuOp.LW, LsuOp.SW, LsuOp.FLOAT) -> "b1111".U(bytesPerSlot.W),
      // Vector
      LsuOp.isVector(uop.op) -> 0.U(bytesPerSlot.W),
    ))
    result.active := active.asBools

    // Compute addrs
    result.baseAddr := uop.addr
    result.elemWidth := uop.elemWidth.getOrElse(0.U(3.W))
    result.sew := uop.sew.getOrElse(0.U(3.W))
    result.addrs := Mux(
        uop.op.isOneOf(LsuOp.VLOAD_STRIDED, LsuOp.VSTORE_STRIDED),
        ComputeStridedAddrs(bytesPerSlot, uop.addr, uop.data, uop.elemWidth.getOrElse(0.U(3.W))),
        VecInit((0 until bytesPerSlot).map(i => uop.addr + i.U)))

    val unitStride = Mux(
        uop.op.isOneOf(LsuOp.VLOAD_OINDEXED, LsuOp.VLOAD_UINDEXED,
                       LsuOp.VSTORE_OINDEXED, LsuOp.VSTORE_UINDEXED),
        // Indexed load. The unit stride also controls segment stride.
        MuxUpTo1H(1.U, Seq(
            (result.sew === "b000".U) -> 1.U,  // 1-byte elements
            (result.sew === "b001".U) -> 2.U,  // 2-byte elements
            (result.sew === "b010".U) -> 4.U,  // 4-byte elements
        )),
        // Non-indexed load.
        MuxUpTo1H(1.U, Seq(
            (uop.elemWidth.getOrElse(3.U) === "b000".U) -> 1.U,  // 1-byte elements
            (uop.elemWidth.getOrElse(3.U) === "b101".U) -> 2.U,  // 2-byte elements
            (uop.elemWidth.getOrElse(3.U) === "b110".U) -> 4.U,  // 4-byte elements
        )),
    )

    result.segmentStride := unitStride
    result.elemStride := Mux(
        uop.op.isOneOf(LsuOp.VLOAD_UNIT, LsuOp.VSTORE_UNIT),
        unitStride + (uop.nfields.getOrElse(3.U) * unitStride),
        uop.data)

    result.data(0) := uop.data(7, 0)
    result.data(1) := uop.data(15, 8)
    result.data(2) := uop.data(23, 16)
    result.data(3) := uop.data(31, 24)
    for (i <- 4 until bytesPerSlot) {
      result.data(i) := 0.U
    }

    result
  }
}

class LsuCtrl(p: Parameters) extends Bundle {
  val pc = UInt(32.W)
  val addr = UInt(32.W)
  val adrx = UInt(32.W)
  val data = UInt(32.W)
  val index = UInt(5.W)
  val size = UInt((log2Ceil(p.lsuDataBits / 8) + 1).W)
  val fullsize = UInt((log2Ceil(p.lsuDataBits / 8) + 1).W)
  val write = Bool()
  val sext = Bool()
  val iload = Bool()
  val fencei = Bool()
  val flushat = Bool()
  val flushall = Bool()
  val sldst = Bool()  // scalar load/store cached
  val vldst = Bool()  // vector load/store
  val fldst = Bool() // float load/store
  val regionType = MemoryRegionType()
  val mask = UInt(p.lsuDataBytes.W)
  val last = Bool()
}

class LsuReadData(p: Parameters) extends Bundle {
  val addr = UInt(32.W)
  val index = UInt(5.W)
  val size = UInt((log2Ceil(p.lsuDataBits / 8) + 1).W)
  val fullsize = UInt((log2Ceil(p.lsuDataBits / 8) + 1).W)
  val sext = Bool()
  val iload = Bool()
  val sldst = Bool()
  val fldst = Bool()
  val regionType = MemoryRegionType()
  val mask = UInt(p.lsuDataBytes.W)
  val last = Bool()
}

object LsuBus extends ChiselEnum {
  val IBUS = Value
  val DBUS = Value
  val EXTERNAL = Value
}

class LsuRead(lineBits: Int) extends Bundle {
  val bus = LsuBus()
  val lineAddr = UInt(lineBits.W)
}

object LsuRead {
  def apply(bus: LsuBus.Type, lineAddr: UInt): LsuRead = {
    val result = Wire(new LsuRead(lineAddr.getWidth))
    result.bus := bus
    result.lineAddr := lineAddr
    result
  }
}

class FlushCmd extends Bundle {
  val all = Bool()
  val fencei = Bool()
  val pcNext = UInt(32.W)
}

object FlushCmd {
  def apply(cmd: LsuCmd): FlushCmd = {
    val result = Wire(new FlushCmd)
    result.all    := cmd.op.isOneOf(LsuOp.FENCEI, LsuOp.FLUSHALL)
    result.fencei := (cmd.op === LsuOp.FENCEI)
    result.pcNext := cmd.pc + 4.U
    result
  }
}

class LsuV2(p: Parameters) extends Lsu(p) {
  class LsuFault(p: Parameters) extends Bundle {
    val info = new FaultInfo(p)
    val rd = UInt(5.W)
    val op = LsuOp()
    val store = Bool()
  }

  // Tie-offs
  io.vldst := 0.U

  val opQueue = Module(new CircularBufferMulti(new LsuUOp(p), p.instructionLanes, 4))
  opQueue.io.flush := false.B
  io.queueCapacity := opQueue.io.nSpace

  // Flush state
  // DispatchV2 will only flush on first slot, when LSU is inactive.

  val flushCmd = RegInit(MakeInvalid(new FlushCmd))  // Track pending flush + pc
  io.flush.valid  := flushCmd.valid
  io.flush.all    := flushCmd.bits.all
  io.flush.clean  := true.B
  io.flush.fencei := flushCmd.bits.fencei
  io.flush.pcNext := flushCmd.bits.pcNext

  flushCmd := MuxCase(flushCmd, Seq(
    // New flush command
    (io.req(0).fire && LsuOp.isFlush(io.req(0).bits.op))
        -> MakeValid(true.B, FlushCmd(io.req(0).bits)),
    // Finish flush command
    (io.flush.valid && io.flush.ready) -> MakeInvalid(new FlushCmd),
  ))

  // Accept one instruction per cycle.
  val queueSpace = opQueue.io.nSpace
  val validSum = io.req.map(_.valid).scan(
      0.U(log2Ceil(p.instructionLanes + 1).W))(_+_)
  for (i <- 0 until p.instructionLanes) {
    io.req(i).ready := (validSum(i) < queueSpace) && !flushCmd.valid
  }

  val ops = (0 until p.instructionLanes).map(i =>
    MakeValid(
        io.req(i).fire && !LsuOp.isFlush(io.req(i).bits.op),
        LsuUOp(p, i, io.req(i).bits, io.busPort, io.busPort_flt, io.rvvState))
  )
  val alignedOps = Aligner(ops)

  opQueue.io.enqValid := PopCount(alignedOps.map(_.valid))
  opQueue.io.enqData := alignedOps.map(_.bits)
  assert(opQueue.io.enqValid <= opQueue.io.nSpace)

  val nextSlot = LsuSlot.fromLsuUOp(opQueue.io.dataOut(0), p, 16)

  // Tracks if a read has been fired last cycle.
  val readFired = RegInit(MakeInvalid(new LsuRead(32 - nextSlot.elemBits)))
  val slot = RegInit(LsuSlot.inactive(p, 16))

  val readData = MuxLookup(readFired.bits.bus, 0.U)(Seq(
      LsuBus.IBUS -> io.ibus.rdata,
      LsuBus.DBUS -> io.dbus.rdata,
      LsuBus.EXTERNAL -> io.ebus.dbus.rdata,
  ))

  // ==========================================================================
  // Vector update
  val vectorUpdatedSlot = if (p.enableRvv) {
      io.rvv2lsu.get(0).ready := slot.pendingVector()
      io.rvv2lsu.get(1).ready := false.B
      slot.vectorUpdate(io.rvv2lsu.get(0).bits)
  } else {
      slot
  }

  // ==========================================================================
  // Transaction update
  val faultReg = RegInit(MakeInvalid(new LsuFault(p)))

  // First stage of load update: Update results based on bus read
  val loadUpdatedSlot = Mux(readFired.valid,
                            slot.loadUpdate(readFired.bits.lineAddr, readData),
                            slot)

  // Compute next target transaction
  val targetAddress = loadUpdatedSlot.targetAddress(
      MakeValid(readFired.valid, readFired.bits.lineAddr))
  val targetLine = MakeValid(
      targetAddress.valid, targetAddress.bits(31, nextSlot.elemBits))
  val targetLineAddr = targetLine.bits << 4
  val itcm = p.m.filter(_.memType == MemoryRegionType.IMEM)
                .map(_.contains(targetLineAddr)).reduceOption(_ || _).getOrElse(false.B)
  val dtcm = p.m.filter(_.memType == MemoryRegionType.DMEM)
                .map(_.contains(targetLineAddr)).reduceOption(_ || _).getOrElse(true.B)
  val peri = p.m.filter(_.memType == MemoryRegionType.Peripheral)
                .map(_.contains(targetLineAddr)).reduceOption(_ || _).getOrElse(false.B)
  val external = !(itcm || dtcm || peri)
  assert(PopCount(Cat(itcm | dtcm | peri)) <= 1.U)

  val (wdata, wmask, wactive) = slot.scatter(targetLine.bits)

  val (opSize, alignedAddress) = LsuOp.opSize(slot.op, targetAddress.bits)

  // ibus data path
  io.ibus.valid := loadUpdatedSlot.activeTransaction() && itcm && !slot.store && !faultReg.valid
  io.ibus.addr := targetLineAddr

  // dbus data path
  io.dbus.valid := dtcm && Mux(slot.store,
                               slot.activeTransaction(),
                               loadUpdatedSlot.activeTransaction()) && !faultReg.valid
  io.dbus.write := slot.store
  io.dbus.pc := slot.pc
  io.dbus.addr := targetLineAddr
  io.dbus.adrx := targetLineAddr
  io.dbus.size := opSize
  io.dbus.wdata := Cat(wdata.reverse)
  io.dbus.wmask := Cat(wmask.reverse)

  // ebus data path
  io.ebus.dbus.valid := (external || peri) && Mux(slot.store,
                                                  slot.activeTransaction(),
                                                  loadUpdatedSlot.activeTransaction()) && !faultReg.valid
  io.ebus.dbus.write := slot.store
  io.ebus.dbus.addr := alignedAddress
  io.ebus.dbus.adrx := targetLineAddr
  io.ebus.dbus.size := opSize
  io.ebus.dbus.wdata := Cat(wdata.reverse)
  io.ebus.dbus.wmask := Cat(wmask.reverse)
  io.ebus.dbus.pc := slot.pc
  io.ebus.internal := peri

  val ibusFired = io.ibus.valid && io.ibus.ready
  val dbusFired = io.dbus.valid && io.dbus.ready
  val ebusFired = io.ebus.dbus.valid && io.ebus.dbus.ready
  assert(PopCount(Seq(ibusFired, dbusFired, ebusFired)) <= 1.U)
  val slotFired = ebusFired || dbusFired || ibusFired

  val readFiredValid = ibusFired || (dbusFired && !io.dbus.write) || (ebusFired && !io.ebus.dbus.write)
  readFired := MakeValid(readFiredValid,
    MuxCase(readFired.bits, Seq(
      (ibusFired) -> LsuRead(LsuBus.IBUS, targetLine.bits),
      (dbusFired && !io.dbus.write) -> LsuRead(LsuBus.DBUS, targetLine.bits),
      (ebusFired && !io.ebus.dbus.write) -> LsuRead(LsuBus.EXTERNAL, targetLine.bits),
    )))

  // Fault handling
  val ibusFault = Wire(Valid(new FaultInfo(p)))
  ibusFault.valid := loadUpdatedSlot.activeTransaction() && itcm && slot.store
  ibusFault.bits.write := true.B
  ibusFault.bits.addr := targetLineAddr
  ibusFault.bits.epc := slot.pc

  io.fault.valid := faultReg.valid
  io.fault.bits := faultReg.bits.info
  faultReg := {
    val f = Wire(Valid(new LsuFault(p)))
    val nextFaultInfo = MuxCase(MakeInvalid(new FaultInfo(p)), Seq(
        io.ebus.fault.valid -> io.ebus.fault,
        ibusFault.valid -> ibusFault,
    ))
    f.valid := nextFaultInfo.valid
    f.bits.info := nextFaultInfo.bits
    f.bits.rd := slot.rd
    f.bits.op := slot.op
    f.bits.store := slot.store
    f
  }

  // Transaction update
  val storeUpdate = Mux(slotFired, wactive, VecInit.fill(16)(false.B))
  val transactionUpdatedSlot = Mux(slot.store,
      slot.storeUpdate(storeUpdate), loadUpdatedSlot)
  val lsu2RvvFire = if (p.enableRvv) { io.lsu2rvv.get(0).fire } else { false.B }
  // For scalar stores: complete when transaction is done (slotFired && all bytes written)
  // For vector stores: complete when lsu2rvv handshake fires with last=1
  // These happen in different cycles, so we can't AND them together.
  val scalarStoreComplete = slotFired && slot.store && !slot.slotIdle() &&
      transactionUpdatedSlot.slotIdle() && !LsuOp.isVector(slot.op)
  val vectorStoreComplete = if (p.enableRvv) {
      lsu2RvvFire && io.lsu2rvv.get(0).bits.last
  } else { false.B }
  val storeComplete = scalarStoreComplete || vectorStoreComplete
  io.storeComplete := Mux(storeComplete && !io.ebus.fault.valid, MakeValid(slot.pc), MakeInvalid(UInt(32.W)))


  // ==========================================================================
  // Writeback update

  val currentOp = Mux(faultReg.valid, faultReg.bits.op, slot.op)
  val currentStore = Mux(faultReg.valid, faultReg.bits.store, slot.store)

  // Scalar writeback
  // Write back on error. io.fault.valid will mask
  io.rd.valid := ((faultReg.valid && LsuOp.isScalarLoad(faultReg.bits.op)) || slot.shouldWriteback()) &&
      currentOp.isOneOf(LsuOp.LB, LsuOp.LBU, LsuOp.LH, LsuOp.LHU, LsuOp.LW)

  io.rd.bits.data := slot.scalarLoadResult()
  io.rd.bits.addr := Mux(faultReg.valid, faultReg.bits.rd, slot.rd)

  // Float writeback
  io.rd_flt.valid := ((faultReg.valid && !currentStore) || slot.shouldWriteback()) &&
                     (currentOp === LsuOp.FLOAT)
  io.rd_flt.bits.addr := Mux(faultReg.valid, faultReg.bits.rd, slot.rd)
  io.rd_flt.bits.data := slot.scalarLoadResult()

  // Vector writeback
  if (p.enableRvv) {
    val faultDetected = faultReg.valid
    // If a fault occurs, we must still signal completion to the RVV core so it can
    // retire the instruction (and take the trap).
    val vectorFault = faultDetected && LsuOp.isVector(currentOp)

    io.lsu2rvv.get(0).valid := (slot.shouldWriteback() && LsuOp.isVector(currentOp)) || vectorFault
    io.lsu2rvv.get(0).bits.addr := Mux(faultReg.valid, faultReg.bits.rd, slot.rd)
    io.lsu2rvv.get(0).bits.data := Cat(slot.data.reverse)
    io.lsu2rvv.get(0).bits.last := (slot.shouldWriteback() || vectorFault) &&
        currentOp.isOneOf(LsuOp.VSTORE_UNIT, LsuOp.VSTORE_STRIDED,
                        LsuOp.VSTORE_OINDEXED, LsuOp.VSTORE_UINDEXED)

    io.lsu2rvv.get(1).valid := false.B
    io.lsu2rvv.get(1).bits.addr := 0.U
    io.lsu2rvv.get(1).bits.data := 0.U
    io.lsu2rvv.get(1).bits.last := true.B
  }

  val writebacksFired = Seq(io.rd.valid, io.rd_flt.valid) ++ (if (p.enableRvv) {
      Seq(io.lsu2rvv.get(0).fire) } else { Seq() })
  assert(PopCount(writebacksFired) <= 1.U)
  val writebackFired = writebacksFired.reduce(_ || _)
  val writebackUpdatedSlot = slot.writebackUpdate()

  // TODO(derekjchow): Improve timing?
  opQueue.io.deqReady := Mux(slot.slotIdle() && (opQueue.io.nEnqueued > 0.U), 1.U, 0.U)

  // ==========================================================================
  // State transition

  // Assertions
  val vectorUpdate = io.rvv2lsu.map(_(0).fire).getOrElse(false.B)
  assert(!vectorUpdate || slot.pendingVector())
  assert(!writebackFired || (slot.shouldWriteback() || faultReg.valid))

  // Slot update
  val slotNext = MuxCase(slot, Seq(
    // Move to inactive if error.
    (faultReg.valid) -> LsuSlot.inactive(p, 16),
    // When inactive, dequeue if possible
    (slot.slotIdle() && (opQueue.io.nEnqueued > 0.U)) -> nextSlot,
    // Vector update.
    vectorUpdate -> vectorUpdatedSlot,
    // Active transaction update.
    slot.activeTransaction() -> transactionUpdatedSlot,
    // Writeback update.
    writebackFired -> writebackUpdatedSlot,
  ))

  slot := slotNext

  io.active := !slot.slotIdle() || (opQueue.io.nEnqueued =/= 0.U)
}

// Per-byte lifecycle in an LsuV3SuperSlot entry.
//   Idle     -> no work assigned (default / post-completion).
//   Pending  -> addr (and data, for stores) ready; eligible for emitTxn.
//   InFlight -> selected into a TL-A beat; awaiting D-channel ack.
//   Done     -> work complete.
object LsuV3EntryState extends ChiselEnum {
  val Idle = Value
  val Pending = Value
  val InFlight = Value
  val Done = Value
}

class LsuV3Entry extends Bundle {
  val data = UInt(8.W)
  val addr = UInt(32.W)
  val state = LsuV3EntryState()
}

object LsuV3Entry {
  def apply(data: UInt, addr: UInt, state: LsuV3EntryState.Type): LsuV3Entry = {
    val result = Wire(new LsuV3Entry())
    result.data := data
    result.addr := addr
    result.state := state
    result
  }
}

class LsuV3SuperSlot(vlenb: Int, dlenb: Int) extends Bundle {
  // Geometry constants used throughout this class.
  private val nEntries = vlenb * 8
  private val nRegions = nEntries / dlenb
  private val lineBits = log2Ceil(dlenb)
  // Cap simultaneously-outstanding transactions at 2 (never exceeding the
  // number of distinct lines in the slot).
  private val nSources = nRegions.min(2)

  val store = Bool()
  val rd = UInt(5.W)
  val pc = UInt(32.W)  // Instruction PC, retained for fault epc.

  // Entries of the LSU slot
  val entries = Vec(nEntries, new LsuV3Entry())

  // Address to dispatch next
  val dispatchNext = Valid(UInt(32.W))

  // Source-tag table: index = local source id, valid = tag in use, bits = the
  // dispatched dlenb-line address.  Correlates address-less D responses
  // (tld.source) back to the line whose bytes they retire.
  val sources = Vec(nSources, Valid(UInt(32.W)))

  // Which region is active
  // val activeRegion = UInt(log2Ceil(nRegions+1).W)

  // Returns region n and n+1 which can participate in a LSU operation
  def getRegion(): Vec[LsuV3Entry] = {
    // TODO(derekjchow): Implement properly for scalar (window on activeRegion).
    VecInit((0 until 2 * dlenb).map(i => entries(i)))
  }

  def generateTransaction(tlp: TLULParameters)
      : (LsuV3SuperSlot, Valid[TileLink_A_ChannelBase[NoUser]]) = {
    val txn = Wire(new TileLink_A_ChannelBase(tlp, () => new NoUser))
    txn.opcode := Mux(
        store,
        TLULOpcodesA.PutPartialData,  // TODO(derekjchow): Relax this
        TLULOpcodesA.Get
    ).asUInt
    // TODO(derekjchow): Handle aligment and such
    txn.param := 0.U  // Reserved in TL-UL; must be 0 (param is only used by TL-C).
    txn.size  := log2Ceil(dlenb).U  // TODO: Populate based on transaction type
    // Allocate a free source tag for this transaction.  The TileLink source is
    // the master transaction ID; we set its MSB to 1 to mark LSU transactions,
    // leaving the whole MSB=0 half of the ID space to the AXI slave interface
    // (which thus has room for many concurrent IDs).  The free-tag index sits
    // in the low bits, so the LSU's IDs form a contiguous block.
    // TODO(derekjchow): the two ping-pong slots share the port, so LsuV3 must
    // offset each slot's source range (or pass a base) to avoid collisions.
    val freeMask = VecInit(sources.map(!_.valid))
    val hasFree  = freeMask.asUInt.orR
    val freeIdx  = PriorityEncoder(freeMask)
    txn.source := Cat(1.U(1.W), freeIdx.pad(tlp.o - 1))
    txn.address := dispatchNext.bits
    txn.user := DontCare  // NoUser carries no fields to drive.

    // Only the entries in the active region (getRegion) participate in a
    // transaction.  Restricting the scatter to this fixed-size window bounds
    // the comparator/select fan-in instead of scaling it with the whole slot.
    val region = getRegion()

    // Scatter the region bytes belonging to the dispatched line onto the TL-A
    // beat.  An entry participates when it is Pending and its address falls in
    // the same dlenb-sized line as dispatchNext; the entry's low address bits
    // select its lane within the beat.  Loads (Get) ignore the scattered data
    // but still assert the mask so the accessed bytes are tracked.
    // TODO(derekjchow): Properly support vector here.
    val dispatchLine = dispatchNext.bits(31, lineBits)
    val laneValid = VecInit(region.map(e =>
        dispatchNext.valid &&
        (e.state === LsuV3EntryState.Pending) &&
        (e.addr(31, lineBits) === dispatchLine)))
    val laneIndex = VecInit(region.map(_.addr(lineBits - 1, 0)))
    val laneData = VecInit(region.map(_.data))
    val (beatData, beatMask, selectedRegionEntities) =
        Scatter(laneValid, laneIndex, laneData)
    txn.mask := beatMask.asUInt
    txn.data := beatData.asUInt

    // A transaction is emitted only if there are bytes to send, a free source
    // tag, and the line is not already outstanding (keeps source<->line 1:1, so
    // a response's line-based retirement is unambiguous).
    val lineBusy = sources.map(s =>
        s.valid && (s.bits(31, lineBits) === dispatchLine)).reduce(_ || _)
    val txnValid = selectedRegionEntities.asUInt.orR && hasFree && !lineBusy

    // Advance the scattered bytes from Pending to InFlight: they are now
    // committed to this beat and must not be re-dispatched until their
    // D-channel response arrives.  getRegion() returns the leading 2*dlenb
    // entries, so region index i maps directly to entry i.
    // TODO(derekjchow): Once getRegion() windows on activeRegion, the region
    // index no longer equals the entry index -- map region index i back to its
    // global entry (entry = activeRegion*dlenb + i) before writing state here.
    val result = WireInit(this)
    for (i <- region.indices) {
      result.entries(i).state := Mux(
          selectedRegionEntities(i), LsuV3EntryState.InFlight, region(i).state)
    }
    // Record the dispatched line under the allocated tag.  Iterating the table
    // makes the valid bit a pure logical OR -- allocation can only set a tag,
    // never clear one -- instead of a dynamically-indexed write.  A non-firing
    // call (or a full table) leaves alloc low, so nothing is clobbered.
    for (i <- 0 until nSources) {
      val alloc = txnValid && (freeIdx === i.U)
      result.sources(i).valid := sources(i).valid || alloc
      result.sources(i).bits  := Mux(alloc, dispatchNext.bits, sources(i).bits)
    }
    // Advance dispatchNext to the next line in the region that still has
    // Pending bytes.  The bytes just committed to this beat are now InFlight
    // (selectedRegionEntities), so they are excluded; when nothing Pending
    // remains in the region, dispatchNext goes invalid and the region drains.
    val pendingAfter = region.indices.map(i =>
        !selectedRegionEntities(i) &&
        (region(i).state === LsuV3EntryState.Pending))
    result.dispatchNext := MakeValid(
        pendingAfter.reduce(_ || _),
        PriorityMux(pendingAfter, region.map(_.addr)))

    // Emit only when there are bytes to send and a free, non-conflicting tag.
    (result, MakeValid(txnValid, txn))
  }

  // Apply a TileLink-UL D-channel response to this slot.  The D channel carries
  // no address, only its source tag, so the tag is looked up in the source-tag
  // table to recover the dispatched line; retirement is gated to the InFlight
  // bytes whose line matches (mismatched/unallocated responses retire nothing
  // and trip an assert).  Load responses (AccessAckData) gather their byte from
  // the beat by lane; store responses (AccessAck) carry no data.  Matched bytes
  // retire to Done and the tag is freed.  Returns the updated slot and a
  // Valid[FaultInfo] (valid on tld.error) for the caller to raise a fault.
  def receiveTransaction(p: Parameters, tld: TileLink_D_ChannelBase[NoUser])
      : (LsuV3SuperSlot, Valid[FaultInfo]) = {
    val result = WireInit(this)
    val isLoad = !store
    val beatBytes = UIntToVec(tld.data, 8)  // dlenb response bytes, lane-indexed.

    // Recover the dispatched line for this response's source tag.  The LSU
    // encodes the tag in the low bits of the source (the MSB just marks LSU vs
    // AXI), so the local id is those low bits.
    val localId  = tld.source(log2Ceil(nSources) - 1, 0)
    val expected = sources(localId)
    assert(expected.valid,
        "LsuV3SuperSlot: D response for an unallocated source tag")

    // Retire only InFlight bytes whose line matches the tag's recorded line.
    val respMatch = VecInit((0 until nEntries).map(i =>
        (entries(i).state === LsuV3EntryState.InFlight) && expected.valid &&
        (entries(i).addr(31, lineBits) === expected.bits(31, lineBits))))
    for (i <- 0 until nEntries) {
      val lane = entries(i).addr(lineBits - 1, 0)
      result.entries(i).state :=
          Mux(respMatch(i), LsuV3EntryState.Done, entries(i).state)
      result.entries(i).data :=
          Mux(respMatch(i) && isLoad, beatBytes(lane), entries(i).data)
    }
    // Free the tag now that its bytes have retired.
    result.sources(localId).valid := false.B

    // The faulting effective address (mtval) is the lowest-index byte of the
    // access that just responded, taken from the pre-retire matched set.
    val fault = Wire(new FaultInfo(p))
    fault.write := store
    fault.addr  := PriorityMux(respMatch, entries.map(_.addr))
    fault.epc   := pc
    (result, MakeValid(tld.error, fault))
  }
}

object LsuV3SuperSlot {
  // All-zero super-slot for reset / idle state.  Matches LsuSlot.inactive.
  def inactive(vlenb: Int, dlenb: Int): LsuV3SuperSlot = {
    0.U.asTypeOf(new LsuV3SuperSlot(vlenb, dlenb))
  }

  def fromLsuUOp(uop: LsuUOp, vlenb: Int, dlenb: Int): LsuV3SuperSlot = {
    val result = Wire(new LsuV3SuperSlot(vlenb, dlenb))
    result.rd := uop.rd
    result.store := uop.store
    result.pc := uop.pc
    result.sources := VecInit(Seq.fill(result.nSources)(MakeInvalid(UInt(32.W))))

    val scalarElemSize = MuxLookup(uop.op, 1.U)(Seq(
      LsuOp.LB -> 1.U,
      LsuOp.LBU -> 1.U,
      LsuOp.LH -> 2.U,
      LsuOp.LHU -> 2.U,
      LsuOp.LW -> 4.U,
      LsuOp.SB -> 4.U,
      LsuOp.SH -> 4.U,
      LsuOp.SW -> 4.U,
      LsuOp.FLOAT -> 4.U,
    ))

    // TODO(derekjchow): Support vector
    result.entries := VecInit.tabulate(result.nEntries)(i =>
      if (i < 4) {
        LsuV3Entry(uop.data((8*i) + 7, 8*i),
                   addr + i.U,
                   LsuV3EntryState.Pending)
      } else {
        LsuV3Entry(0.U, 0.U, LsuV3EntryState.Done)
      }
    )
    result.dispatchNext := MakeValid(false.B, )


    // TODO(derekjchow): Address generator for float

    result
  }
}

// Lifecycle phase of an LsuV3 super-slot.
//   Inactive  -> free; may accept a dequeued op.
//   Fill      -> vector op pulling beats from rvv2lsu (scalar ops skip this).
//   Transact  -> issuing TL-A transactions and collecting D responses.
//   Writeback -> draining loads to lsu2rvv / writing rd / signaling done.
object LsuV3Phase extends ChiselEnum {
  val Inactive = Value
  val Fill = Value
  val Transact = Value
  val Writeback = Value
}

// LsuNext mirrors the Lsu interface with two changes: the cached ibus/dbus
// and external ebus collapse into a single consolidated TileLink-UL master
// port, and the scalar vldst switch and flush/fence interface are removed.
class LsuNext(p: Parameters) extends Module {
  val io = IO(new Bundle {
    // Decode cycle.
    val req = Vec(p.instructionLanes, Flipped(Decoupled(new LsuCmd(p))))
    val busPort = Flipped(new RegfileBusPortIO(p))
    val busPort_flt = Option.when(p.enableFloat)(Flipped(new RegfileBusPortIO(p)))

    // Execute cycle(s).
    val rd = Valid(Flipped(new RegfileWriteDataIO))
    val rd_flt = Valid(Flipped(new RegfileWriteDataIO))

    // Single consolidated master TileLink-UL port (A out, D in), NoUser.
    val tl = new TLULHost2Device(
        new TLULParameters(p), () => new NoUser, () => new NoUser)

    val fault = Valid(new FaultInfo(p))

    val rvv2lsu = Option.when(p.enableRvv)(
        Vec(2, Flipped(Decoupled(new Rvv2Lsu(p)))))
    val lsu2rvv = Option.when(p.enableRvv)(Vec(2, Decoupled(new Lsu2Rvv(p))))
    val rvvState = Option.when(p.enableRvv)(Input(Valid(new RvvConfigState(p))))

    val queueCapacity = Output(UInt(3.W))
    val active = Output(Bool())
    val storeComplete = Output(Valid(UInt(32.W)))
  })
}

// LsuV3 drives two LsuV3SuperSlots through their lifecycle, ping-ponging so
// that one slot can fill from rvv2lsu while the other transacts on the bus.
// Scalar ops bypass the fill phase; their entries are constructed directly
// from the command.  The shared TL master port supports several outstanding
// transactions, tracked by a source-tag table so D responses (which carry no
// address) can be written back to the right slot and line.
class LsuV3(p: Parameters) extends LsuNext(p) {
  // ===========================================================================
  // Instruction frontend
  // ===========================================================================
  val opQueue = Module(new CircularBufferMulti(new LsuUOp(p), p.instructionLanes, 4))
  opQueue.io.flush := false.B
  io.queueCapacity := opQueue.io.nSpace

  // Accept one instruction per cycle.
  val queueSpace = opQueue.io.nSpace
  val validSum = io.req.map(_.valid).scan(
      0.U(log2Ceil(p.instructionLanes + 1).W))(_+_)
  for (i <- 0 until p.instructionLanes) {
    io.req(i).ready := (validSum(i) < queueSpace) // && !flushCmd.valid
  }

  val ops = (0 until p.instructionLanes).map(i =>
    MakeValid(
        io.req(i).fire,
        // io.req(i).fire && !LsuOp.isFlush(io.req(i).bits.op),
        LsuUOp(p, i, io.req(i).bits, io.busPort, io.busPort_flt, io.rvvState))
  )
  val alignedOps = Aligner(ops)

  opQueue.io.enqValid := PopCount(alignedOps.map(_.valid))
  opQueue.io.enqData := alignedOps.map(_.bits)
  assert(opQueue.io.enqValid <= opQueue.io.nSpace)

  




}

object LsuV3 {
  def apply(p: Parameters): LsuV3 = Module(new LsuV3(p))
}

object LsuTlulMuxState extends ChiselEnum {
  val Idle = Value
  val Drive = Value
  val Resp = Value
}

// Region mux that adapts LsuV3's single NoUser TileLink master port to the
// core's legacy ibus (ITCM) / dbus (DTCM) / ebus (external + peripheral) buses.
// Single outstanding: accept one A, drive the region's bus, capture the
// response (1 cycle after the bus fires, matching LsuV2's readFired
// convention), and answer on D.  Routing mirrors LsuV2 (Lsu.scala emitTxn-era
// region decode): ITCM loads -> ibus, DTCM -> dbus, everything else -> ebus.
class LsuTlulMux(p: Parameters) extends Module {
  val tlp = new TLULParameters(p)
  val lineBytes = tlp.w
  val io = IO(new Bundle {
    val tl = Flipped(
        new TLULHost2Device(tlp, () => new NoUser, () => new NoUser))
    val ibus = new IBusIO(p)
    val dbus = new DBusIO(p)
    val ebus = new EBusIO(p)
  })

  def inRegion(t: MemoryRegionType.Type, a: UInt): Bool =
    p.m.filter(_.memType == t).map(_.contains(a)).reduceOption(_ || _)
        .getOrElse(false.B)

  val state = RegInit(LsuTlulMuxState.Idle)
  val reqIsLoad = Reg(Bool())
  val reqAddr = Reg(UInt(32.W))                 // line-aligned
  val reqData = Reg(UInt((8 * lineBytes).W))
  val reqMask = Reg(UInt(lineBytes.W))
  val reqSource = Reg(UInt(tlp.o.W))
  val reqTlSize = Reg(UInt(tlp.z.W))
  val busSel = Reg(UInt(2.W))                   // 0 = ibus, 1 = dbus, 2 = ebus

  // Region decode of the incoming A address.
  val aAddr = io.tl.a.bits.address
  val aIsLoad = io.tl.a.bits.opcode === TLULOpcodesA.Get.asUInt
  val aItcm = inRegion(MemoryRegionType.IMEM, aAddr)
  val aDtcm = inRegion(MemoryRegionType.DMEM, aAddr)
  val aSel = Mux(aItcm && aIsLoad, 0.U, Mux(aDtcm, 1.U, 2.U))

  // Mask-derived aligned byte address + access size for the ebus path
  // (peripheral/external accesses care about the access size).
  val offset = PriorityEncoder(reqMask)
  val count = PopCount(reqMask)
  val byteAddr = (reqAddr + offset)(31, 0)
  val peri = inRegion(MemoryRegionType.Peripheral, reqAddr)

  // ---- Defaults ----
  io.tl.a.ready := false.B
  io.tl.d.valid := false.B
  io.tl.d.bits := 0.U.asTypeOf(io.tl.d.bits)

  io.ibus.valid := false.B
  io.ibus.addr := reqAddr

  io.dbus.valid := false.B
  io.dbus.write := !reqIsLoad
  io.dbus.pc := 0.U
  io.dbus.addr := reqAddr
  io.dbus.adrx := reqAddr
  io.dbus.size := lineBytes.U
  io.dbus.wdata := reqData
  io.dbus.wmask := reqMask

  io.ebus.dbus.valid := false.B
  io.ebus.dbus.write := !reqIsLoad
  io.ebus.dbus.pc := 0.U
  io.ebus.dbus.addr := byteAddr
  io.ebus.dbus.adrx := reqAddr
  io.ebus.dbus.size := count
  io.ebus.dbus.wdata := reqData
  io.ebus.dbus.wmask := reqMask
  io.ebus.internal := peri

  switch (state) {
    is (LsuTlulMuxState.Idle) {
      io.tl.a.ready := true.B
      when (io.tl.a.fire) {
        reqIsLoad := aIsLoad
        reqAddr := aAddr
        reqData := io.tl.a.bits.data
        reqMask := io.tl.a.bits.mask
        reqSource := io.tl.a.bits.source
        reqTlSize := io.tl.a.bits.size
        busSel := aSel
        state := LsuTlulMuxState.Drive
      }
    }
    is (LsuTlulMuxState.Drive) {
      val fire = WireInit(false.B)
      switch (busSel) {
        is (0.U) { io.ibus.valid := true.B; fire := io.ibus.ready }
        is (1.U) { io.dbus.valid := true.B; fire := io.dbus.ready }
        is (2.U) { io.ebus.dbus.valid := true.B; fire := io.ebus.dbus.ready }
      }
      when (fire) { state := LsuTlulMuxState.Resp }
    }
    is (LsuTlulMuxState.Resp) {
      // Read data is valid the cycle after the bus fired (TCM and ebus alike,
      // per LsuV2's readFired timing).
      val rdata = MuxLookup(busSel, io.dbus.rdata)(Seq(
          0.U -> io.ibus.rdata, 1.U -> io.dbus.rdata, 2.U -> io.ebus.dbus.rdata))
      val err = (busSel === 0.U && io.ibus.fault.valid) ||
                (busSel === 2.U && io.ebus.fault.valid)
      io.tl.d.valid := true.B
      io.tl.d.bits.opcode := Mux(reqIsLoad,
          TLULOpcodesD.AccessAckData.asUInt, TLULOpcodesD.AccessAck.asUInt)
      io.tl.d.bits.param := 0.U
      io.tl.d.bits.size := reqTlSize
      io.tl.d.bits.source := reqSource
      io.tl.d.bits.sink := 0.U
      io.tl.d.bits.data := rdata
      io.tl.d.bits.error := err
      when (io.tl.d.fire) { state := LsuTlulMuxState.Idle }
    }
  }
}

// Drop-in replacement for LsuV2 that runs LsuV3 internally.  Presents the
// legacy Lsu IO (ibus/dbus/ebus/flush/vldst) so SCore/CoreAxi are unchanged:
// LsuV3's consolidated TL port is region-muxed back to the three buses, and a
// small shim re-implements the fence/flush interface LsuV3 does not provide.
class LsuV3Wrapper(p: Parameters) extends Lsu(p) {
  val v3 = Module(new LsuV3(p))
  val mux = Module(new LsuTlulMux(p))

  // TL master -> region mux -> legacy buses.
  mux.io.tl <> v3.io.tl
  io.ibus <> mux.io.ibus
  io.dbus <> mux.io.dbus
  io.ebus <> mux.io.ebus

  // vldst is unused (LsuV2 ties it off too).
  io.vldst := 0.U

  // Shared ports pass straight through.
  v3.io.busPort := io.busPort
  if (p.enableFloat) { v3.io.busPort_flt.get := io.busPort_flt.get }
  io.rd := v3.io.rd
  io.rd_flt := v3.io.rd_flt
  io.fault := v3.io.fault
  io.active := v3.io.active
  io.queueCapacity := v3.io.queueCapacity
  io.storeComplete := v3.io.storeComplete
  if (p.enableRvv) {
    v3.io.rvvState.get := io.rvvState.get
    io.lsu2rvv.get <> v3.io.lsu2rvv.get
    v3.io.rvv2lsu.get <> io.rvv2lsu.get
  }

  // ---- Fence/flush shim (lifted from LsuV2). ----
  val flushCmd = RegInit(MakeInvalid(new FlushCmd))
  io.flush.valid  := flushCmd.valid
  io.flush.all    := flushCmd.bits.all
  io.flush.clean  := true.B
  io.flush.fencei := flushCmd.bits.fencei
  io.flush.pcNext := flushCmd.bits.pcNext
  flushCmd := MuxCase(flushCmd, Seq(
    (io.req(0).fire && LsuOp.isFlush(io.req(0).bits.op))
        -> MakeValid(true.B, FlushCmd(io.req(0).bits)),
    (io.flush.valid && io.flush.ready) -> MakeInvalid(new FlushCmd),
  ))

  // Route req: fence/flush ops are consumed by the shim; others go to LsuV3.
  // Accept gating mirrors LsuV2 (no new ops while a flush is pending).
  for (i <- 0 until p.instructionLanes) {
    val isFlushOp = LsuOp.isFlush(io.req(i).bits.op)
    v3.io.req(i).valid := io.req(i).valid && !isFlushOp && !flushCmd.valid
    v3.io.req(i).bits := io.req(i).bits
    io.req(i).ready := Mux(isFlushOp, true.B, v3.io.req(i).ready) && !flushCmd.valid
  }
}

@_root_.scala.annotation.nowarn
object LsuV3Emitter extends App {
  import _root_.circt.stage.{ChiselStage, FirtoolOption}
  import chisel3.stage.ChiselGeneratorAnnotation
  val p = new Parameters
  // dlenb = axi2DataBytes = lsuDataBits/8 must equal vlenb (=16) so the TL
  // beat matches the RVV beat; enableRvv exposes the rvv2lsu/lsu2rvv ports.
  p.lsuDataBits = 128
  p.enableRvv = true
  (new ChiselStage).execute(
    Array("--target", "systemverilog") ++ args,
    Seq(ChiselGeneratorAnnotation(() => new LsuV3(p))) ++
      Seq(FirtoolOption("-enable-layers=Verification"))
  )
}
